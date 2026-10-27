package com.seatsac.booking.service;

import com.seatsac.booking.common.BookingErrorCode;
import com.seatsac.booking.component.PaymentClient;
import com.seatsac.booking.component.PaymentClient.PaymentRequest;
import com.seatsac.booking.component.PaymentClient.PaymentResult;
import com.seatsac.booking.dto.request.BookingPayRequest;
import com.seatsac.booking.dto.response.BookingPaymentResponse;
import com.seatsac.booking.dto.response.PaymentView;
import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.booking.service.BookingPaymentResultApplier.Applied;
import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.ErrorDetail;
import com.seatsac.common.core.time.Times;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * BKG-10 결제 요청 — 명세 5장. 결과 반영(BKG-06)은 {@link BookingPaymentResultApplier}가 하고,
 * 이 클래스는 <b>앞단 검증과 결제 ID 발급·응답 변환</b>만 맡는다.
 *
 * <pre>
 * 1. 검증(본인·PENDING·선점 유효·결제 1회) + paymentId 발급 → 조건부 UPDATE로 붙임
 * 2. Payment 동기 호출 (응답을 못 받으면 1회 재호출 → 조회, 끝까지 모르면 503)
 * 3. 결과 반영 → 응답 (승인 200 CONFIRMED · 거절 200 PAYMENT_FAILED · 만료 409 + 환불)
 * </pre>
 *
 * <p><b>예매당 결제 1회</b>(ADR-24)는 {@code current_payment_id}를 붙이는 조건부 UPDATE가 막는다 —
 * 두 번째 요청은 0행이라 409 {@code PAYMENT_ALREADY_REQUESTED}이고, 결과는 단건 조회로 본다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingPaymentService {

    private final Clock clock;
    private final BookingRepositoryReader bookingReader;
    private final BookingStateService bookingState;
    private final BookingPaymentResultApplier resultApplier;
    private final PaymentClient paymentClient;

    /** 거절도 200이고 {@code status}로 구분한다 */
    public BookingPaymentResponse pay(String userId, Long bookingId, BookingPayRequest request) {
        Instant now = clock.instant();
        Booking booking = bookingReader.findById(bookingId)
            .orElseThrow(BookingErrorCode.BOOKING_NOT_FOUND::exception);
        validate(booking, userId, now);

        String paymentId = UUID.randomUUID().toString();
        if (!bookingState.attachPayment(bookingId, paymentId, now)) {
            throw conflictFor(bookingId, now);
        }

        // 결과를 못 받으면 PaymentClient가 503 PAYMENT_RESULT_UNKNOWN을 던진다 (예매는 PENDING 유지)
        PaymentResult payment = paymentClient.request(new PaymentRequest(paymentId, bookingId,
            booking.getAmount(), request.method(), card(request)));

        Applied applied = payment.isApproved()
            ? resultApplier.applyApproved(booking, payment)
            : resultApplier.applyDeclined(booking);

        return switch (applied) {
            // 내가 확정했거나, 폴링 경로가 먼저 확정했거나 — 사용자가 원한 결과는 같다
            case CONFIRMED, ALREADY_CONFIRMED ->
                response(booking, BookingStatus.CONFIRMED, confirmedAt(bookingId), payment);
            case FAILED -> response(booking, BookingStatus.PAYMENT_FAILED, null, payment);
            case EXPIRED_REFUNDED -> throw BookingErrorCode.HOLD_EXPIRED.exception(
                ErrorDetail.of("status", BookingStatus.EXPIRED.name(),
                    BookingErrorCode.HOLD_EXPIRED.name()),
                ErrorDetail.of("refunded", true));
            // 거절을 단건 조회 폴링이 먼저 반영했을 수 있다. 그때는 이 요청의 결과와 같으므로
            // 409가 아니라 200 PAYMENT_FAILED다(같은 요청에 같은 답). 그 밖의 경합은 409
            case ALREADY_SETTLED -> {
                if (declinedAlready(bookingId, paymentId)) {
                    yield response(booking, BookingStatus.PAYMENT_FAILED, null, payment);
                }
                throw conflictFor(bookingId, clock.instant());
            }
        };
    }

    private void validate(Booking booking, String userId, Instant now) {
        if (!booking.isOwnedBy(userId)) {
            throw BookingErrorCode.NOT_OWNER.exception();
        }
        if (booking.getCurrentPaymentId() != null) {
            throw BookingErrorCode.PAYMENT_ALREADY_REQUESTED.exception(
                ErrorDetail.of("bookingId", booking.getBookingId(),
                    BookingErrorCode.PAYMENT_ALREADY_REQUESTED.name()));
        }
        if (booking.getStatus() != BookingStatus.PENDING) {
            throw BookingErrorCode.BOOKING_NOT_PENDING.exception(ErrorDetail.of("status",
                booking.getStatus().name(), BookingErrorCode.BOOKING_NOT_PENDING.name()));
        }
        if (booking.isExpiredAt(now)) {
            throw BookingErrorCode.HOLD_EXPIRED.exception(ErrorDetail.of("status",
                booking.getStatus().name(), BookingErrorCode.HOLD_EXPIRED.name()));
        }
    }

    /** 결제 ID를 붙이지 못했을 때 현재 상태를 읽어 알맞은 409를 만든다 */
    private BusinessException conflictFor(Long bookingId, Instant now) {
        Booking current = bookingReader.findById(bookingId).orElse(null);
        if (current == null) {
            return BookingErrorCode.BOOKING_NOT_FOUND.exception();
        }
        if (current.getCurrentPaymentId() != null) {
            return BookingErrorCode.PAYMENT_ALREADY_REQUESTED.exception(
                ErrorDetail.of("bookingId", bookingId,
                    BookingErrorCode.PAYMENT_ALREADY_REQUESTED.name()));
        }
        if (current.isExpiredAt(now)) {
            return BookingErrorCode.HOLD_EXPIRED.exception(ErrorDetail.of("status",
                current.getStatus().name(), BookingErrorCode.HOLD_EXPIRED.name()));
        }
        return BookingErrorCode.BOOKING_NOT_PENDING.exception(ErrorDetail.of("status",
            current.getStatus().name(), BookingErrorCode.BOOKING_NOT_PENDING.name()));
    }

    /** 다른 경로가 확정했다면 그쪽이 쓴 시각을 쓴다 */
    /**
     * <b>같은 결제</b>의 거절을 다른 경로(단건 조회 폴링)가 먼저 반영했는지. 그렇다면 이 요청의
     * 결과와 같으니 409가 아니라 200 {@code PAYMENT_FAILED}로 답한다 — 같은 요청에 같은 답이다.
     */
    private boolean declinedAlready(Long bookingId, String paymentId) {
        return bookingReader.findById(bookingId)
            .filter(b -> b.getStatus() == BookingStatus.PAYMENT_FAILED)
            .filter(b -> paymentId.equals(b.getCurrentPaymentId()))
            .isPresent();
    }

    private java.time.OffsetDateTime confirmedAt(Long bookingId) {
        return bookingReader.findById(bookingId)
            .map(Booking::getConfirmedAt)
            .map(Times::toApi)
            .orElseGet(() -> Times.toApi(clock.instant()));
    }

    private static BookingPaymentResponse response(Booking booking, BookingStatus status,
                                                    java.time.OffsetDateTime confirmedAt,
                                                    PaymentResult payment) {
        return BookingPaymentResponse.builder()
            .bookingId(booking.getBookingId())
            .status(status)
            .confirmedAt(confirmedAt)
            .seatLabels(booking.getSeatLabels())
            .payment(PaymentView.of(payment.paymentId(), payment.status(), payment.cardLast4(),
                payment.publicFailureCode(), payment.approvedAt(), payment.canceledAt()))
            .build();
    }

    private static PaymentRequest.Card card(BookingPayRequest request) {
        return new PaymentRequest.Card(request.card().number(), request.card().expiry(),
            request.card().holder());
    }
}

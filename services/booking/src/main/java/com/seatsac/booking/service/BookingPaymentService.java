package com.seatsac.booking.service;

import com.seatsac.booking.common.BookingErrorCode;
import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.component.PaymentClient;
import com.seatsac.booking.component.PaymentClient.PaymentRequest;
import com.seatsac.booking.component.PaymentClient.PaymentResult;
import com.seatsac.booking.component.ScreeningCache;
import com.seatsac.booking.config.PaymentProperties;
import com.seatsac.booking.dto.request.BookingPayRequest;
import com.seatsac.booking.dto.request.PaymentPrepareRequest;
import com.seatsac.booking.dto.response.BookingPaymentResponse;
import com.seatsac.booking.dto.response.PaymentPrepareResponse;
import com.seatsac.booking.dto.response.PaymentView;
import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.booking.enums.PaymentMethod;
import com.seatsac.booking.service.BookingPaymentResultApplier.Applied;
import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.ErrorDetail;
import com.seatsac.common.core.time.Times;
import com.seatsac.common.web.user.AuthUser;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * BKG-10 결제 요청 — 명세 5장, ADR-112. 결과 반영(BKG-06)은 {@link BookingPaymentResultApplier}가 하고,
 * 이 클래스는 <b>앞단 검증과 결제 ID 발급·응답 변환</b>만 맡는다.
 *
 * <p><b>TOSSPAY(화면)</b>는 두 번에 나뉜다.
 * <pre>
 * ① 준비 {@link #prepare}: 검증(본인·PENDING·선점 유효·결제 1회·예매 마감 전) + paymentId 발급 → 조건부 UPDATE로 붙임
 *    → payment가 PROCESSING 행 + 결제창 값(checkout). 돈은 움직이지 않는다
 * ② 화면이 포트원 결제창
 * ③ 완료 {@link #pay}(TOSSPAY): 붙은 paymentId인지 확인 → payment verify(포트원 단건 조회) → 결과 반영 → 응답
 * </pre>
 * ③은 <b>만료·마감 검사를 하지 않는다</b> — 사용자는 이미 결제창에서 돈을 냈을 수 있다. 만료됐으면 결과 반영이
 * 환불하고 409(ADR-100)로 답한다.
 *
 * <p><b>CARD(모의)</b>는 테스트·분석 전용이다 — {@code X-Payment-Mock-Key}가 설정 키와 같거나 ADMIN일 때만 받고
 * 아니면 400 {@code VALIDATION_FAILED}(reason {@code MOCK_NOT_ALLOWED}). 흐름은 ①과 같은 검증 → payment 동기
 * 판정(응답을 못 받으면 1회 재호출 → 조회, 끝까지 모르면 503) → 결과 반영 한 번이다.
 *
 * <p>응답은 둘 다 같다 — 승인 200 CONFIRMED · 거절 200 PAYMENT_FAILED · 만료 409 + 환불 · 환불 미확인 503.
 *
 * <p><b>예매당 결제 1회</b>(ADR-24)는 {@code current_payment_id}를 붙이는 조건부 UPDATE가 막는다 —
 * 두 번째 요청은 0행이라 409 {@code PAYMENT_ALREADY_REQUESTED}이고, 결과는 단건 조회로 본다.
 *
 * <p><b>예매 마감</b>(상영 시작 5분 전, {@link BookingPolicy#BOOKING_CUTOFF})이 지나면 결제 ID를 붙이기 전에
 * 400 {@code SCREENING_STARTED}로 거절한다 — 돈이 나가지 않고, 선점은 TTL로 풀린다. 상영 시작 뒤에 승인된
 * 확정 예매가 생기면 취소(BKG-07)가 막혀 환불할 수 없기 때문이다. 회차 정보를 못 받으면 503(아무것도 바뀌지 않음).
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
    private final ScreeningCache screeningCache;
    private final PaymentProperties paymentProperties;

    /**
     * ① TOSSPAY 결제 준비. payment에 닿지 못하면 503이고 예매에는 paymentId가 붙은 채 PENDING으로 남는다 —
     * 다시 준비할 수 없으니 선점 만료 + 유예 뒤 만료 처리가 정리한다(payment 행이 없으면 일반 만료).
     */
    public PaymentPrepareResponse prepare(String userId, Long bookingId, PaymentPrepareRequest request) {
        if (request.method() != PaymentMethod.TOSSPAY) {
            throw BookingErrorCode.INVALID_PAYMENT_METHOD.exception(ErrorDetail.of("method",
                request.method().name(), BookingErrorCode.INVALID_PAYMENT_METHOD.name()));
        }
        Instant now = clock.instant();
        Booking booking = bookingReader.findById(bookingId)
            .orElseThrow(BookingErrorCode.BOOKING_NOT_FOUND::exception);
        String paymentId = attachNewPayment(booking, userId, now);

        String orderName = BookingPolicy.orderNameOf(booking.getSeatCount());
        PaymentResult prepared = paymentClient.prepare(new PaymentRequest(paymentId, bookingId,
            booking.getAmount(), PaymentMethod.TOSSPAY, null, orderName));
        if (!prepared.isProcessing() || prepared.checkout() == null) {
            // 준비 응답은 PROCESSING + checkout뿐이다. 그 밖이면 payment 쪽 버그 — 결과를 모른다고 답한다
            log.warn("결제 준비 응답이 예상과 다르다 bookingId={} paymentId={} status={}", bookingId,
                paymentId, prepared.status());
            throw BookingErrorCode.PAYMENT_RESULT_UNKNOWN.exception(ErrorDetail.of("paymentId", paymentId));
        }

        PaymentClient.Checkout checkout = prepared.checkout();
        return PaymentPrepareResponse.builder()
            .bookingId(bookingId)
            .paymentId(paymentId)
            .amount(booking.getAmount())
            .orderName(orderName)
            .expiresAt(Times.toApi(booking.getExpiresAt()))
            .checkout(new PaymentPrepareResponse.Checkout(checkout.provider(), checkout.storeId(),
                checkout.channelKey(), checkout.payMethod(), checkout.currency()))
            .build();
    }

    /** 거절도 200이고 {@code status}로 구분한다 */
    public BookingPaymentResponse pay(AuthUser user, String mockKey, Long bookingId,
                                      BookingPayRequest request) {
        return switch (request.method()) {
            case CARD -> payWithMock(user, mockKey, bookingId, request);
            case TOSSPAY -> completeTossPay(user.userId(), bookingId, request.paymentId());
        };
    }

    /** CARD(모의) — 테스트·분석 전용. 결제 ID 발급부터 판정까지 한 번에 끝난다 */
    private BookingPaymentResponse payWithMock(AuthUser user, String mockKey, Long bookingId,
                                               BookingPayRequest request) {
        if (!user.isAdmin() && !paymentProperties.mockKeyMatches(mockKey)) {
            throw BookingErrorCode.MOCK_NOT_ALLOWED.exception(ErrorDetail.of("method",
                PaymentMethod.CARD.name(), BookingErrorCode.MOCK_NOT_ALLOWED.name()));
        }
        if (request.card() == null) {
            throw BookingErrorCode.INVALID_PAYMENT_METHOD.exception(
                ErrorDetail.required("card"));
        }

        Instant now = clock.instant();
        Booking booking = bookingReader.findById(bookingId)
            .orElseThrow(BookingErrorCode.BOOKING_NOT_FOUND::exception);
        String paymentId = attachNewPayment(booking, user.userId(), now);

        // 결과를 못 받으면 PaymentClient가 503 PAYMENT_RESULT_UNKNOWN을 던진다 (예매는 PENDING 유지)
        PaymentResult payment = paymentClient.request(new PaymentRequest(paymentId, bookingId,
            booking.getAmount(), PaymentMethod.CARD, card(request), null));

        return settle(booking, paymentId, payment, false);
    }

    /**
     * ③ TOSSPAY 완료 확인. 결제창이 성공이든 취소·실패든 화면은 이것을 부른다 — 결과는 payment가 포트원에서
     * 확인한다(브라우저가 보낸 결과를 믿지 않는다).
     */
    private BookingPaymentResponse completeTossPay(String userId, Long bookingId, String paymentId) {
        if (paymentId == null) {
            throw BookingErrorCode.INVALID_PAYMENT_METHOD.exception(ErrorDetail.required("paymentId"));
        }
        Booking booking = bookingReader.findById(bookingId)
            .orElseThrow(BookingErrorCode.BOOKING_NOT_FOUND::exception);
        if (!booking.isOwnedBy(userId)) {
            throw BookingErrorCode.NOT_OWNER.exception();
        }
        if (!paymentId.equals(booking.getCurrentPaymentId())) {
            // 준비 없이 왔거나 다른 결제의 ID다
            throw BookingErrorCode.PAYMENT_ID_MISMATCH.exception(ErrorDetail.of("paymentId", paymentId,
                BookingErrorCode.PAYMENT_ID_MISMATCH.name()));
        }
        if (booking.getStatus() != BookingStatus.PENDING) {
            // 단건 조회 폴링·만료 처리가 먼저 끝냈다 — 같은 결제의 결과를 그대로 알려 준다
            return settledResponse(booking);
        }

        // 결제창 미완료(PROCESSING)·결과 미확인이면 503 PAYMENT_RESULT_UNKNOWN — 예매는 PENDING 유지
        PaymentResult payment = paymentClient.verify(paymentId);
        return settle(booking, paymentId, payment, true);
    }

    /** 검증 → 마감 확인 → 새 결제 ID를 조건부 UPDATE로 붙인다. 붙이지 못하면 현재 상태에 맞는 409 */
    private String attachNewPayment(Booking booking, String userId, Instant now) {
        validate(booking, userId, now);
        Long bookingId = booking.getBookingId();
        screeningCache.requireOpen(screeningCache.screeningOfBooking(bookingId, booking.getScreeningId()));

        String paymentId = UUID.randomUUID().toString();
        if (!bookingState.attachPayment(bookingId, paymentId, now)) {
            throw conflictFor(bookingId, now);
        }
        return paymentId;
    }

    /**
     * 결제 결과를 예매에 반영하고 응답을 만든다 (CARD·TOSSPAY 공통).
     *
     * @param completion TOSSPAY 완료 확인인가 — 다른 경로가 먼저 끝냈을 때 그 결과로 답한다
     */
    private BookingPaymentResponse settle(Booking booking, String paymentId, PaymentResult payment,
                                          boolean completion) {
        Long bookingId = booking.getBookingId();
        Applied applied;
        if (payment.isApproved()) {
            applied = resultApplier.applyApproved(booking, payment);
        } else if (payment.isCanceled()) {
            // 포트원에서 이미 취소된 결제다. PENDING에서는 만료로 끝낸다(단건 조회 reconcile과 같은 규칙)
            applied = resultApplier.applyRefundedExpiry(booking);
        } else {
            applied = resultApplier.applyDeclined(booking);
        }

        return switch (applied) {
            // 내가 확정했거나, 폴링 경로가 먼저 확정했거나 — 사용자가 원한 결과는 같다
            case CONFIRMED, ALREADY_CONFIRMED ->
                response(booking, BookingStatus.CONFIRMED, confirmedAt(bookingId), payment);
            case FAILED -> response(booking, BookingStatus.PAYMENT_FAILED, null, payment);
            case EXPIRED_REFUNDED -> throw expiredRefunded(true);
            // 만료라 환불을 시도했지만 확인하지 못했다 — 예매는 PENDING, 돈은 잡혀 있을 수 있다.
            // 「만료·환불됨」(409 refunded=true)으로 답하면 거짓이다. 결과 미확인(503)으로 답하고
            // 화면이 단건 조회로 다시 확인하게 한다 — 그 조회가 환불을 다시 시도한다
            case REFUND_PENDING -> throw BookingErrorCode.PAYMENT_RESULT_UNKNOWN.exception();
            // 거절을 단건 조회 폴링이 먼저 반영했을 수 있다. 그때는 이 요청의 결과와 같으므로
            // 409가 아니라 200 PAYMENT_FAILED다(같은 요청에 같은 답). 그 밖의 경합은 409
            case ALREADY_SETTLED -> {
                if (declinedAlready(bookingId, paymentId)) {
                    yield response(booking, BookingStatus.PAYMENT_FAILED, null, payment);
                }
                if (completion) {
                    Booking current = bookingReader.findById(bookingId)
                        .orElseThrow(BookingErrorCode.BOOKING_NOT_FOUND::exception);
                    if (current.getStatus() != BookingStatus.PENDING) {
                        yield settledResponse(current);
                    }
                }
                throw conflictFor(bookingId, clock.instant());
            }
        };
    }

    /**
     * 이미 끝난 예매(PENDING 아님)에 온 TOSSPAY 완료 확인 — 결제를 다시 확인하지 않고 끝난 결과를 알려 준다.
     * 결제 정보를 조회하지 못하면 503 {@code PAYMENT_RESULT_UNKNOWN}(화면이 단건 조회로 다시 본다).
     */
    private BookingPaymentResponse settledResponse(Booking booking) {
        return switch (booking.getStatus()) {
            case CONFIRMED -> response(booking, BookingStatus.CONFIRMED,
                Times.toApi(booking.getConfirmedAt()), findPayment(booking));
            case PAYMENT_FAILED -> response(booking, BookingStatus.PAYMENT_FAILED, null,
                findPayment(booking));
            case EXPIRED -> throw expiredRefunded(paymentClient.find(booking.getCurrentPaymentId())
                .map(PaymentResult::isCanceled)
                .orElse(false));
            default -> throw BookingErrorCode.BOOKING_NOT_PENDING.exception(ErrorDetail.of("status",
                booking.getStatus().name(), BookingErrorCode.BOOKING_NOT_PENDING.name()));
        };
    }

    private PaymentResult findPayment(Booking booking) {
        return paymentClient.find(booking.getCurrentPaymentId())
            .orElseThrow(() -> BookingErrorCode.PAYMENT_RESULT_UNKNOWN.exception(
                ErrorDetail.of("paymentId", booking.getCurrentPaymentId())));
    }

    private static BusinessException expiredRefunded(boolean refunded) {
        return BookingErrorCode.HOLD_EXPIRED.exception(
            ErrorDetail.of("status", BookingStatus.EXPIRED.name(), BookingErrorCode.HOLD_EXPIRED.name()),
            ErrorDetail.of("refunded", refunded));
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

    /** 다른 경로가 확정했다면 그쪽이 쓴 시각을 쓴다 */
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
            .payment(PaymentView.of(payment.paymentId(), payment.status(), payment.method(),
                payment.cardLast4(), payment.publicFailureCode(), payment.approvedAt(),
                payment.canceledAt()))
            .build();
    }

    private static PaymentRequest.Card card(BookingPayRequest request) {
        return new PaymentRequest.Card(request.card().number(), request.card().expiry(),
            request.card().holder());
    }
}

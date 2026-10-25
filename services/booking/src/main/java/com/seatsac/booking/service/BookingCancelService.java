package com.seatsac.booking.service;

import com.seatsac.booking.common.BookingErrorCode;
import com.seatsac.booking.component.PaymentClient;
import com.seatsac.booking.component.PaymentClient.PaymentCancelReason;
import com.seatsac.booking.component.PaymentClient.PaymentResult;
import com.seatsac.booking.component.SeatEventPublisher;
import com.seatsac.booking.dto.response.BookingCancelResponse;
import com.seatsac.booking.dto.response.PaymentView;
import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.SeatEventType;
import com.seatsac.common.core.error.ErrorDetail;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * BKG-07 확정 예매 취소 — 명세 5장. 상태를 먼저 바꾸고(조건부 UPDATE) 그 다음에 환불한다.
 *
 * <p>순서의 이유: 환불을 먼저 하면 그 뒤 상태 전이가 실패했을 때 「환불됐는데 예매는 확정」이
 * 남는다. 반대로 상태를 먼저 바꾸면 환불 호출이 실패해도 예매는 취소된 상태이고, PAY-03은
 * 멱등이라 다시 부르면 된다.
 *
 * <p>이미 취소된 예매는 <b>그대로 200</b>이다 — 화면이 두 번 눌러도 같은 결과를 본다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingCancelService {

    private final Clock clock;
    private final BookingRepositoryReader bookingReader;
    private final BookingStateService bookingState;
    private final PaymentClient paymentClient;
    private final SeatEventPublisher eventPublisher;

    public BookingCancelResponse cancel(String userId, Long bookingId) {
        Instant now = clock.instant();
        Booking booking = bookingReader.findById(bookingId)
            .orElseThrow(BookingErrorCode.BOOKING_NOT_FOUND::exception);

        if (!booking.isOwnedBy(userId)) {
            throw BookingErrorCode.NOT_OWNER.exception();
        }
        if (booking.isCanceled()) {
            return BookingCancelResponse.from(booking, paymentViewOf(booking));   // 멱등
        }
        if (!booking.isConfirmed()) {
            // 선점 중 취소는 BKG-03 해제다. 결제 실패·만료는 이미 끝난 예매다
            throw BookingErrorCode.BOOKING_NOT_CONFIRMED.exception(ErrorDetail.of("status",
                booking.getStatus().name(), BookingErrorCode.BOOKING_NOT_CONFIRMED.name()));
        }

        List<Long> seatIds = bookingReader.findOccupiedSeatIds(bookingId);
        if (!bookingState.cancelConfirmed(bookingId, now)) {
            // 그 사이 다른 경로가 취소했다. 현재 상태가 정답이다
            return current(bookingId);
        }

        // 커밋 후: 환불 → 발행. 환불이 실패해도 취소는 유효하다(PAY-03은 멱등이라 재시도 가능)
        PaymentView refunded = refund(booking);
        eventPublisher.publish(SeatEventType.RELEASED, booking.getScreeningId(), seatIds, now);
        log.info("예매 취소 bookingId={} paymentId={} seats={}", bookingId,
            booking.getCurrentPaymentId(), seatIds.size());

        Booking canceled = bookingReader.findById(bookingId)
            .orElseThrow(BookingErrorCode.BOOKING_NOT_FOUND::exception);
        // 환불 응답을 받았으면 그것을 담고, 못 받았으면(실패) 결제 상태를 다시 조회해 담는다
        return BookingCancelResponse.from(canceled,
            refunded != null ? refunded : paymentViewOf(canceled));
    }

    /** 환불 호출. 실패는 삼키고 로그만 남긴다 — 취소는 이미 커밋됐고 만료 처리가 다시 시도한다 */
    private PaymentView refund(Booking booking) {
        if (booking.getCurrentPaymentId() == null) {
            return null;   // 결제 없이 확정될 수는 없지만 방어
        }
        try {
            PaymentResult result = paymentClient.cancel(booking.getCurrentPaymentId(),
                PaymentCancelReason.BOOKING_CANCELED);
            return PaymentView.of(result.paymentId(), result.status(), result.cardLast4(),
                result.publicFailureCode(), result.approvedAt(), result.canceledAt());
        } catch (RuntimeException e) {
            log.error("환불 실패 bookingId={} paymentId={} — 취소는 유효하고 재시도가 필요하다",
                booking.getBookingId(), booking.getCurrentPaymentId(), e);
            return null;
        }
    }

    private PaymentView paymentViewOf(Booking booking) {
        return Optional.ofNullable(booking.getCurrentPaymentId())
            .flatMap(paymentClient::find)
            .map(result -> PaymentView.of(result.paymentId(), result.status(), result.cardLast4(),
                result.publicFailureCode(), result.approvedAt(), result.canceledAt()))
            .orElse(null);
    }

    private BookingCancelResponse current(Long bookingId) {
        Booking current = bookingReader.findById(bookingId)
            .orElseThrow(BookingErrorCode.BOOKING_NOT_FOUND::exception);
        return BookingCancelResponse.from(current, paymentViewOf(current));
    }
}

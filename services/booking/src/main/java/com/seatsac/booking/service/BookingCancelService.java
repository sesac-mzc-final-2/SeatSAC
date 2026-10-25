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
            // 멱등 응답. 다만 앞선 요청의 환불이 실패했을 수 있다 — 결제가 아직 APPROVED면
            // 여기서 다시 취소한다(PAY-03은 멱등). 이 재시도 경로가 없으면 「취소된 예매 +
            // 승인된 결제」가 영구히 남는다
            return BookingCancelResponse.from(booking, retryRefundIfNeeded(booking));
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

    /**
     * 이미 취소된 예매의 결제가 아직 {@code APPROVED}면 환불을 다시 시도한다.
     * 앞선 요청에서 환불 호출이 실패한 경우를 이 경로가 복구한다.
     */
    private PaymentView retryRefundIfNeeded(Booking booking) {
        PaymentResult current = findPayment(booking);
        if (current == null || !"APPROVED".equals(current.status())) {
            return viewOf(current);
        }
        log.warn("취소된 예매의 결제가 아직 승인 상태다 — 환불을 다시 시도한다 bookingId={} paymentId={}",
            booking.getBookingId(), current.paymentId());
        PaymentView retried = refund(booking);
        return retried != null ? retried : viewOf(current);
    }

    private PaymentView paymentViewOf(Booking booking) {
        return viewOf(findPayment(booking));
    }

    /**
     * 결제 조회. <b>서킷이 열리면</b> {@code CallNotPermittedException}이 {@code find()} 밖에서
     * 터진다 — 그것까지 삼켜야 「이미 취소된 예매를 다시 취소」가 200으로 끝난다.
     */
    private PaymentResult findPayment(Booking booking) {
        if (booking.getCurrentPaymentId() == null) {
            return null;
        }
        try {
            return paymentClient.find(booking.getCurrentPaymentId()).orElse(null);
        } catch (RuntimeException e) {
            log.warn("결제 조회 실패(서킷 열림 포함) bookingId={} — 결제 정보 없이 응답한다",
                booking.getBookingId());
            return null;
        }
    }

    private static PaymentView viewOf(PaymentResult result) {
        return Optional.ofNullable(result)
            .map(r -> PaymentView.of(r.paymentId(), r.status(), r.cardLast4(),
                r.publicFailureCode(), r.approvedAt(), r.canceledAt()))
            .orElse(null);
    }

    private BookingCancelResponse current(Long bookingId) {
        Booking current = bookingReader.findById(bookingId)
            .orElseThrow(BookingErrorCode.BOOKING_NOT_FOUND::exception);
        return BookingCancelResponse.from(current, paymentViewOf(current));
    }
}

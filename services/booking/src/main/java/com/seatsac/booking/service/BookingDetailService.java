package com.seatsac.booking.service;

import com.seatsac.booking.common.BookingErrorCode;
import com.seatsac.booking.component.PaymentClient;
import com.seatsac.booking.component.PaymentClient.PaymentResult;
import com.seatsac.booking.dto.response.BookingDetailResponse;
import com.seatsac.booking.dto.response.PaymentView;
import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.BookingStatus;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 예매 단건 조회 — 새로고침과 <b>결제 결과 재확인</b>에 쓴다 (명세 5장).
 *
 * <p>지연 카드(9999)는 Booking의 응답 대기(3초)보다 늦게 승인되므로 BKG-10이 503
 * {@code PAYMENT_RESULT_UNKNOWN}을 돌려준다. 화면은 이 API를 2초 간격으로 다시 불러 확정을
 * 확인하는데, 그때 <b>결제 결과를 여기서 반영</b>해야 확정이 끝난다.
 *
 * <p>{@code PENDING}이고 결제 요청이 있으면 Payment를 조회해 BKG-10 4단계와 같은 로직을 돌린다 —
 * 승인이면 확정(아웃박스 포함), 거절이면 실패 처리, {@code PROCESSING}이면 그대로 둔다.
 *
 * <p>{@code seatIds}는 점유 중(PENDING·CONFIRMED)일 때만 담는다 — 끝난 예매는
 * {@code booking_seat} 행이 지워져 빈 목록이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingDetailService {

    private final Clock clock;
    private final BookingRepositoryReader bookingReader;
    private final BookingPaymentResultApplier resultApplier;
    private final PaymentClient paymentClient;

    public BookingDetailResponse get(String userId, Long bookingId) {
        Booking booking = bookingReader.findById(bookingId)
            .orElseThrow(BookingErrorCode.BOOKING_NOT_FOUND::exception);
        if (!booking.isOwnedBy(userId)) {
            throw BookingErrorCode.NOT_OWNER.exception();
        }

        PaymentResult payment = reconcile(booking);
        Booking current = booking.getStatus() == BookingStatus.PENDING
            ? bookingReader.findById(bookingId).orElse(booking)   // 반영됐을 수 있다
            : booking;

        List<Long> seatIds = holdsSeats(current)
            ? bookingReader.findOccupiedSeatIds(bookingId) : List.of();
        return BookingDetailResponse.from(current, seatIds, viewOf(payment));
    }

    /**
     * 결제 결과를 반영한다. {@code PENDING}이고 결제 요청이 있을 때만 — 그 밖에는 조회만 한다.
     *
     * @return 결제 정보 (요청이 없었으면 {@code null})
     */
    private PaymentResult reconcile(Booking booking) {
        if (booking.getCurrentPaymentId() == null) {
            return null;
        }
        PaymentResult payment = findPayment(booking.getCurrentPaymentId());
        if (payment == null || booking.getStatus() != BookingStatus.PENDING) {
            return payment;
        }

        if (payment.isApproved()) {
            resultApplier.applyApproved(booking, payment);
        } else if (!payment.isProcessing()) {
            resultApplier.applyDeclined(booking);
        }
        // PROCESSING이면 그대로 둔다 — 아직 결과를 모른다
        return payment;
    }

    /**
     * 결제 조회. 서킷이 열리면 {@code CallNotPermittedException}이 {@code find()} 밖에서 터지므로
     * 여기서 삼킨다 — 결제를 못 봐도 <b>예매 조회 자체는 200</b>이어야 한다(화면이 재시도한다).
     */
    private PaymentResult findPayment(String paymentId) {
        try {
            return paymentClient.find(paymentId).orElse(null);
        } catch (RuntimeException e) {
            log.warn("결제 조회 실패(서킷 열림 포함) paymentId={} — 결제 정보 없이 응답한다", paymentId);
            return null;
        }
    }

    private static boolean holdsSeats(Booking booking) {
        return booking.getStatus() == BookingStatus.PENDING
            || booking.getStatus() == BookingStatus.CONFIRMED;
    }

    private static PaymentView viewOf(PaymentResult result) {
        return Optional.ofNullable(result)
            .map(r -> PaymentView.of(r.paymentId(), r.status(), r.cardLast4(),
                r.publicFailureCode(), r.approvedAt(), r.canceledAt()))
            .orElse(null);
    }
}

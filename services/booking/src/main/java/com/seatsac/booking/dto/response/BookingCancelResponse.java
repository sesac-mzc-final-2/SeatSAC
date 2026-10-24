package com.seatsac.booking.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.common.core.time.Times;
import java.time.OffsetDateTime;
import lombok.Builder;

/**
 * 해제·취소 200 응답 — 계약의 {@code CancelResult} 하나로 BKG-03과 BKG-07을 함께 쓴다.
 *
 * <p>{@code payment}는 결제를 취소한 BKG-07에만 담고, 결제가 없는 BKG-03 선점 해제에서는
 * {@code null}로 두어 응답에서 아예 빠진다({@link JsonInclude}). 계약이 {@code payment}를
 * 필수에서 빼고 「BKG-03 해제에는 없음」이라고 적은 것과 같은 모양이다.
 */
@Builder
public record BookingCancelResponse(
    Long bookingId,
    BookingStatus status,
    OffsetDateTime canceledAt,
    @JsonInclude(JsonInclude.Include.NON_NULL) PaymentView payment
) {

    /** BKG-03 선점 해제 */
    public static BookingCancelResponse ofHoldRelease(Booking booking) {
        return ofHoldRelease(booking.getBookingId(), Times.toApi(booking.getCanceledAt()));
    }

    /**
     * BKG-03 선점 해제 — 예매 행을 다시 읽지 못했을 때. 해제 자체는 조건부 UPDATE로 끝났으므로
     * 상태는 {@code CANCELED}로 확정이고, 취소 시각만 앱이 구한 값을 쓴다.
     */
    public static BookingCancelResponse ofHoldRelease(Long bookingId, OffsetDateTime canceledAt) {
        return BookingCancelResponse.builder()
            .bookingId(bookingId)
            .status(BookingStatus.CANCELED)
            .canceledAt(canceledAt)
            .build();
    }

    /** BKG-07 예매 취소 — 환불된 결제 정보를 함께 돌려준다 */
    public static BookingCancelResponse from(Booking booking, PaymentView payment) {
        return BookingCancelResponse.builder()
            .bookingId(booking.getBookingId())
            .status(booking.getStatus())
            .canceledAt(Times.toApi(booking.getCanceledAt()))
            .payment(payment)
            .build();
    }
}

package com.seatsac.booking.dto.response;

import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.common.core.time.Times;
import java.time.OffsetDateTime;

/** BKG-07 예매 취소 200 응답 — 명세 5장. 환불된 결제 정보를 함께 돌려준다. */
public record BookingCancelResponse(
    Long bookingId,
    BookingStatus status,
    OffsetDateTime canceledAt,
    PaymentView payment
) {
    public static BookingCancelResponse from(Booking booking, PaymentView payment) {
        return new BookingCancelResponse(booking.getBookingId(), booking.getStatus(),
            Times.toApi(booking.getCanceledAt()), payment);
    }
}

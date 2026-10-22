package com.seatsac.booking.dto.response;

import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.common.core.time.Times;
import java.time.OffsetDateTime;

/** BKG-03 선점 해제 200 응답 — 명세 5장. */
public record HoldReleaseResponse(Long bookingId, BookingStatus status, OffsetDateTime canceledAt) {

    public static HoldReleaseResponse from(Booking booking) {
        return new HoldReleaseResponse(booking.getBookingId(), booking.getStatus(),
            Times.toApi(booking.getCanceledAt()));
    }
}

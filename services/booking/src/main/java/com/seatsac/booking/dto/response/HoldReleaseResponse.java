package com.seatsac.booking.dto.response;

import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.common.core.time.Times;
import java.time.OffsetDateTime;
import lombok.Builder;
import lombok.Getter;

/** BKG-03 선점 해제 200 응답 — 명세 5장. */
@Getter
@Builder
public class HoldReleaseResponse {

    private final Long bookingId;
    private final BookingStatus status;
    private final OffsetDateTime canceledAt;

    public static HoldReleaseResponse from(Booking booking) {
        return HoldReleaseResponse.builder()
            .bookingId(booking.getBookingId())
            .status(booking.getStatus())
            .canceledAt(Times.toApi(booking.getCanceledAt()))
            .build();
    }
}

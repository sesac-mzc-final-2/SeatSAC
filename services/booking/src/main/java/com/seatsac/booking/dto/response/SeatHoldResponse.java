package com.seatsac.booking.dto.response;

import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.common.core.time.Times;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Builder;

/**
 * BKG-02 좌석 선점 201 응답 — 명세 5장.
 * holdId는 서버 내부값이라 어떤 응답에도 넣지 않는다.
 */
@Builder
public record SeatHoldResponse(
    Long bookingId,
    Long screeningId,
    BookingStatus status,
    List<Long> seatIds,
    List<String> seatLabels,
    Integer seatCount,
    Integer amount,
    OffsetDateTime expiresAt,
    OffsetDateTime createdAt
) {
    public static SeatHoldResponse from(Booking booking, List<Long> seatIds) {
        return SeatHoldResponse.builder()
            .bookingId(booking.getBookingId())
            .screeningId(booking.getScreeningId())
            .status(booking.getStatus())
            .seatIds(List.copyOf(seatIds))
            .seatLabels(booking.getSeatLabels())
            .seatCount(booking.getSeatCount())
            .amount(booking.getAmount())
            .expiresAt(Times.toApi(booking.getExpiresAt()))
            .createdAt(Times.toApi(booking.getCreatedAt()))
            .build();
    }
}

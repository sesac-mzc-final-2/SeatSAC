package com.seatsac.user.dto;

import com.seatsac.common.core.time.Times;
import com.seatsac.user.client.BookingClient.BookingItem;
import com.seatsac.user.client.CatalogClient.ScreeningSummary;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * USR-04 예매 내역 원소 — 계약 {@code MyBooking}. booking 응답에 catalog 회차 요약을 붙인다.
 * {@code screening}은 catalog가 답하지 않았거나 회차가 없으면 null(계약). 시각은 모두 +09:00으로 맞춘다.
 */
public record MyBooking(
    Long bookingId,
    String status,
    List<String> seatLabels,
    Integer seatCount,
    Long amount,
    OffsetDateTime createdAt,
    OffsetDateTime confirmedAt,
    OffsetDateTime canceledAt,
    Screening screening
) {
    public static MyBooking of(BookingItem booking, ScreeningSummary summary) {
        Integer seatCount = booking.seatCount() != null ? booking.seatCount() : booking.seatLabels().size();
        return new MyBooking(booking.bookingId(), booking.status(), booking.seatLabels(), seatCount, booking.amount(),
            api(booking.createdAt()), api(booking.confirmedAt()), api(booking.canceledAt()),
            summary == null ? null : Screening.of(summary));
    }

    private static OffsetDateTime api(OffsetDateTime at) {
        return at == null ? null : Times.toApi(at.toInstant());
    }

    public record Screening(
        Long screeningId,
        String movieTitle,
        String theaterName,
        String auditoriumName,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt
    ) {
        static Screening of(ScreeningSummary s) {
            return new Screening(s.screeningId(), s.movieTitle(), s.theaterName(), s.auditoriumName(),
                api(s.startsAt()), api(s.endsAt()));
        }
    }
}

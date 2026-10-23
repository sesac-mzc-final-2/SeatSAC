package com.seatsac.catalog.dto.internal;

import com.seatsac.catalog.entity.Screening;
import com.seatsac.common.core.time.Times;
import java.time.OffsetDateTime;

/** POST /internal/screenings/summaries 원소 (호출자 user — USR-04 예매 내역) */
public record ScreeningSummary(
    Long screeningId,
    String movieTitle,
    String theaterName,
    String auditoriumName,
    OffsetDateTime startsAt,
    OffsetDateTime endsAt
) {
    public static ScreeningSummary from(Screening s) {
        return new ScreeningSummary(s.getScreeningId(), s.getMovie().getTitle(),
            s.getAuditorium().getTheater().getName(), s.getAuditorium().getName(),
            Times.toApi(s.getStartsAt()), Times.toApi(s.getEndsAt()));
    }
}

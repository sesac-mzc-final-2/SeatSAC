package com.seatsac.catalog.dto.internal;

import com.seatsac.catalog.entity.Screening;
import com.seatsac.common.core.time.Times;
import java.time.OffsetDateTime;

/** GET /internal/screenings/{id} (호출자 booking — BKG-02·06·07). 이름 필드는 SQS 알림 payload용 */
public record ScreeningForBookingResponse(
    Long screeningId,
    Long auditoriumId,
    int layoutVersion,
    OffsetDateTime startsAt,
    OffsetDateTime endsAt,
    String movieTitle,
    String theaterName,
    String auditoriumName
) {
    public static ScreeningForBookingResponse from(Screening s) {
        return new ScreeningForBookingResponse(s.getScreeningId(), s.getAuditorium().getAuditoriumId(),
            s.getLayoutVersion(), Times.toApi(s.getStartsAt()), Times.toApi(s.getEndsAt()),
            s.getMovie().getTitle(), s.getAuditorium().getTheater().getName(), s.getAuditorium().getName());
    }
}

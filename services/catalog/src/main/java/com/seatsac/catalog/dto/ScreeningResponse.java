package com.seatsac.catalog.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** CAT-03 회차 응답 — 계약 Screening. 시각은 +09:00, {@code businessDate}는 KST 영업일 */
public record ScreeningResponse(
    Long screeningId,
    MovieRef movie,
    TheaterRef theater,
    AuditoriumRef auditorium,
    int layoutVersion,
    LocalDate businessDate,
    OffsetDateTime startsAt,
    OffsetDateTime endsAt,
    String filmFormat,
    int totalSeats,
    int remainingSeats,
    RemainingSeatsSource remainingSeatsSource
) {
    public record MovieRef(Long movieId, String title, ViewGrade viewGrade) {
    }

    public record TheaterRef(Long theaterId, String name) {
    }

    public record AuditoriumRef(Long auditoriumId, String name, ScreenType screenType) {
    }
}

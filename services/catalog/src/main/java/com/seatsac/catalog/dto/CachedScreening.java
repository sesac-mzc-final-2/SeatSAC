package com.seatsac.catalog.dto;

import com.seatsac.catalog.dto.ScreeningResponse.AuditoriumRef;
import com.seatsac.catalog.dto.ScreeningResponse.MovieRef;
import com.seatsac.catalog.dto.ScreeningResponse.TheaterRef;
import com.seatsac.catalog.entity.Auditorium;
import com.seatsac.catalog.entity.Screening;
import com.seatsac.common.core.time.Times;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 캐시에 넣는 회차 (잔여 좌석 없음 — 잔여 좌석은 캐시 금지, CAT-05). 영업일 전체 회차를 넣고
 * 시작한 회차 제외·잔여 좌석 채우기는 캐시 조회 뒤 요청마다 한다(계약 listScreenings description).
 * {@code startsAt}은 비교용으로 UTC Instant 그대로 둔다.
 */
public record CachedScreening(
    Long screeningId,
    MovieRef movie,
    TheaterRef theater,
    AuditoriumRef auditorium,
    int layoutVersion,
    LocalDate businessDate,
    Instant startsAt,
    Instant endsAt,
    String filmFormat,
    int totalSeats
) {
    public static CachedScreening from(Screening s) {
        Auditorium a = s.getAuditorium();
        return new CachedScreening(
            s.getScreeningId(),
            new MovieRef(s.getMovie().getMovieId(), s.getMovie().getTitle(), ViewGrade.fromCode(s.getMovie().getViewGradeCode())),
            new TheaterRef(a.getTheater().getTheaterId(), a.getTheater().getName()),
            new AuditoriumRef(a.getAuditoriumId(), a.getName(), ScreenType.fromCode(a.getScreenTypeCode())),
            s.getLayoutVersion(), s.getBusinessDate(), s.getStartsAt(), s.getEndsAt(), s.getFilmFormat(), a.getTotalSeats());
    }

    public boolean startsAfter(Instant now) {
        return startsAt.isAfter(now);
    }

    /** 잔여 좌석을 채워 응답으로. {@code unavailable}(선점+확정)이 null이면 TOTAL 대체 */
    public ScreeningResponse toResponse(Long unavailable) {
        boolean fromBooking = unavailable != null;
        int remaining = fromBooking ? (int) Math.max(0, totalSeats - unavailable) : totalSeats;
        return new ScreeningResponse(screeningId, movie, theater, auditorium, layoutVersion, businessDate,
            Times.toApi(startsAt), Times.toApi(endsAt), filmFormat, totalSeats, remaining,
            fromBooking ? RemainingSeatsSource.BOOKING : RemainingSeatsSource.TOTAL);
    }
}

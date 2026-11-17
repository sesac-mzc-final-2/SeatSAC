package com.seatsac.catalog.dto.internal;

import com.seatsac.catalog.dto.MovieSummary;
import com.seatsac.catalog.entity.Movie;
import com.seatsac.catalog.entity.Screening;
import com.seatsac.common.core.time.Times;
import java.time.OffsetDateTime;

/**
 * POST /internal/screenings/summaries 원소 (호출자 user — USR-04 예매 내역).
 * {@code posterUrl}은 CAT-01 {@link MovieSummary#posterUrl}과 같은 TMDB 주소(없으면 null) — 예매 내역 카드 포스터용
 */
public record ScreeningSummary(
    Long screeningId,
    Long movieId,
    String movieTitle,
    String posterUrl,
    String theaterName,
    String auditoriumName,
    OffsetDateTime startsAt,
    OffsetDateTime endsAt
) {
    public static ScreeningSummary from(Screening s) {
        Movie movie = s.getMovie();
        return new ScreeningSummary(s.getScreeningId(), movie.getMovieId(), movie.getTitle(),
            MovieSummary.posterUrl(movie.getPosterPath()),
            s.getAuditorium().getTheater().getName(), s.getAuditorium().getName(),
            Times.toApi(s.getStartsAt()), Times.toApi(s.getEndsAt()));
    }
}

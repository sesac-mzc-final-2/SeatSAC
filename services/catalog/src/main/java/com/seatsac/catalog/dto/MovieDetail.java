package com.seatsac.catalog.dto;

import com.seatsac.catalog.entity.Movie;
import java.time.LocalDate;
import java.util.List;

/** CAT-02 상세 = MovieSummary + TMDB 보충(없는 항목은 null·[]) */
public record MovieDetail(
    Long movieId,
    String title,
    String titleEn,
    ViewGrade viewGrade,
    Integer slotMinutes,
    String posterUrl,
    String synopsis,
    String director,
    List<String> cast,
    LocalDate releaseDate,
    List<String> genres,
    Integer runtimeMinutes
) {
    public MovieDetail {
        cast = cast == null ? List.of() : List.copyOf(cast);
        genres = genres == null ? List.of() : List.copyOf(genres);
    }

    public static MovieDetail from(Movie movie) {
        MovieSummary summary = MovieSummary.from(movie);
        return new MovieDetail(summary.movieId(), summary.title(), summary.titleEn(), summary.viewGrade(),
            summary.slotMinutes(), summary.posterUrl(), movie.getOverview(), movie.getDirector(),
            movie.getCastNames(), movie.getReleaseDate(), movie.getGenres(), movie.getRuntimeMinutes());
    }
}

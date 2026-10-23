package com.seatsac.catalog.dto;

import com.seatsac.catalog.entity.Movie;

/** CAT-01 목록 원소. posterUrl은 TMDB 경로(출처 표시), 없으면 null. 롯데 이미지는 쓰지 않는다 */
public record MovieSummary(
    Long movieId,
    String title,
    String titleEn,
    ViewGrade viewGrade,
    Integer slotMinutes,
    String posterUrl
) {
    /** TMDB 이미지 CDN(공개 값). 포스터 크기는 목록·상세 공통 w500 */
    static final String TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500";

    public static MovieSummary from(Movie movie) {
        return new MovieSummary(movie.getMovieId(), movie.getTitle(), movie.getTitleEn(),
            ViewGrade.fromCode(movie.getViewGradeCode()), movie.getSlotMinutes(), posterUrl(movie.getPosterPath()));
    }

    static String posterUrl(String posterPath) {
        return posterPath == null || posterPath.isBlank() ? null : TMDB_IMAGE_BASE + posterPath;
    }
}

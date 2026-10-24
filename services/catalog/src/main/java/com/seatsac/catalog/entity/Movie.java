package com.seatsac.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 영화 — {@code movie}. TMDB 보충 컬럼(2.2.4 D3)은 모두 NULL 허용이라 응답에서 null·[]로 나간다. */
@Entity
@Table(name = "movie")
public class Movie {

    @Id
    @Column(name = "movie_id")
    private Long movieId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "title_en", length = 200)
    private String titleEn;

    /** 롯데 ViewGradeCode: 0 전체, 12, 15, 19 (계약 ViewGrade) */
    @Column(name = "view_grade_code")
    private Integer viewGradeCode;

    /** 시작~종료 간격 최빈값. TMDB 러닝타임과 다른 값 */
    @Column(name = "slot_minutes")
    private Integer slotMinutes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "genres")
    private List<String> genres;

    @Column(name = "overview", columnDefinition = "TEXT")
    private String overview;

    @Column(name = "release_date")
    private LocalDate releaseDate;

    @Column(name = "runtime_minutes")
    private Integer runtimeMinutes;

    /** TMDB 상대 경로 (/abc.jpg). 롯데 이미지는 쓰지 않는다 */
    @Column(name = "poster_path", length = 255)
    private String posterPath;

    @Column(name = "director", length = 200)
    private String director;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cast_names")
    private List<String> castNames;

    protected Movie() {
    }

    public Long getMovieId() {
        return movieId;
    }

    public String getTitle() {
        return title;
    }

    public String getTitleEn() {
        return titleEn;
    }

    public Integer getViewGradeCode() {
        return viewGradeCode;
    }

    public Integer getSlotMinutes() {
        return slotMinutes;
    }

    public List<String> getGenres() {
        return genres == null ? List.of() : List.copyOf(genres);
    }

    public String getOverview() {
        return overview;
    }

    public LocalDate getReleaseDate() {
        return releaseDate;
    }

    public Integer getRuntimeMinutes() {
        return runtimeMinutes;
    }

    public String getPosterPath() {
        return posterPath;
    }

    public String getDirector() {
        return director;
    }

    public List<String> getCastNames() {
        return castNames == null ? List.of() : List.copyOf(castNames);
    }
}

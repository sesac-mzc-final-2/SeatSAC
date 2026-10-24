package com.seatsac.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 상영 회차 — {@code screening}. {@code startsAt}·{@code endsAt}는 UTC, {@code businessDate}만 KST 영업일.
 * 날짜 필터는 영업일로, 시작한 회차 제외는 {@code startsAt}로 한다(새벽 회차는 UTC 날짜가 하루 앞선다).
 */
@Entity
@Table(name = "screening")
public class Screening {

    @Id
    @Column(name = "screening_id")
    private Long screeningId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auditorium_id", nullable = false)
    private Auditorium auditorium;

    @Column(name = "layout_version", nullable = false)
    private Integer layoutVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "movie_id", nullable = false)
    private Movie movie;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "film_format", length = 40)
    private String filmFormat;

    protected Screening() {
    }

    public Long getScreeningId() {
        return screeningId;
    }

    public Auditorium getAuditorium() {
        return auditorium;
    }

    public int getLayoutVersion() {
        return layoutVersion;
    }

    public Movie getMovie() {
        return movie;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public String getFilmFormat() {
        return filmFormat;
    }
}

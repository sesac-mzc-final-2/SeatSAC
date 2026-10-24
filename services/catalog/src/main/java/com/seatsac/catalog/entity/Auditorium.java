package com.seatsac.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** 상영관 — {@code auditorium}. {@code layoutVersion}이 현재(최신) 배치 버전이다. */
@Entity
@Table(name = "auditorium")
public class Auditorium {

    @Id
    @Column(name = "auditorium_id")
    private Long auditoriumId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "theater_id", nullable = false)
    private Theater theater;

    @Column(name = "name", nullable = false, length = 30)
    private String name;

    /** 롯데 ScreenDivisionCode: 100 일반, 986 리클라이너 (계약 ScreenType) */
    @Column(name = "screen_type_code")
    private Integer screenTypeCode;

    @Column(name = "total_seats", nullable = false)
    private Integer totalSeats;

    @Column(name = "layout_version", nullable = false)
    private Integer layoutVersion;

    @Column(name = "layout_width", precision = 8, scale = 3)
    private BigDecimal layoutWidth;

    @Column(name = "layout_height", precision = 8, scale = 3)
    private BigDecimal layoutHeight;

    protected Auditorium() {
    }

    public Long getAuditoriumId() {
        return auditoriumId;
    }

    public Theater getTheater() {
        return theater;
    }

    public String getName() {
        return name;
    }

    public Integer getScreenTypeCode() {
        return screenTypeCode;
    }

    public int getTotalSeats() {
        return totalSeats;
    }

    public int getLayoutVersion() {
        return layoutVersion;
    }

    public BigDecimal getLayoutWidth() {
        return layoutWidth;
    }

    public BigDecimal getLayoutHeight() {
        return layoutHeight;
    }
}

package com.seatsac.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 배치 버전별 스크린·입구·통로 — {@code auditorium_layout} (PK: 상영관, 버전).
 * JSON 컬럼은 lotte-seed 모양 그대로 문자열로 읽고(snake_case 키) 응답 DTO로 바꿀 때 camelCase로 옮긴다.
 */
@Entity
@Table(name = "auditorium_layout")
public class AuditoriumLayout {

    @EmbeddedId
    private LayoutId id;

    @Column(name = "screen_x_from", nullable = false, precision = 8, scale = 3)
    private BigDecimal screenXFrom;

    @Column(name = "screen_x_to", nullable = false, precision = 8, scale = 3)
    private BigDecimal screenXTo;

    /** 원소 키: label, x, y, angle_code */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "entrances", nullable = false)
    private String entrances;

    /** 원소 키: x_from, x_to, rows */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "vertical_aisles", nullable = false)
    private String verticalAisles;

    /** 원소 키: after_row, before_row, y_from, y_to */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "horizontal_aisles", nullable = false)
    private String horizontalAisles;

    @Column(name = "source_provider", nullable = false, length = 10)
    private String sourceProvider;

    /** 수집 시각 (UTC) — 화면 하단 출처 표시 */
    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    protected AuditoriumLayout() {
    }

    public LayoutId getId() {
        return id;
    }

    public BigDecimal getScreenXFrom() {
        return screenXFrom;
    }

    public BigDecimal getScreenXTo() {
        return screenXTo;
    }

    public String getEntrances() {
        return entrances;
    }

    public String getVerticalAisles() {
        return verticalAisles;
    }

    public String getHorizontalAisles() {
        return horizontalAisles;
    }

    public String getSourceProvider() {
        return sourceProvider;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    /** (상영관, 배치 버전) 복합 키. 캐시 키 catalog:layout:{auditoriumId}:{version}와 같은 축이다 */
    @Embeddable
    public record LayoutId(
        @Column(name = "auditorium_id") Long auditoriumId,
        @Column(name = "layout_version") Integer layoutVersion
    ) implements Serializable {
    }
}

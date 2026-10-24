package com.seatsac.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 극장 — catalog V1 {@code theater}. 읽기 전용(시드는 lotte-seed가 적재). DDL과 {@code ddl-auto=validate}로 맞춘다.
 * 수집용 컬럼(source_*)은 응답에 쓰지 않아 매핑하지 않는다.
 */
@Entity
@Table(name = "theater")
public class Theater {

    @Id
    @Column(name = "theater_id")
    private Long theaterId;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "region", length = 20)
    private String region;

    /** DDL TINYINT. 배치도가 있는 4곳만 1 */
    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "has_layout", nullable = false)
    private Boolean hasLayout;

    protected Theater() {
    }

    public Long getTheaterId() {
        return theaterId;
    }

    public String getName() {
        return name;
    }

    public String getRegion() {
        return region;
    }

    public boolean hasLayout() {
        return Boolean.TRUE.equals(hasLayout);
    }
}

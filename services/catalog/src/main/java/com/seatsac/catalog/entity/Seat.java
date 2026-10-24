package com.seatsac.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 좌석 — {@code seat}. 좌표는 배치도 정규화 단위. 점유 상태는 여기 없다(BKG-01). */
@Entity
@Table(name = "seat")
public class Seat {

    @Id
    @Column(name = "seat_id")
    private Long seatId;

    @Column(name = "auditorium_id", nullable = false)
    private Long auditoriumId;

    @Column(name = "layout_version", nullable = false)
    private Integer layoutVersion;

    @Column(name = "row_label", nullable = false, length = 3)
    private String rowLabel;

    @Column(name = "col_no", nullable = false)
    private Integer colNo;

    @Column(name = "seat_label", nullable = false, length = 6)
    private String seatLabel;

    @Column(name = "x", nullable = false, precision = 8, scale = 3)
    private BigDecimal x;

    @Column(name = "y", nullable = false, precision = 8, scale = 3)
    private BigDecimal y;

    @Column(name = "w", nullable = false, precision = 6, scale = 3)
    private BigDecimal w;

    @Column(name = "h", nullable = false, precision = 6, scale = 3)
    private BigDecimal h;

    @Column(name = "zone_no")
    private Integer zoneNo;

    /** 롯데 PhysicalBlockCode: 1 일반, 190 리클라이너, 그 외 미확인 (계약 SeatType) */
    @Column(name = "source_seat_code")
    private Integer sourceSeatCode;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "active", nullable = false)
    private Boolean active;

    protected Seat() {
    }

    public Long getSeatId() {
        return seatId;
    }

    public String getRowLabel() {
        return rowLabel;
    }

    public int getColNo() {
        return colNo;
    }

    public String getSeatLabel() {
        return seatLabel;
    }

    public BigDecimal getX() {
        return x;
    }

    public BigDecimal getY() {
        return y;
    }

    public BigDecimal getW() {
        return w;
    }

    public BigDecimal getH() {
        return h;
    }

    public Integer getZoneNo() {
        return zoneNo;
    }

    public Integer getSourceSeatCode() {
        return sourceSeatCode;
    }

    public boolean isActive() {
        return Boolean.TRUE.equals(active);
    }
}

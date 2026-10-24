package com.seatsac.catalog.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** CAT-04 배치도 — 계약 AuditoriumLayout. 좌석 점유 상태는 없다(BKG-01). {@code source}는 화면 하단 출처 표시용 */
public record AuditoriumLayoutResponse(
    Long auditoriumId,
    String name,
    ScreenType screenType,
    int layoutVersion,
    BigDecimal width,
    BigDecimal height,
    Screen screen,
    List<Entrance> entrances,
    List<VerticalAisle> verticalAisles,
    List<HorizontalAisle> horizontalAisles,
    List<LayoutSeat> seats,
    Source source
) {
    public record Screen(BigDecimal xFrom, BigDecimal xTo) {
    }

    public record Entrance(BigDecimal x, BigDecimal y) {
    }

    public record VerticalAisle(BigDecimal xFrom, BigDecimal xTo) {
    }

    public record HorizontalAisle(BigDecimal yFrom, BigDecimal yTo) {
    }

    public record LayoutSeat(
        Long seatId, String seatLabel, String rowLabel, int colNo,
        BigDecimal x, BigDecimal y, BigDecimal w, BigDecimal h,
        Integer zoneNo, SeatType seatType, boolean active
    ) {
    }

    public record Source(String provider, OffsetDateTime fetchedAt) {
    }
}

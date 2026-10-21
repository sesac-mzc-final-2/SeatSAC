package com.seatsac.booking.enums;

/**
 * 좌석 이벤트 종류 — 명세 5장(BKG-05). 이 네 가지만 쓴다.
 * 본문 {@code {seq, type, screeningId, seatIds, occurredAt, traceId}}.
 */
public enum SeatEventType {
    HELD,
    RELEASED,
    CONFIRMED,
    EXPIRED
}

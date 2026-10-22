package com.seatsac.booking.enums;

/** 화면에 보여 주는 좌석 상태 — 명세 3장. 우선순위 RESERVED > HELD > AVAILABLE. */
public enum SeatStatus {
    AVAILABLE,
    /** Valkey 선점 키가 있거나 RDS 예매가 PENDING */
    HELD,
    /** RDS 예매가 CONFIRMED */
    RESERVED
}

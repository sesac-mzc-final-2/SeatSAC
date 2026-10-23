package com.seatsac.catalog.dto;

/** 잔여 좌석 출처 — BOOKING: 300ms 안에 받은 집계, TOTAL: 대체(remainingSeats = totalSeats) */
public enum RemainingSeatsSource {
    TOTAL, BOOKING
}

package com.seatsac.catalog.dto.internal;

import com.seatsac.catalog.entity.Theater;

/** GET /internal/theaters 원소 (호출자 user — USR-05 선호 극장 검증) */
public record TheaterRef(Long theaterId, String name) {

    public static TheaterRef from(Theater theater) {
        return new TheaterRef(theater.getTheaterId(), theater.getName());
    }
}

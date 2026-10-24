package com.seatsac.catalog.dto;

import com.seatsac.catalog.entity.Theater;

/** CAT-03 극장 목록 원소 */
public record TheaterResponse(Long theaterId, String name, String region, boolean hasLayout) {

    public static TheaterResponse from(Theater theater) {
        return new TheaterResponse(theater.getTheaterId(), theater.getName(), theater.getRegion(), theater.hasLayout());
    }
}

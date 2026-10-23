package com.seatsac.catalog.dto;

import java.util.List;

/** 페이징 없는 목록 {@code { items }} (2.2.4 D8, ADR-54) */
public record ItemsResponse<T>(List<T> items) {

    public ItemsResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}

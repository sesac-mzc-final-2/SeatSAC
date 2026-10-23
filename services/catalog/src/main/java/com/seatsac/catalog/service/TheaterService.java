package com.seatsac.catalog.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.seatsac.catalog.cache.CacheKeys;
import com.seatsac.catalog.cache.CatalogCache;
import com.seatsac.catalog.dto.ItemsResponse;
import com.seatsac.catalog.dto.TheaterResponse;
import com.seatsac.catalog.dto.internal.TheaterRef;
import com.seatsac.catalog.entity.Theater;
import com.seatsac.catalog.repository.TheaterRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/** CAT-03 극장 목록(캐시) · 내부 극장 ID 확인(캐시 없음 — 10개 이하 PK 조회) */
@Service
public class TheaterService {

    private static final TypeReference<ItemsResponse<TheaterResponse>> ITEMS_TYPE = new TypeReference<>() {
    };

    private final TheaterRepository theaterRepository;
    private final CatalogCache cache;

    public TheaterService(TheaterRepository theaterRepository, CatalogCache cache) {
        this.theaterRepository = theaterRepository;
        this.cache = cache;
    }

    public ItemsResponse<TheaterResponse> list(boolean hasLayout, String region) {
        String normalizedRegion = region == null || region.isBlank() ? null : region.strip();
        return cache.getOrLoad(CacheKeys.theaters(hasLayout, normalizedRegion), ITEMS_TYPE, () -> {
            List<Theater> theaters = normalizedRegion == null
                ? theaterRepository.findByHasLayoutOrderByTheaterId(hasLayout)
                : theaterRepository.findByHasLayoutAndRegionOrderByTheaterId(hasLayout, normalizedRegion);
            return new ItemsResponse<>(theaters.stream().map(TheaterResponse::from).toList());
        });
    }

    /** 없는 ID는 결과에서 빠진다 (내부 계약) */
    public ItemsResponse<TheaterRef> byIds(List<Long> theaterIds) {
        return new ItemsResponse<>(theaterRepository.findByTheaterIdInOrderByTheaterId(theaterIds).stream()
            .map(TheaterRef::from).toList());
    }
}

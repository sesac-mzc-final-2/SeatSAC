package com.seatsac.catalog.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.seatsac.catalog.cache.CacheKeys;
import com.seatsac.catalog.cache.CatalogCache;
import com.seatsac.catalog.dto.ItemsResponse;
import com.seatsac.catalog.dto.TheaterResponse;
import com.seatsac.catalog.dto.internal.TheaterRef;
import com.seatsac.catalog.repository.TheaterRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * CAT-03 극장 목록(캐시) · 내부 극장 ID 확인(캐시 없음 — 10개 이하 PK 조회).
 *
 * <p>캐시 키는 {@code catalog:theaters:{hasLayout}:all} 둘뿐이다. {@code region}은 사용자 입력이라 키에 넣으면
 * 값마다 키가 늘어나므로, 전체(132곳)를 캐시한 뒤 메모리에서 거른다.
 */
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
        ItemsResponse<TheaterResponse> all = cache.getOrLoad(CacheKeys.theaters(hasLayout), ITEMS_TYPE, () ->
            new ItemsResponse<>(theaterRepository.findByHasLayoutOrderByTheaterId(hasLayout).stream()
                .map(TheaterResponse::from).toList()));
        if (region == null || region.isBlank()) {
            return all;
        }
        String wanted = region.strip();
        return new ItemsResponse<>(all.items().stream().filter(t -> wanted.equals(t.region())).toList());
    }

    /** 없는 ID는 결과에서 빠진다 (내부 계약) */
    public ItemsResponse<TheaterRef> byIds(List<Long> theaterIds) {
        return new ItemsResponse<>(theaterRepository.findByTheaterIdInOrderByTheaterId(theaterIds).stream()
            .map(TheaterRef::from).toList());
    }
}

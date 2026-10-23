package com.seatsac.catalog.service;

import com.seatsac.catalog.cache.CacheKeys;
import com.seatsac.catalog.cache.CatalogCache;
import com.seatsac.catalog.dto.AuditoriumLayoutResponse;
import org.springframework.stereotype.Service;

/**
 * CAT-04 배치도. 캐시 키 {@code catalog:layout:{auditoriumId}:{version}}. booking도 이 응답을 (상영관, 버전)으로 파드 캐시한다.
 * {@code version} 생략 시 상영관의 현재 버전({@code auditorium.layout_version})을 쓴다.
 * DB 읽기는 {@link LayoutLoader}(읽기 전용 트랜잭션), 캐시는 트랜잭션 밖에서 다룬다.
 */
@Service
public class LayoutService {

    private final LayoutLoader loader;
    private final CatalogCache cache;

    public LayoutService(LayoutLoader loader, CatalogCache cache) {
        this.loader = loader;
        this.cache = cache;
    }

    public AuditoriumLayoutResponse get(Long auditoriumId, Integer version) {
        int resolved = version != null ? version : loader.currentVersion(auditoriumId);
        return cache.getOrLoad(CacheKeys.layout(auditoriumId, resolved), AuditoriumLayoutResponse.class,
            () -> loader.load(auditoriumId, resolved));
    }
}

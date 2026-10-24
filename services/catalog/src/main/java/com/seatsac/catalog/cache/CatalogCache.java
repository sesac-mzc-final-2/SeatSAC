package com.seatsac.catalog.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.catalog.config.CatalogProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * CAT-05 조회 캐시 — Valkey에 JSON 문자열로, TTL {@code app.cache.ttl}(기본 10분).
 *
 * <ul>
 *   <li>키 이름은 {@link CacheKeys}만. Spring Cache 추상화를 쓰지 않는 이유는 키 형식({@code catalog:...})을 규약 그대로 두기 위해서다</li>
 *   <li>Valkey 장애·직렬화 오류는 <b>조회 실패가 아니다</b>: WARN 한 줄 남기고 DB에서 읽어 응답한다(캐시는 가속 장치).
 *       읽기가 Valkey 오류로 실패한 요청은 쓰기도 건너뛴다 — 장애 구간에서 명령 타임아웃(300ms)을 두 번 물지 않게</li>
 *   <li>적중률 메트릭 {@code catalog_cache_requests_total{result=hit|miss}} (서비스 실행 계약 5장). Valkey 오류도 miss로 센다</li>
 *   <li>loader가 예외를 던지면(404 등) 캐시에 넣지 않는다</li>
 * </ul>
 */
@Component
public class CatalogCache {

    private static final Logger log = LoggerFactory.getLogger(CatalogCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration ttl;
    private final Counter hits;
    private final Counter misses;

    public CatalogCache(StringRedisTemplate redis, ObjectMapper mapper, CatalogProperties properties,
                        MeterRegistry meterRegistry) {
        this.redis = redis;
        this.mapper = mapper;
        this.ttl = properties.cache().ttl();
        this.hits = Counter.builder("catalog.cache.requests").tag("result", "hit").register(meterRegistry);
        this.misses = Counter.builder("catalog.cache.requests").tag("result", "miss").register(meterRegistry);
    }

    public <T> T getOrLoad(String key, Class<T> type, Supplier<T> loader) {
        return getOrLoad(key, mapper.constructType(type), loader);
    }

    public <T> T getOrLoad(String key, TypeReference<T> type, Supplier<T> loader) {
        return getOrLoad(key, mapper.constructType(type), loader);
    }

    private <T> T getOrLoad(String key, JavaType type, Supplier<T> loader) {
        Lookup<T> lookup = read(key, type);
        if (lookup.state() == Lookup.State.HIT) {
            hits.increment();
            return lookup.value();
        }
        misses.increment();
        T loaded = loader.get();
        if (loaded != null && lookup.state() == Lookup.State.MISS) {
            write(key, loaded);
        }
        return loaded;
    }

    /** 관리자 변경(CAT-06·LAY-07) 후 삭제용. 실패해도 TTL이 지나면 맞춰진다 */
    public void evict(String key) {
        try {
            redis.delete(key);
        } catch (RuntimeException e) {
            log.warn("cache evict failed key={} cause={}: {}", key, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * 미스와 Valkey 오류를 구분한다. 깨진 값(역직렬화 실패)은 미스로 보고 새 값으로 덮어쓴다.
     */
    private <T> Lookup<T> read(String key, JavaType type) {
        String json;
        try {
            json = redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            // 연결 실패·명령 타임아웃 — 캐시 없이 진행하고 이 요청에서는 쓰기도 하지 않는다
            log.warn("cache read failed key={} cause={}: {}", key, e.getClass().getSimpleName(), e.getMessage());
            return Lookup.error();
        }
        if (json == null) {
            return Lookup.miss();
        }
        try {
            return Lookup.hit(mapper.readValue(json, type));
        } catch (IOException e) {
            log.warn("cache value unreadable key={} cause={}: {}", key, e.getClass().getSimpleName(), e.getMessage());
            return Lookup.miss();
        }
    }

    private void write(String key, Object value) {
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            log.warn("cache write failed key={} cause={}: {}", key, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private record Lookup<T>(State state, T value) {

        enum State { HIT, MISS, ERROR }

        static <T> Lookup<T> hit(T value) {
            return new Lookup<>(State.HIT, value);
        }

        static <T> Lookup<T> miss() {
            return new Lookup<>(State.MISS, null);
        }

        static <T> Lookup<T> error() {
            return new Lookup<>(State.ERROR, null);
        }
    }
}

package com.seatsac.catalog.client;

import com.seatsac.common.core.error.BusinessException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@code GET http://booking:8080/internal/screenings/seat-counts?ids=} (booking 내부 계약, 최대 100개, 300ms 전체).
 *
 * <ul>
 *   <li>타임아웃·연결 오류·5xx는 공통 {@code InternalRestClientFactory}가 503 {@link BusinessException}으로 바꿔 던진다</li>
 *   <li>서킷 {@code booking}(실패율 50%·10초, ADR-23)이 열려 있으면 호출하지 않고 바로 대체한다</li>
 *   <li>대체 횟수 {@code catalog_seat_counts_fallback_total} (서비스 실행 계약 5장)</li>
 * </ul>
 * 재시도는 하지 않는다(공통 규칙). 요청이 100개를 넘으면 나눠 보내고, 한 묶음이라도 실패하면 전체를 대체한다
 * (일부만 BOOKING으로 섞어 보내면 화면이 출처를 회차마다 해석해야 한다).
 */
@Component
public class BookingSeatCountClient implements SeatCountClient {

    static final int MAX_IDS_PER_CALL = 100;

    private static final Logger log = LoggerFactory.getLogger(BookingSeatCountClient.class);

    private final RestClient bookingRestClient;
    private final CircuitBreaker circuitBreaker;
    private final Counter fallbacks;

    public BookingSeatCountClient(RestClient bookingRestClient, CircuitBreakerRegistry circuitBreakerRegistry,
                                  MeterRegistry meterRegistry) {
        this.bookingRestClient = bookingRestClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("booking");
        this.fallbacks = Counter.builder("catalog.seat.counts.fallback").register(meterRegistry);
    }

    @Override
    public Optional<Map<Long, Long>> unavailableCounts(Collection<Long> screeningIds) {
        if (screeningIds.isEmpty()) {
            return Optional.of(Map.of());
        }
        Map<Long, Long> counts = new HashMap<>();
        try {
            for (List<Long> batch : partition(screeningIds)) {
                SeatCounts response = circuitBreaker.executeSupplier(() -> fetch(batch));
                for (SeatCounts.Item item : response.items()) {
                    counts.put(item.screeningId(), item.heldCount() + item.reservedCount());
                }
            }
        } catch (CallNotPermittedException | BusinessException | RestClientException e) {
            fallbacks.increment();
            log.warn("seat-counts fallback to TOTAL ids={} cause={}: {}", screeningIds.size(),
                e.getClass().getSimpleName(), e.getMessage());
            return Optional.empty();
        }
        // 계약상 요청한 회차는 모두 돌아오지만, 빠진 회차는 예매 없음(0)으로 본다
        for (Long id : screeningIds) {
            counts.putIfAbsent(id, 0L);
        }
        return Optional.of(counts);
    }

    private SeatCounts fetch(List<Long> ids) {
        String joined = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        SeatCounts body = bookingRestClient.get()
            .uri("/internal/screenings/seat-counts?ids={ids}", joined)
            .retrieve()
            .body(SeatCounts.class);
        return body == null ? new SeatCounts(List.of()) : body;
    }

    private static List<List<Long>> partition(Collection<Long> ids) {
        List<Long> all = new ArrayList<>(ids);
        List<List<Long>> batches = new ArrayList<>();
        for (int from = 0; from < all.size(); from += MAX_IDS_PER_CALL) {
            batches.add(all.subList(from, Math.min(all.size(), from + MAX_IDS_PER_CALL)));
        }
        return batches;
    }

    /** booking 응답 {@code { items: [{screeningId, heldCount, reservedCount}] }}. 모르는 필드는 무시한다 */
    record SeatCounts(List<Item> items) {

        SeatCounts {
            items = items == null ? List.of() : List.copyOf(items);
        }

        record Item(Long screeningId, long heldCount, long reservedCount) {
        }
    }
}

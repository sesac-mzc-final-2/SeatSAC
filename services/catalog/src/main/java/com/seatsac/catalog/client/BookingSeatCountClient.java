package com.seatsac.catalog.client;

import com.seatsac.catalog.config.CatalogProperties;
import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.CommonResponseCode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@code GET http://booking:8080/internal/screenings/seat-counts?ids=} (booking 내부 계약, 최대 100개, 300ms 전체).
 *
 * <ul>
 *   <li>타임아웃·연결 오류·5xx는 공통 {@code InternalRestClientFactory}가 503 {@link BusinessException}으로 바꿔 던진다</li>
 *   <li>서킷 {@code booking}(실패율 50%·10초, ADR-23)이 열려 있으면 호출하지 않고 바로 대체한다</li>
 *   <li>계약상 요청한 회차는 모두 돌아온다. 빠진 회차가 있거나 본문이 없으면 응답을 믿지 않고 전체를 대체한다</li>
 *   <li>대체 횟수 {@code catalog_seat_counts_fallback_total} (서비스 실행 계약 5장)</li>
 * </ul>
 * 재시도는 하지 않는다(공통 규칙). 요청이 100개를 넘으면 나눠 보내고, 한 묶음이라도 실패하면 전체를 대체한다
 * (일부만 BOOKING으로 섞어 보내면 화면이 출처를 회차마다 해석해야 한다).
 *
 * <p><b>전체 예산</b>({@code app.services.booking-read-timeout}, 300ms)은 호출 전체의 벽시계 상한이다. 배치마다 남은 예산만큼만
 * 기다리고({@link #fetchWithin}), 예산이 끝나면 진행 중인 호출을 끊고(interrupt → 공통 팩토리가 503으로 바꿈) 바로 대체한다.
 * 연결·응답 단계별 타임아웃({@code BookingClientConfig}, 같은 값)은 그 아래의 안전망이다. 호출은 가상 스레드에서 돌리고
 * MDC(traceId·userId)를 복사해 {@code X-Trace-Id}·{@code X-User-Id} 전파가 끊기지 않게 한다.
 */
@Component
public class BookingSeatCountClient implements SeatCountClient {

    static final int MAX_IDS_PER_CALL = 100;

    private static final Logger log = LoggerFactory.getLogger(BookingSeatCountClient.class);

    private final RestClient bookingRestClient;
    private final CircuitBreaker circuitBreaker;
    private final Counter fallbacks;
    private final Duration budget;
    /** 예산이 끝나면 기다리던 호출을 끊기 위한 작업 스레드 — 호출마다 가상 스레드 하나 */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public BookingSeatCountClient(RestClient bookingRestClient, CircuitBreakerRegistry circuitBreakerRegistry,
                                  MeterRegistry meterRegistry, CatalogProperties properties) {
        this.bookingRestClient = bookingRestClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("booking");
        this.fallbacks = Counter.builder("catalog.seat.counts.fallback").register(meterRegistry);
        this.budget = properties.services().bookingReadTimeout();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @Override
    public Optional<Map<Long, Long>> unavailableCounts(Collection<Long> screeningIds) {
        if (screeningIds.isEmpty()) {
            return Optional.of(Map.of());
        }
        Map<Long, Long> counts = new HashMap<>();
        long deadline = System.nanoTime() + budget.toNanos();
        try {
            for (List<Long> batch : partition(screeningIds)) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    // 앞 배치들이 예산을 다 썼다 — 더 기다리지 않고 전체를 대체한다
                    return fallback(screeningIds.size(), "budget " + budget.toMillis() + "ms spent before batch of "
                        + batch.size());
                }
                SeatCounts response = circuitBreaker.executeSupplier(() -> fetchWithin(batch, remainingNanos));
                for (SeatCounts.Item item : response.items()) {
                    counts.put(item.screeningId(), item.heldCount() + item.reservedCount());
                }
            }
        } catch (CallNotPermittedException | BusinessException | RestClientException e) {
            return fallback(screeningIds.size(), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        long missing = screeningIds.stream().filter(id -> !counts.containsKey(id)).count();
        if (missing > 0) {
            // 계약 위반 응답(본문 없음·일부 회차 누락) — 0으로 추정하지 않는다
            return fallback(screeningIds.size(), "response missing " + missing + " screening(s)");
        }
        return Optional.of(counts);
    }

    private Optional<Map<Long, Long>> fallback(int requested, String cause) {
        fallbacks.increment();
        log.warn("seat-counts fallback to TOTAL ids={} cause={}", requested, cause);
        return Optional.empty();
    }

    /**
     * 남은 예산 안에서만 기다린다. 넘기면 호출 스레드를 interrupt해 끊고(JDK HttpClient는 응답 대기를 취소한다)
     * 503 {@link BusinessException}을 던져 서킷 실패로 집계되게 한다. 호출 중 생긴 예외는 그대로 다시 던진다.
     */
    private SeatCounts fetchWithin(List<Long> ids, long remainingNanos) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        Future<SeatCounts> future = executor.submit(() -> {
            if (mdc != null) {
                MDC.setContextMap(mdc);   // InternalRestClientFactory가 MDC에서 traceId·userId를 읽어 헤더로 보낸다
            }
            try {
                return fetch(ids);
            } finally {
                MDC.clear();
            }
        });
        try {
            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new BusinessException(CommonResponseCode.SERVICE_UNAVAILABLE,
                "seat-counts budget " + budget.toMillis() + "ms exceeded");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new BusinessException(CommonResponseCode.SERVICE_UNAVAILABLE, "seat-counts call interrupted");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;   // BusinessException(503)·RestClientException 그대로
            }
            throw new IllegalStateException(e.getCause());
        }
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

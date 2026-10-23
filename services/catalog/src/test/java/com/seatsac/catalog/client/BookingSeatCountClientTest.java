package com.seatsac.catalog.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatsac.catalog.config.CatalogProperties;
import com.seatsac.common.web.client.InternalRestClientFactory;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * booking 집계 호출의 결말 — 정상(합산·100개 분할), 계약 위반 응답(회차 누락·본문 없음 → TOTAL),
 * 300ms 초과·5xx(TOTAL + 메트릭), 예산 소진 뒤 다음 배치 생략, 서킷 열림(호출 없이 대체).
 * 컨테이너 없이 JDK HttpServer로 booking을 대신한다.
 */
class BookingSeatCountClientTest {

    private HttpServer server;
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private volatile long delayMillis = 0;
    private volatile int status = 200;
    /** 응답에서 빼 버릴 회차 (계약 위반 흉내) */
    private volatile Set<Long> dropIds = Set.of();
    private volatile boolean emptyBody = false;

    private SimpleMeterRegistry meters;
    private CircuitBreakerRegistry breakers;
    private BookingSeatCountClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/screenings/seat-counts", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            queries.add(query);
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            // 요청한 회차마다 항목을 돌려준다. 1번 회차만 선점 2 + 확정 3, 나머지는 0
            String items = Arrays.stream(query.substring("ids=".length()).split(","))
                .map(Long::valueOf)
                .filter(id -> !dropIds.contains(id))
                .map(id -> "{\"screeningId\":" + id + ",\"heldCount\":" + (id == 1 ? 2 : 0)
                    + ",\"reservedCount\":" + (id == 1 ? 3 : 0) + ",\"extra\":true}")
                .collect(Collectors.joining(","));
            byte[] body = (emptyBody ? "" : "{\"items\":[" + items + "]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        meters = new SimpleMeterRegistry();
        breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
            .slidingWindowSize(4).minimumNumberOfCalls(2).failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(10)).build());
        Duration budget = Duration.ofMillis(300);
        String bookingUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new BookingSeatCountClient(
            new InternalRestClientFactory().create(bookingUrl, budget, budget),
            breakers, meters,
            new CatalogProperties(new CatalogProperties.Cache(Duration.ofMinutes(10)),
                new CatalogProperties.Services(bookingUrl, budget)));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void sumsHeldAndReservedPerScreening() {
        Optional<Map<Long, Long>> counts = client.unavailableCounts(List.of(1L, 2L));

        assertThat(counts).contains(Map.of(1L, 5L, 2L, 0L));
        assertThat(queries).containsExactly("ids=1,2");
        assertThat(fallbacks()).isZero();
    }

    @Test
    void splitsRequestsIntoBatchesOfHundred() {
        List<Long> ids = LongStream.rangeClosed(1, 150).boxed().toList();

        Optional<Map<Long, Long>> counts = client.unavailableCounts(ids);

        assertThat(counts).isPresent();
        assertThat(counts.get()).hasSize(150);
        assertThat(queries).hasSize(2);
        assertThat(queries.get(0).split(",")).hasSize(100);
        assertThat(queries.get(1).split(",")).hasSize(50);
    }

    @Test
    void emptyInputDoesNotCallBooking() {
        assertThat(client.unavailableCounts(List.of())).contains(Map.of());
        assertThat(queries).isEmpty();
    }

    @Test
    void missingScreeningInResponseFallsBackInsteadOfAssumingZero() {
        dropIds = Set.of(2L);

        assertThat(client.unavailableCounts(List.of(1L, 2L, 3L))).isEmpty();
        assertThat(fallbacks()).isEqualTo(1);
    }

    @Test
    void emptyBodyFallsBack() {
        emptyBody = true;

        assertThat(client.unavailableCounts(List.of(1L))).isEmpty();
        assertThat(fallbacks()).isEqualTo(1);
    }

    @Test
    void slowResponseFallsBackAndCountsMetric() {
        delayMillis = 800;

        assertThat(client.unavailableCounts(List.of(1L))).isEmpty();
        assertThat(fallbacks()).isEqualTo(1);
    }

    @Test
    void stopsSendingBatchesOnceBudgetIsSpent() {
        delayMillis = 200;   // 호출마다 응답 타임아웃(300ms) 안이지만, 두 번이면 예산 300ms를 넘긴다
        List<Long> ids = LongStream.rangeClosed(1, 250).boxed().toList();   // 3배치

        assertThat(client.unavailableCounts(ids)).isEmpty();
        assertThat(queries).hasSizeLessThan(3);   // 예산을 다 쓴 뒤 세 번째 배치는 보내지 않는다
        assertThat(fallbacks()).isEqualTo(1);
    }

    @Test
    void serverErrorFallsBack() {
        status = 503;

        assertThat(client.unavailableCounts(List.of(1L))).isEmpty();
        assertThat(fallbacks()).isEqualTo(1);
    }

    @Test
    void openCircuitSkipsTheCall() {
        status = 503;
        client.unavailableCounts(List.of(1L));
        client.unavailableCounts(List.of(1L));   // 실패 2회 / 최소 2회 → 실패율 100% → OPEN
        assertThat(breakers.circuitBreaker("booking").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int callsSoFar = queries.size();

        status = 200;
        assertThat(client.unavailableCounts(List.of(1L))).isEmpty();
        assertThat(queries).hasSize(callsSoFar);   // 서킷이 열려 있어 booking을 부르지 않았다
        assertThat(fallbacks()).isEqualTo(3);
    }

    private double fallbacks() {
        return meters.get("catalog.seat.counts.fallback").counter().count();
    }
}

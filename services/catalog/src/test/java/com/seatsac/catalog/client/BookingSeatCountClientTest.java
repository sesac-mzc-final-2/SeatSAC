package com.seatsac.catalog.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatsac.catalog.config.CatalogProperties;
import com.seatsac.common.core.http.Headers;
import com.seatsac.common.core.trace.TraceKeys;
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
import org.slf4j.MDC;

/**
 * booking 집계 호출의 결말 — 정상(합산·100개 분할), 계약 위반 응답(회차 누락·본문 없음 → TOTAL),
 * 300ms 초과·5xx(TOTAL + 메트릭), 전체 예산 상한(배치가 여럿이어도·단계별 타임아웃이 길어도 300ms에 끊김),
 * traceId 전파, 서킷 열림(호출 없이 대체). 컨테이너 없이 JDK HttpServer로 booking을 대신한다.
 */
class BookingSeatCountClientTest {

    private static final Duration BUDGET = Duration.ofMillis(300);
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    private HttpServer server;
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private final List<String> traceHeaders = new CopyOnWriteArrayList<>();
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
            traceHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst(Headers.TRACE_ID)));
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
        client = newClient(BUDGET);   // 운영과 같이 연결·응답 단계 타임아웃 = 예산
    }

    @AfterEach
    void stop() {
        client.shutdown();
        server.stop(0);
        MDC.clear();
    }

    /** 연결·응답 단계 타임아웃을 {@code phaseTimeout}으로 둔 클라이언트. 예산은 항상 300ms */
    private BookingSeatCountClient newClient(Duration phaseTimeout) {
        String bookingUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new BookingSeatCountClient(
            new InternalRestClientFactory().create(bookingUrl, phaseTimeout, phaseTimeout),
            breakers, meters,
            new CatalogProperties(new CatalogProperties.Cache(Duration.ofMinutes(10)),
                new CatalogProperties.Services(bookingUrl, BUDGET)));
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

    /** 배치가 여럿이면 합산 시간이 예산을 넘기기 전에 끊는다 — 200ms + 200ms가 아니라 300ms 근처에서 대체 */
    @Test
    void cutsOffAtBudgetAcrossBatches() {
        delayMillis = 200;   // 호출 하나는 단계 타임아웃(300ms) 안이지만, 둘을 합치면 예산을 넘긴다
        List<Long> ids = LongStream.rangeClosed(1, 250).boxed().toList();   // 3배치

        long startedAt = System.nanoTime();
        Optional<Map<Long, Long>> counts = client.unavailableCounts(ids);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(counts).isEmpty();
        assertThat(elapsed).isLessThan(Duration.ofMillis(500));   // 두 번째 호출을 끝까지 기다렸다면 400ms+
        assertThat(queries).hasSizeLessThan(3);   // 세 번째 배치는 보내지 않는다
        assertThat(fallbacks()).isEqualTo(1);
    }

    /** 단계별 타임아웃이 예산보다 길어도(연결 1초·응답 1초) 전체는 예산에서 끊긴다 */
    @Test
    void cutsOffSingleSlowCallAtBudgetEvenWhenPhaseTimeoutsAreLonger() {
        BookingSeatCountClient lenient = newClient(Duration.ofSeconds(1));
        delayMillis = 800;
        try {
            long startedAt = System.nanoTime();
            Optional<Map<Long, Long>> counts = lenient.unavailableCounts(List.of(1L));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(counts).isEmpty();
            assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(250));
            assertThat(elapsed).isLessThan(Duration.ofMillis(600));   // 응답(800ms)을 기다리지 않았다
            assertThat(fallbacks()).isEqualTo(1);
        } finally {
            lenient.shutdown();
        }
    }

    /** 호출이 다른 스레드에서 돌아도 요청의 traceId가 X-Trace-Id로 나간다 */
    @Test
    void propagatesTraceIdThroughTheBudgetThread() {
        MDC.put(TraceKeys.MDC_TRACE_ID, TRACE);

        assertThat(client.unavailableCounts(List.of(1L))).isPresent();
        assertThat(traceHeaders).containsExactly(TRACE);
        assertThat(MDC.get(TraceKeys.MDC_TRACE_ID)).isEqualTo(TRACE);   // 호출 스레드의 MDC는 건드리지 않는다
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

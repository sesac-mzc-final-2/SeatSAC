package com.seatsac.catalog.client;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * booking 집계 호출의 세 가지 결말 — 정상(합산·0 채움·100개 분할), 300ms 초과(TOTAL 대체 + 메트릭), 서킷 열림(호출 없이 대체).
 * 컨테이너 없이 JDK HttpServer로 booking을 대신한다.
 */
class BookingSeatCountClientTest {

    private HttpServer server;
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private volatile long delayMillis = 0;
    private volatile int status = 200;

    private SimpleMeterRegistry meters;
    private CircuitBreakerRegistry breakers;
    private BookingSeatCountClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/screenings/seat-counts", exchange -> {
            queries.add(exchange.getRequestURI().getQuery());
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = "{\"items\":[{\"screeningId\":1,\"heldCount\":2,\"reservedCount\":3}]}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        meters = new SimpleMeterRegistry();
        breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
            .slidingWindowSize(4).minimumNumberOfCalls(2).failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(10)).build());
        client = new BookingSeatCountClient(
            new InternalRestClientFactory().create("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofMillis(300)),
            breakers, meters);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void sumsHeldAndReservedAndFillsMissingWithZero() {
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
    void slowResponseFallsBackAndCountsMetric() {
        delayMillis = 800;

        assertThat(client.unavailableCounts(List.of(1L))).isEmpty();
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

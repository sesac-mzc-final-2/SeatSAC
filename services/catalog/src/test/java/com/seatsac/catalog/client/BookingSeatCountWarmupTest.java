package com.seatsac.catalog.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * 기동 예열 — booking을 한 번 부르되 서킷 {@code booking}과 {@code catalog_seat_counts_fallback_total}에는
 * 성공·실패 어느 쪽도 남기지 않고, 실패(5xx·타임아웃·연결 거부)해도 예외를 밖으로 내지 않는다.
 * 컨테이너 없이 JDK HttpServer로 booking을 대신한다.
 */
class BookingSeatCountWarmupTest {

    private HttpServer server;
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private volatile long delayMillis = 0;
    private volatile int status = 200;

    private SimpleMeterRegistry meters;
    private CircuitBreakerRegistry breakers;
    private BookingSeatCountClient client;
    private BookingSeatCountWarmup warmup;

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
            byte[] body = "{\"items\":[{\"screeningId\":1,\"heldCount\":0,\"reservedCount\":0}]}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        meters = new SimpleMeterRegistry();
        // 실패 1번이면 열리는 설정 — 예열 실패가 서킷에 들어간다면 바로 OPEN으로 드러난다
        breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
            .slidingWindowSize(1).minimumNumberOfCalls(1).failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(10)).build());
        client = new BookingSeatCountClient(
            new InternalRestClientFactory().create("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofMillis(300)),
            breakers, meters);
        warmup = new BookingSeatCountWarmup(client);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void warmsUpWithOneSeatCountsCallOutsideCircuitAndFallbackCounter() {
        warmup.warmUp();

        assertThat(queries).containsExactly("ids=" + BookingSeatCountWarmup.WARMUP_SCREENING_ID);
        assertUntouched();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();   // 요청 밖 작업의 traceId·job을 스레드에 남기지 않는다
    }

    @Test
    void serverErrorIsSwallowedAndLeavesCircuitAndCounterUntouched() {
        status = 503;

        assertThatCode(warmup::warmUp).doesNotThrowAnyException();

        assertThat(queries).hasSize(1);
        assertUntouched();
        // 예열 뒤 실제 호출은 평소처럼 서킷 안에서 집계된다 (예열이 서킷을 우회했을 뿐 서킷이 꺼진 것이 아니다)
        assertThat(client.unavailableCounts(List.of(1L))).isEmpty();
        assertThat(fallbacks()).isEqualTo(1);
        assertThat(breakers.circuitBreaker("booking").getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void timeoutIsSwallowedAndLeavesCircuitAndCounterUntouched() {
        delayMillis = 800;

        assertThatCode(warmup::warmUp).doesNotThrowAnyException();

        assertUntouched();
    }

    @Test
    void connectionRefusedIsSwallowedAndLeavesCircuitAndCounterUntouched() {
        BookingSeatCountWarmup unreachable = new BookingSeatCountWarmup(new BookingSeatCountClient(
            new InternalRestClientFactory().create("http://127.0.0.1:1", Duration.ofMillis(300)), breakers, meters));

        assertThatCode(unreachable::warmUp).doesNotThrowAnyException();

        assertThat(queries).isEmpty();
        assertUntouched();
    }

    @Test
    void applicationReadyRunsWarmupOnAnotherThreadWithoutBlocking() {
        delayMillis = 200;   // 리스너가 호출을 기다렸다면 200ms 이상 걸린다

        long started = System.nanoTime();
        warmup.onApplicationReady();
        long listenerMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(listenerMillis).isLessThan(200);
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> assertThat(queries).hasSize(1));
        assertUntouched();
    }

    private void assertUntouched() {
        assertThat(fallbacks()).isZero();
        CircuitBreaker breaker = breakers.circuitBreaker("booking");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isZero();
    }

    private double fallbacks() {
        return meters.get("catalog.seat.counts.fallback").counter().count();
    }
}

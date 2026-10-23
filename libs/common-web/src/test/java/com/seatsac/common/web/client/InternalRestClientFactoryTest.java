package com.seatsac.common.web.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.CommonResponseCode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.web.client.RestClient;

/**
 * 내부 호출 클라이언트의 타임아웃 — 연결 타임아웃 오버로드가 요청 팩토리 설정에 그대로 들어가고 기본 1초보다 먼저
 * 503으로 끊는지, 기존 {@code create(baseUrl, readTimeout)}이 그대로 동작하는지. 컨테이너 없이 JDK HttpServer로 상대를 대신한다.
 */
class InternalRestClientFactoryTest {

    /** 라우팅되지 않는 주소 — SYN에 응답이 없어 연결 타임아웃까지 기다린다 (TEST-NET 아님, 사설 대역 끝) */
    private static final String UNREACHABLE = "http://10.255.255.1:1";

    private final InternalRestClientFactory factory = new InternalRestClientFactory();

    private HttpServer server;
    private volatile long delayMillis = 0;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/ping", exchange -> {
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = "pong".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** 네트워크와 무관한 확정 검사 — 오버로드에 준 두 값이 요청 팩토리 설정에 그대로 들어간다 */
    @Test
    void settingsCarryBothTimeoutsAsGiven() {
        ClientHttpRequestFactorySettings settings =
            InternalRestClientFactory.settings(Duration.ofMillis(200), Duration.ofMillis(300));

        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofMillis(200));
        assertThat(settings.readTimeout()).isEqualTo(Duration.ofMillis(300));
    }

    /**
     * 실제 연결 타임아웃이 200ms에 걸리는지. 연결을 즉시 거부하는 환경(경로 없음)에서는 타임아웃 자체가 일어나지
     * 않으므로 거짓 통과 대신 건너뛴다(assumption). 타임아웃이 걸린 환경에서는 원인 예외와 경과 시간으로 200ms 적용을 확인한다.
     */
    @Test
    void connectTimeoutOverloadFiresAt200msNotDefault1s() {
        Duration connect = Duration.ofMillis(200);
        RestClient client = factory.create(UNREACHABLE, connect, InternalRestClientFactory.READ);
        // 첫 요청의 클래스 로딩·HttpClient 초기화가 경과 시간에 섞이지 않게 로컬 서버로 한 번 워밍업한다
        factory.create(localUrl(), connect, InternalRestClientFactory.READ)
            .get().uri("/internal/ping").retrieve().body(String.class);

        long startedAt = System.nanoTime();
        BusinessException thrown = catchThrowableOfType(BusinessException.class,
            () -> client.get().uri("/internal/ping").retrieve().body(String.class));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(thrown).isNotNull();
        assertThat(thrown.code()).isEqualTo(CommonResponseCode.SERVICE_UNAVAILABLE);
        Throwable cause = thrown.getCause();
        Assumptions.assumeTrue(cause instanceof HttpTimeoutException || cause instanceof InterruptedIOException,
            () -> "연결이 즉시 거부돼 연결 타임아웃을 검증할 수 없는 환경: " + cause);

        // 타임아웃이 걸렸다면 200ms 근처(기본 1초보다 한참 앞)에서 끝나야 한다
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(150));
        assertThat(elapsed).isLessThan(Duration.ofMillis(800));
    }

    @Test
    void readTimeoutOverloadStillAppliesReadTimeoutAndKeepsCause() {
        delayMillis = 800;
        RestClient client = factory.create(localUrl(), Duration.ofMillis(300));

        assertThatThrownBy(() -> client.get().uri("/internal/ping").retrieve().body(String.class))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.code()).isEqualTo(CommonResponseCode.SERVICE_UNAVAILABLE);
                assertThat(e.getCause()).isInstanceOf(IOException.class);   // WARN 로그에 원인 한 줄이 남는다
            });
    }

    @Test
    void readTimeoutOverloadStillReturnsBodyWhenPeerAnswersInTime() {
        RestClient client = factory.create(localUrl(), Duration.ofMillis(300));

        assertThat(client.get().uri("/internal/ping").retrieve().body(String.class)).isEqualTo("pong");
    }

    private String localUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}

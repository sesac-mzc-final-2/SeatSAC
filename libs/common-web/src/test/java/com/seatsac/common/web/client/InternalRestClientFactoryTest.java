package com.seatsac.common.web.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.CommonResponseCode;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 내부 호출 클라이언트의 타임아웃 — 연결 타임아웃 오버로드가 기본 1초보다 먼저 503으로 끊는지,
 * 기존 {@code create(baseUrl, readTimeout)}이 그대로 동작하는지. 컨테이너 없이 JDK HttpServer로 상대를 대신한다.
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

    @Test
    void connectTimeoutOverloadFailsAs503BeforeDefaultConnectTimeout() {
        Duration connect = Duration.ofMillis(200);
        RestClient client = factory.create(UNREACHABLE, connect, InternalRestClientFactory.READ);

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> client.get().uri("/internal/ping").retrieve().body(String.class))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo(CommonResponseCode.SERVICE_UNAVAILABLE));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        // 연결이 바로 거부되는 환경(경로 없음)이면 0에 가깝고, SYN이 묻히는 환경이면 200ms 근처 — 어느 쪽이든 기본 1초보다 짧다
        assertThat(elapsed).isLessThan(InternalRestClientFactory.CONNECT);
    }

    @Test
    void readTimeoutOverloadStillAppliesReadTimeout() {
        delayMillis = 800;
        RestClient client = factory.create(localUrl(), Duration.ofMillis(300));

        assertThatThrownBy(() -> client.get().uri("/internal/ping").retrieve().body(String.class))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo(CommonResponseCode.SERVICE_UNAVAILABLE));
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

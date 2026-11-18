package com.seatsac.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 3.3.10 유휴 연결 503. catalog(Tomcat)는 keep-alive 연결을 60초 쉬면 닫는데, gateway 연결 풀이
 * 그 연결을 다시 쓰면 요청이 닫히는 연결에 실려 {@code PrematureCloseException} → 503으로 끝났다(T3, 11/17).
 *
 * <p>가짜 catalog는 <b>60초 넘게 쉰 연결로 온 요청을 응답 없이 끊는다</b> — Tomcat이 닫는 순간과
 * gateway가 다시 쓰는 순간이 겹친 경우를 매번 재현한다. gateway가 풀에서 30초
 * ({@code httpclient.pool.max-idle-time}) 넘은 연결을 버리면 새 연결로 가서 200이다.
 * application.yml의 실제 값을 그대로 쓰므로 61초를 기다린다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayIdleConnectionTest {

    private static final Duration UPSTREAM_KEEP_ALIVE = Duration.ofSeconds(60); // Tomcat connectionTimeout 기본
    private static final StaleClosingServer CATALOG = new StaleClosingServer(UPSTREAM_KEEP_ALIVE);

    @Autowired
    WebTestClient client;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.services.catalog-url", () -> "http://localhost:" + CATALOG.port());
        registry.add("app.cognito.issuer-uri", () -> "http://localhost:" + CATALOG.port() + "/pool"); // 공개 경로만 쓴다
        registry.add("app.cognito.client-id", () -> "test-client");
    }

    @AfterAll
    static void stop() {
        CATALOG.close();
    }

    @Test
    void 하위_서비스_keep_alive보다_오래_쉰_뒤에도_요청은_200() throws InterruptedException {
        client.get().uri("/api/movies").exchange().expectStatus().isOk();
        assertThat(CATALOG.connections()).isEqualTo(1);

        Thread.sleep(UPSTREAM_KEEP_ALIVE.plusSeconds(1).toMillis()); // 61초 — 천천히 누르는 사용자

        client.mutate().responseTimeout(Duration.ofSeconds(10)).build()
            .get().uri("/api/movies").exchange().expectStatus().isOk();
        assertThat(CATALOG.connections()).as("쉰 연결을 버리고 새 연결로 갔다").isEqualTo(2);
        CATALOG.assertNoError();
    }

    /** HTTP/1.1 keep-alive로 200을 주되, keepAlive보다 오래 쉰 연결로 온 다음 요청은 응답 없이 닫는다 */
    static final class StaleClosingServer implements AutoCloseable {

        private static final byte[] RESPONSE = ("HTTP/1.1 200 OK\r\n"
            + "Content-Type: application/json\r\nContent-Length: 2\r\n\r\n{}").getBytes(StandardCharsets.US_ASCII);

        private final ServerSocket server;
        private final long keepAliveNanos;
        private final AtomicInteger connections = new AtomicInteger();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        StaleClosingServer(Duration keepAlive) {
            this.keepAliveNanos = keepAlive.toNanos();
            try {
                this.server = new ServerSocket(0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            Thread.ofVirtual().start(this::acceptLoop);
        }

        int port() {
            return server.getLocalPort();
        }

        int connections() {
            return connections.get();
        }

        /** 서버가 열려 있는 동안 난 예상 밖 오류. 테스트 스레드가 {@link #assertNoError()}로 원인과 함께 실패시킨다 */
        void assertNoError() {
            Throwable error = failure.get();
            if (error != null) {
                throw new AssertionError("가짜 catalog 서버 오류", error);
            }
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    connections.incrementAndGet();
                    Thread.ofVirtual().start(() -> serve(socket));
                } catch (IOException e) {
                    if (!server.isClosed()) {
                        failure.compareAndSet(null, e); // 정상 종료(close)가 아닌데 accept 실패
                    }
                    return;
                }
            }
        }

        private void serve(Socket socket) {
            try (socket; InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream()) {
                long lastActive = System.nanoTime();
                while (readRequestHead(in)) {
                    if (System.nanoTime() - lastActive > keepAliveNanos) {
                        return; // 이미 닫았어야 할 연결 — 응답 없이 끊는다
                    }
                    out.write(RESPONSE);
                    out.flush();
                    lastActive = System.nanoTime();
                }
            } catch (SocketException e) {
                // gateway가 풀에서 버린 연결을 닫는 것(reset·closed)은 이 테스트가 기대하는 동작이다
            } catch (IOException e) {
                if (!server.isClosed()) {
                    failure.compareAndSet(null, e);
                }
            }
        }

        /** 요청 헤더 끝(빈 줄)까지 읽는다. 본문 없는 GET만 받는다. 연결이 닫히면 false */
        private static boolean readRequestHead(InputStream in) throws IOException {
            int matched = 0;
            int b;
            byte[] end = {'\r', '\n', '\r', '\n'};
            while ((b = in.read()) != -1) {
                matched = (b == end[matched]) ? matched + 1 : (b == '\r' ? 1 : 0);
                if (matched == end.length) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void close() {
            try {
                server.close();
            } catch (IOException ignored) {
                // 테스트 종료
            }
        }
    }
}

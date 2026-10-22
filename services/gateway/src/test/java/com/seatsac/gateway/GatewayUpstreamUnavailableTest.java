package com.seatsac.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * GW-01 「서비스 응답 없음은 503」. Catalog를 아무도 듣지 않는 포트로 두고 공개 조회를 보낸다.
 * {@link GatewayIntegrationTest}와 하위 서비스 주소가 달라 컨텍스트를 따로 띄운다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayUpstreamUnavailableTest {

    private static final int DEAD_PORT = deadPort();

    @Autowired
    WebTestClient client;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.services.catalog-url", () -> "http://localhost:" + DEAD_PORT); // 꺼진 서비스
        registry.add("app.services.booking-url", () -> "http://localhost:" + DEAD_PORT);
        registry.add("app.services.user-url", () -> "http://localhost:" + DEAD_PORT);
        registry.add("app.cognito.issuer-uri", () -> "http://localhost:" + DEAD_PORT + "/pool"); // 공개 경로만 쓰므로 호출되지 않는다
        registry.add("app.cognito.client-id", () -> "test-client");
    }

    @Test
    void 하위_서비스가_꺼져_있으면_503_공통_형식() {
        EntityExchangeResult<JsonNode> result = client.get().uri("/api/movies").exchange()
            .expectStatus().isEqualTo(503)
            .expectBody(JsonNode.class).returnResult();
        JsonNode body = result.getResponseBody();

        assertThat(body.get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(body.get("traceId").asText()).isEqualTo(result.getResponseHeaders().getFirst("X-Trace-Id"));
        JsonNode detail = body.get("details").get(0);
        assertThat(detail.get("field").asText()).isEqualTo("service");
        assertThat(detail.get("value").asText()).isEqualTo("catalog"); // 라우트 ID
        assertThat(detail.get("reason").asText()).isEqualTo("UPSTREAM_UNAVAILABLE");
    }

    /** 바인딩했다가 바로 닫아 아무도 듣지 않는 포트를 얻는다 */
    private static int deadPort() {
        DisposableServer server = HttpServer.create().port(0).bindNow();
        int port = server.port();
        server.disposeNow();
        return port;
    }
}

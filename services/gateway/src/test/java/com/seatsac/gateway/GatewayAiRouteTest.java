package com.seatsac.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.seatsac.gateway.route.AiRouteConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.support.RouteMetadataUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * 3.3.8 AI 라우트 — 플래그 {@code app.ai.enabled=true}일 때. 꺼진 기본 상태(404)는 {@link GatewayIntegrationTest}에서 본다.
 * 기본 응답 타임아웃을 1초로 줄여 두고 2초 늦게 답하는 가짜 서비스를 붙여, AI 라우트만 30초가 적용되는지(다른 라우트는 503) 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "app.ai.enabled=true",
        "spring.cloud.gateway.server.webflux.httpclient.response-timeout=1s", // 기본 15초를 테스트용으로 줄인다
    })
class GatewayAiRouteTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CLIENT_ID = "test-client";
    private static final String SUB = "8f4b2c1e-0d3a-4c6b-9e7f-1a2b3c4d5e6f";
    private static final Duration SLOW = Duration.ofSeconds(2); // 기본 1초보다 길고 AI 30초보다 짧다

    private static final RSAKey KEY = rsaKey("k1");

    private static final DisposableServer AI = echoServer("ai");
    private static final DisposableServer CATALOG = echoServer("catalog");
    private static final DisposableServer COGNITO = HttpServer.create().port(0)
        .route(r -> r.get("/pool/.well-known/jwks.json", (req, res) -> res
            .header("Content-Type", "application/json")
            .sendString(Mono.just(new JWKSet(KEY.toPublicJWK()).toString()))))
        .bindNow();

    @Autowired
    WebTestClient client;

    @Autowired
    RouteLocator routeLocator;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.services.ai-url", () -> "http://localhost:" + AI.port());
        registry.add("app.services.catalog-url", () -> "http://localhost:" + CATALOG.port());
        registry.add("app.services.booking-url", () -> "http://localhost:" + CATALOG.port()); // 이 테스트에서는 부르지 않는다
        registry.add("app.services.user-url", () -> "http://localhost:" + CATALOG.port());
        registry.add("app.cognito.issuer-uri", GatewayAiRouteTest::issuer);
        registry.add("app.cognito.client-id", () -> CLIENT_ID);
    }

    @AfterAll
    static void stopServers() {
        List.of(AI, CATALOG, COGNITO).forEach(DisposableServer::disposeNow);
    }

    @Test
    void 플래그가_켜지면_api를_떼고_ai로_가고_토큰과_사용자_헤더를_같이_넘긴다() {
        String token = token(KEY, Map.of());
        JsonNode body = client.post().uri("/api/ai/chat").headers(h -> h.setBearerAuth(token))
            .bodyValue("{\"message\":\"오늘 20시 이후 회차\"}").exchange()
            .expectStatus().isOk()
            .expectBody(JsonNode.class).returnResult().getResponseBody();

        assertThat(body.get("service").asText()).isEqualTo("ai");
        assertThat(body.get("path").asText()).isEqualTo("/ai/chat"); // /api 한 번만 뗀다 (default-filters 중복 적용 없음)
        JsonNode headers = body.get("headers");
        assertThat(headers.get("authorization").asText()).isEqualTo("Bearer " + token); // ai가 "내 예매" 공개 API 호출에 쓴다
        assertThat(headers.get("x-user-id").asText()).isEqualTo(SUB);
        assertThat(headers.get("x-user-role").asText()).isEqualTo("USER");

        assertThat(get("/api/ai/presets", token).expectStatus().isOk()
            .expectBody(JsonNode.class).returnResult().getResponseBody().get("path").asText()).isEqualTo("/ai/presets");
    }

    @Test
    void AI_라우트도_로그인이_필요하다() {
        EntityExchangeResult<JsonNode> result = get("/api/ai/presets", null).expectStatus().isUnauthorized()
            .expectBody(JsonNode.class).returnResult();
        JsonNode body = result.getResponseBody();
        assertThat(body.get("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(body.get("details").get(0).get("reason").asText()).isEqualTo("TOKEN_REQUIRED");
    }

    @Test
    void 응답_타임아웃_30초는_AI_라우트에만_적용된다() {
        String token = token(KEY, Map.of());

        // ai: 기본(1초)보다 늦게 답해도 라우트 타임아웃(30초) 안이라 200
        assertThat(get("/api/ai/slow", token).expectStatus().isOk()
            .expectBody(JsonNode.class).returnResult().getResponseBody().get("service").asText()).isEqualTo("ai");

        // catalog: 같은 지연에 기본 타임아웃이 걸려 503 UPSTREAM_TIMEOUT
        EntityExchangeResult<JsonNode> result = get("/api/movies/slow", null).expectStatus().isEqualTo(503)
            .expectBody(JsonNode.class).returnResult();
        JsonNode detail = result.getResponseBody().get("details").get(0);
        assertThat(result.getResponseBody().get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(detail.get("value").asText()).isEqualTo("catalog");
        assertThat(detail.get("reason").asText()).isEqualTo("UPSTREAM_TIMEOUT");
    }

    @Test
    void 라우트_id는_ai_이고_메타데이터_타임아웃은_30초() {
        // 3.1.21 gateway 블루/그린 분석이 routeId="ai"로 AI 요청을 5xx 판정에서 뺀다 — id가 바뀌면 분석이 깨진다
        Route ai = routeLocator.getRoutes().filter(r -> AiRouteConfig.ROUTE_ID.equals(r.getId())).blockFirst();
        assertThat(ai).isNotNull();
        assertThat(ai.getMetadata()).containsEntry(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR, 30_000);

        List<String> withOwnTimeout = routeLocator.getRoutes()
            .filter(r -> r.getMetadata().containsKey(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR))
            .map(Route::getId).collectList().block();
        assertThat(withOwnTimeout).containsExactlyInAnyOrder("ai", "booking-seat-stream"); // SSE(-1)와 AI(30초)만
    }

    // ---------- helpers ----------

    private WebTestClient.ResponseSpec get(String uri, String token) {
        return client.get().uri(uri)
            .headers(h -> {
                if (token != null) {
                    h.setBearerAuth(token);
                }
            })
            .exchange();
    }

    private static String issuer() {
        return "http://localhost:" + COGNITO.port() + "/pool";
    }

    private static String token(RSAKey key, Map<String, Object> overrides) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
            .issuer(issuer())
            .subject(SUB)
            .claim("token_use", "access")
            .claim("client_id", CLIENT_ID)
            .issueTime(new Date())
            .expirationTime(Date.from(Instant.now().plusSeconds(600)));
        overrides.forEach(claims::claim);
        try {
            SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey rsaKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 받은 경로·헤더(소문자)를 JSON으로 돌려주는 가짜 서비스. 경로가 /slow 로 끝나면 {@link #SLOW} 만큼 늦게 답한다 */
    private static DisposableServer echoServer(String service) {
        return HttpServer.create().port(0)
            .handle((req, res) -> {
                Map<String, String> headers = new LinkedHashMap<>();
                req.requestHeaders().forEach(e -> headers.put(e.getKey().toLowerCase(), e.getValue()));
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("service", service);
                body.put("path", req.uri());
                body.put("headers", headers);
                String json;
                try {
                    json = MAPPER.writeValueAsString(body);
                } catch (JsonProcessingException e) {
                    return Mono.error(e);
                }
                Mono<String> payload = req.uri().endsWith("/slow")
                    ? Mono.delay(SLOW).thenReturn(json)
                    : Mono.just(json);
                return res.header("Content-Type", "application/json").sendString(payload);
            })
            .bindNow();
    }
}

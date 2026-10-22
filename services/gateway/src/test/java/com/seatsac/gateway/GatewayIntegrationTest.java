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
import java.net.URI;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * GW-01~03 통합 테스트. 하위 서비스는 받은 경로·헤더를 그대로 돌려주는 가짜 서버로 대신하고,
 * Cognito는 테스트용 RSA 키의 JWKS를 내주는 서버로 대신한다(실제 서명 검증 경로를 그대로 탄다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CLIENT_ID = "test-client";
    private static final String SUB = "8f4b2c1e-0d3a-4c6b-9e7f-1a2b3c4d5e6f";
    private static final String TRACE_FORMAT = "^[0-9a-f]{32}$";

    private static final RSAKey KEY = rsaKey("k1");
    private static final RSAKey UNKNOWN_KEY = rsaKey("k2"); // JWKS에 없는 키

    private static final DisposableServer CATALOG = echoServer("catalog");
    private static final DisposableServer BOOKING = echoServer("booking");
    private static final DisposableServer USER = echoServer("user");
    private static final DisposableServer COGNITO = HttpServer.create().port(0)
        .route(r -> r.get("/pool/.well-known/jwks.json", (req, res) -> res
            .header("Content-Type", "application/json")
            .sendString(Mono.just(new JWKSet(KEY.toPublicJWK()).toString()))))
        .bindNow();

    @Autowired
    WebTestClient client;

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.services.catalog-url", () -> "http://localhost:" + CATALOG.port());
        registry.add("app.services.booking-url", () -> "http://localhost:" + BOOKING.port());
        registry.add("app.services.user-url", () -> "http://localhost:" + USER.port());
        registry.add("app.cognito.issuer-uri", GatewayIntegrationTest::issuer);
        registry.add("app.cognito.client-id", () -> CLIENT_ID);
    }

    @AfterAll
    static void stopServers() {
        List.of(CATALOG, BOOKING, USER, COGNITO).forEach(DisposableServer::disposeNow);
    }

    // ---------- GW-01 라우팅 ----------

    @Test
    void 공개_조회는_api를_떼고_Catalog로_간다() {
        JsonNode body = get("/api/movies?page=0", null).expectStatus().isOk()
            .expectBody(JsonNode.class).returnResult().getResponseBody();

        assertThat(body.get("service").asText()).isEqualTo("catalog");
        assertThat(body.get("path").asText()).isEqualTo("/movies?page=0");
    }

    @Test
    void 좌석_현황과_SSE는_Booking_회차_단건은_Catalog() {
        assertThat(routedService("/api/screenings/7/seats")).isEqualTo("booking");
        assertThat(routedService("/api/screenings/7/seats/stream")).isEqualTo("booking");
        assertThat(routedService("/api/screenings/7")).isEqualTo("catalog");
        assertThat(routedService("/api/screenings?date=2026-10-21&theaterId=1")).isEqualTo("catalog");
        assertThat(routedService("/api/auditoriums/3/layout")).isEqualTo("catalog");
    }

    @Test
    void 로그인_경로는_서비스별로_간다() {
        String token = token(KEY, Map.of());
        assertThat(get("/api/users/me", token).expectStatus().isOk()
            .expectBody(JsonNode.class).returnResult().getResponseBody().get("service").asText()).isEqualTo("user");
        assertThat(get("/api/bookings/1", token).expectStatus().isOk()
            .expectBody(JsonNode.class).returnResult().getResponseBody().get("service").asText()).isEqualTo("booking");
    }

    @Test
    void 내부_경로와_결제_인증_경로는_외부에_없다() {
        String token = token(KEY, Map.of());
        expectError(get("/api/internal/bookings?userId=" + SUB, token), 404, "NOT_FOUND");
        expectError(get("/api/screenings/internal/1", token), 404, "NOT_FOUND");
        expectError(get("/api/movies/actuator/health", token), 404, "NOT_FOUND");
        expectError(get("/api/payments/abc", token), 404, "NOT_FOUND");
        expectError(get("/api/auth/login", token), 404, "NOT_FOUND");
    }

    @Test
    void 인코딩으로_내부_경로에_닿으려_하면_400() {
        WebTestClient.ResponseSpec response = client.get()
            .uri(URI.create("http://localhost:" + port + "/api/movies/%2e%2e/internal/screenings/1"))
            .exchange();
        JsonNode body = expectError(response, 400, "VALIDATION_FAILED");
        assertThat(body.get("details").get(0).get("reason").asText()).isEqualTo("INVALID_PATH");
    }

    @Test
    void 헬스_체크는_인증_없이_열린다() {
        client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
    }

    // ---------- GW-02 JWT ----------

    @Test
    void 토큰_없으면_401_공통_형식() {
        EntityExchangeResult<JsonNode> result = get("/api/users/me", null).expectStatus().isUnauthorized()
            .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
            .expectBody(JsonNode.class).returnResult();
        JsonNode body = result.getResponseBody();

        assertThat(body.get("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(body.get("details").isArray()).isTrue();
        assertThat(body.get("details").get(0).get("reason").asText()).isEqualTo("TOKEN_REQUIRED");
        assertThat(body.get("traceId").asText())
            .matches(TRACE_FORMAT)
            .isEqualTo(result.getResponseHeaders().getFirst("X-Trace-Id"));
    }

    @Test
    void 유효한_토큰이면_sub와_역할을_헤더로_넘긴다() {
        JsonNode headers = forwardedHeaders("/api/users/me", token(KEY, Map.of()));
        assertThat(headers.get("x-user-id").asText()).isEqualTo(SUB);
        assertThat(headers.get("x-user-role").asText()).isEqualTo("USER");

        JsonNode adminHeaders = forwardedHeaders("/api/users/me", token(KEY, Map.of("cognito:groups", List.of("ADMIN"))));
        assertThat(adminHeaders.get("x-user-role").asText()).isEqualTo("ADMIN");
    }

    @Test
    void ID_토큰_다른_클라이언트_모르는_키_만료_토큰은_401() {
        Date past = Date.from(Instant.now().minusSeconds(600));
        List<String> tokens = List.of(
            token(KEY, Map.of("token_use", "id")),
            token(KEY, Map.of("client_id", "other-client")),
            token(UNKNOWN_KEY, Map.of()),
            token(KEY, Map.of("exp", past, "iat", Date.from(Instant.now().minusSeconds(1200)))),
            "not-a-jwt");
        for (String token : tokens) {
            JsonNode body = expectError(get("/api/users/me", token), 401, "UNAUTHORIZED");
            assertThat(body.get("details").get(0).get("reason").asText()).isEqualTo("INVALID_TOKEN");
        }
    }

    @Test
    void 공개_경로는_만료된_토큰이_붙어도_조회된다() {
        Date past = Date.from(Instant.now().minusSeconds(600));
        String expired = token(KEY, Map.of("exp", past, "iat", Date.from(Instant.now().minusSeconds(1200))));
        get("/api/movies", expired).expectStatus().isOk();
        get("/api/screenings/7/seats", expired).expectStatus().isOk();
    }

    @Test
    void 공개_경로_판정은_GET에만_적용된다() {
        JsonNode body = expectError(post("/api/movies", null), 401, "UNAUTHORIZED");
        assertThat(body.get("details").get(0).get("reason").asText()).isEqualTo("TOKEN_REQUIRED");
        expectError(post("/api/screenings/7/seats", null), 401, "UNAUTHORIZED");
    }

    @Test
    void 관리자_경로는_ADMIN_그룹만() {
        String userToken = token(KEY, Map.of());
        String adminToken = token(KEY, Map.of("cognito:groups", List.of("ADMIN")));

        JsonNode body = expectError(post("/api/admin/movies", userToken), 403, "FORBIDDEN");
        assertThat(body.get("details").get(0).get("reason").asText()).isEqualTo("ADMIN_REQUIRED");

        JsonNode routed = post("/api/admin/movies", adminToken).expectStatus().isOk()
            .expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(routed.get("service").asText()).isEqualTo("catalog");
        assertThat(routed.get("path").asText()).isEqualTo("/admin/movies");
    }

    // ---------- GW-03 추적 ID ----------

    @Test
    void 추적_ID는_매번_새로_만들고_외부_사용자_헤더는_지운다() {
        EntityExchangeResult<JsonNode> result = client.get().uri("/api/movies")
            .header("X-Trace-Id", "0123456789abcdef0123456789abcdef")
            .header("X-User-Id", "attacker")
            .header("X-User-Role", "ADMIN")
            .header("x-user-anything", "x")
            .exchange()
            .expectStatus().isOk()
            .expectBody(JsonNode.class).returnResult();
        JsonNode forwarded = result.getResponseBody().get("headers");
        String responseTraceId = result.getResponseHeaders().getFirst("X-Trace-Id");

        assertThat(responseTraceId).matches(TRACE_FORMAT).isNotEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(forwarded.get("x-trace-id").asText()).isEqualTo(responseTraceId);
        assertThat(forwarded.has("x-user-id")).isFalse();
        assertThat(forwarded.has("x-user-role")).isFalse();
        assertThat(forwarded.has("x-user-anything")).isFalse();
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

    private WebTestClient.ResponseSpec post(String uri, String token) {
        return client.post().uri(uri)
            .headers(h -> {
                if (token != null) {
                    h.setBearerAuth(token);
                }
            })
            .bodyValue("{}")
            .exchange();
    }

    private String routedService(String uri) {
        return get(uri, null).expectStatus().isOk()
            .expectBody(JsonNode.class).returnResult().getResponseBody().get("service").asText();
    }

    private JsonNode forwardedHeaders(String uri, String token) {
        return get(uri, token).expectStatus().isOk()
            .expectBody(JsonNode.class).returnResult().getResponseBody().get("headers");
    }

    private static JsonNode expectError(WebTestClient.ResponseSpec response, int status, String code) {
        EntityExchangeResult<JsonNode> result = response.expectStatus().isEqualTo(status)
            .expectBody(JsonNode.class).returnResult();
        JsonNode body = result.getResponseBody();
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("details").isArray()).isTrue();
        assertThat(body.get("traceId").asText()).isEqualTo(result.getResponseHeaders().getFirst("X-Trace-Id"));
        return body;
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

    /** 받은 경로(쿼리 포함)·헤더(소문자)를 JSON으로 돌려주는 가짜 하위 서비스 */
    private static DisposableServer echoServer(String service) {
        return HttpServer.create().port(0)
            .handle((req, res) -> {
                Map<String, String> headers = new LinkedHashMap<>();
                req.requestHeaders().forEach(e -> headers.put(e.getKey().toLowerCase(), e.getValue()));
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("service", service);
                body.put("path", req.uri());
                body.put("headers", headers);
                try {
                    return res.header("Content-Type", "application/json")
                        .sendString(Mono.just(MAPPER.writeValueAsString(body)));
                } catch (JsonProcessingException e) {
                    return Mono.error(e);
                }
            })
            .bindNow();
    }
}

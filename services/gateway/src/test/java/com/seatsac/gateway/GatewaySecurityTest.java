package com.seatsac.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * 6.3.3 gateway 보안 (ADR-124·121): 본문 64KB 413, SECURITY 로그, /api/coupons/** 라우트.
 * 하위 서비스·Cognito는 GatewayIntegrationTest와 같은 가짜 서버로 대신한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewaySecurityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CLIENT_ID = "test-client";
    private static final String SUB = "8f4b2c1e-0d3a-4c6b-9e7f-1a2b3c4d5e6f";
    private static final int LIMIT = 64 * 1024;

    private static final RSAKey KEY = rsaKey();
    private static final AtomicInteger BOOKING_HITS = new AtomicInteger();

    private static final DisposableServer CATALOG = echoServer("catalog", new AtomicInteger());
    private static final DisposableServer BOOKING = echoServer("booking", BOOKING_HITS);
    private static final DisposableServer USER = echoServer("user", new AtomicInteger());
    private static final DisposableServer PAYMENT = echoServer("payment", new AtomicInteger());
    private static final DisposableServer COUPON = echoServer("coupon", new AtomicInteger());
    private static final DisposableServer COGNITO = HttpServer.create().port(0)
        .route(r -> r.get("/pool/.well-known/jwks.json", (req, res) -> res
            .header("Content-Type", "application/json")
            .sendString(Mono.just(new JWKSet(KEY.toPublicJWK()).toString()))))
        .bindNow();

    @Autowired
    WebTestClient client;

    @LocalServerPort
    int port;

    private ListAppender<ILoggingEvent> securityLog;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.services.catalog-url", () -> "http://localhost:" + CATALOG.port());
        registry.add("app.services.booking-url", () -> "http://localhost:" + BOOKING.port());
        registry.add("app.services.user-url", () -> "http://localhost:" + USER.port());
        registry.add("app.services.payment-url", () -> "http://localhost:" + PAYMENT.port());
        registry.add("app.services.coupon-url", () -> "http://localhost:" + COUPON.port());
        registry.add("app.cognito.issuer-uri", GatewaySecurityTest::issuer);
        registry.add("app.cognito.client-id", () -> CLIENT_ID);
    }

    @AfterAll
    static void stopServers() {
        List.of(CATALOG, BOOKING, USER, PAYMENT, COUPON, COGNITO).forEach(DisposableServer::disposeNow);
    }

    @BeforeEach
    void attachLog() {
        securityLog = new ListAppender<>();
        securityLog.start();
        ((Logger) LoggerFactory.getLogger("security")).addAppender(securityLog);
    }

    @AfterEach
    void detachLog() {
        ((Logger) LoggerFactory.getLogger("security")).detachAppender(securityLog);
    }

    // ---------- /api/coupons/** ----------

    @Test
    void 쿠폰_경로는_로그인하면_api를_떼고_coupon으로_가고_멱등_키를_넘긴다() {
        JsonNode body = client.post().uri("/api/coupons/events/1/claim")
            .headers(h -> h.setBearerAuth(token()))
            .header("Idempotency-Key", "3b1f0c9e-1111-4222-8333-944455556666")
            .exchange()
            .expectStatus().isOk()
            .expectBody(JsonNode.class).returnResult().getResponseBody();

        assertThat(body.get("service").asText()).isEqualTo("coupon");
        assertThat(body.get("path").asText()).isEqualTo("/coupons/events/1/claim");
        assertThat(body.get("headers").get("idempotency-key").asText()).isEqualTo("3b1f0c9e-1111-4222-8333-944455556666");
        assertThat(body.get("headers").get("x-user-id").asText()).isEqualTo(SUB);

        assertThat(client.get().uri("/api/coupons/me").headers(h -> h.setBearerAuth(token())).exchange()
            .expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody()
            .get("service").asText()).isEqualTo("coupon");
    }

    @Test
    void 쿠폰_경로는_공개가_아니다_토큰_없으면_401() {
        expectError(client.get().uri("/api/coupons/events/current").exchange(), 401, "UNAUTHORIZED");
    }

    // ---------- 본문 64KB ----------

    @Test
    void 본문이_64KB면_통과하고_넘으면_하위_호출_없이_413() {
        client.post().uri("/api/seats/hold").headers(h -> h.setBearerAuth(token()))
            .contentType(MediaType.APPLICATION_JSON).bodyValue("x".repeat(LIMIT))
            .exchange().expectStatus().isOk();

        int before = BOOKING_HITS.get();
        JsonNode body = expectError(client.post().uri("/api/seats/hold").headers(h -> h.setBearerAuth(token()))
            .contentType(MediaType.APPLICATION_JSON).bodyValue("x".repeat(LIMIT + 1))
            .exchange(), 413, "PAYLOAD_TOO_LARGE");

        assertThat(BOOKING_HITS.get()).isEqualTo(before);
        JsonNode detail = body.get("details").get(0);
        assertThat(detail.get("field").asText()).isEqualTo("body");
        assertThat(detail.get("value").asText()).isEqualTo("65536");
        assertThat(detail.get("reason").asText()).isEqualTo("MAX_BODY_SIZE");
        assertThat(securityEvent("MAX_BODY_SIZE")).contains("status=413");
    }

    @Test
    void 큰_본문은_토큰이_없어도_인증보다_먼저_413() {
        expectError(client.post().uri("/api/seats/hold")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("x".repeat(LIMIT + 1))
            .exchange(), 413, "PAYLOAD_TOO_LARGE");
    }

    @Test
    void chunked_본문도_읽다가_넘으면_413() {
        DefaultDataBufferFactory factory = DefaultDataBufferFactory.sharedInstance;
        byte[] chunk = "x".repeat(16 * 1024).getBytes(StandardCharsets.UTF_8);
        Flux<DataBuffer> body = Flux.range(0, 5).map(i -> factory.wrap(chunk)); // 80KB, Content-Length 없음

        expectError(client.post().uri("/api/seats/hold").headers(h -> h.setBearerAuth(token()))
            .contentType(MediaType.APPLICATION_JSON).body(BodyInserters.fromDataBuffers(body))
            .exchange(), 413, "PAYLOAD_TOO_LARGE");
        assertThat(securityEvent("MAX_BODY_SIZE")).contains("status=413");
    }

    @Test
    void 한도_안의_chunked_본문은_그대로_전달된다() {
        DefaultDataBufferFactory factory = DefaultDataBufferFactory.sharedInstance;
        Flux<DataBuffer> body = Flux.just("{\"seat", "Ids\":[1]}")
            .map(s -> factory.wrap(s.getBytes(StandardCharsets.UTF_8)));

        JsonNode echoed = client.post().uri("/api/seats/hold").headers(h -> h.setBearerAuth(token()))
            .contentType(MediaType.APPLICATION_JSON).body(BodyInserters.fromDataBuffers(body))
            .exchange().expectStatus().isOk()
            .expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(echoed.get("body").asText()).isEqualTo("{\"seatIds\":[1]}");
        assertThat(echoed.get("headers").get("transfer-encoding").asText()).isEqualTo("chunked"); // 길이 없는 경로를 탔는지
    }

    // ---------- SECURITY 로그 ----------

    @Test
    void 토큰_없음과_잘못된_토큰은_SECURITY_로그에_원문_없이_남는다() {
        client.get().uri("/api/users/me").exchange().expectStatus().isUnauthorized();
        assertThat(securityEvent("TOKEN_REQUIRED"))
            .contains("errorCategory=SECURITY", "status=401", "path=/api/users/me");

        client.get().uri("/api/users/me").headers(h -> h.setBearerAuth("not-a-jwt-secret-value"))
            .exchange().expectStatus().isUnauthorized();
        String invalid = securityEvent("INVALID_TOKEN");
        assertThat(invalid).contains("status=401").doesNotContain("not-a-jwt-secret-value");
        ILoggingEvent event = securityLog.list.stream()
            .filter(e -> e.getFormattedMessage().contains("INVALID_TOKEN")).findFirst().orElseThrow();
        assertThat(event.getMDCPropertyMap().get("traceId")).matches("^[0-9a-f]{32}$");
    }

    @Test
    void 차단_경로_404와_경로_조작_400은_SECURITY_로그() {
        client.get().uri("/api/internal/bookings").exchange().expectStatus().isNotFound();
        assertThat(securityEvent("BLOCKED_PATH")).contains("status=404", "path=/api/internal/bookings");

        client.get().uri(URI.create("http://localhost:" + port + "/api/movies/%2e%2e/internal/x"))
            .exchange().expectStatus().isBadRequest();
        assertThat(securityEvent("INVALID_PATH")).contains("status=400");
    }

    @Test
    void 외부_X_User_헤더는_이름만_FORGED_HEADER로_남기고_요청은_진행한다() {
        client.get().uri("/api/movies")
            .header("X-User-Id", "attacker-value")
            .header("X-User-Grade", "VIP")
            .exchange().expectStatus().isOk();
        assertThat(securityEvent("FORGED_HEADER"))
            .contains("headers=x-user-grade,x-user-id")
            .doesNotContain("attacker-value");
    }

    @Test
    void 정상_요청과_라우트_없는_404는_SECURITY_로그가_없다() {
        client.get().uri("/api/movies").exchange().expectStatus().isOk();
        client.get().uri("/api/admin/movies").headers(h -> h.setBearerAuth(token())).exchange().expectStatus().isNotFound();
        assertThat(securityLog.list).isEmpty();
    }

    // ---------- helpers ----------

    private String securityEvent(String reason) {
        return securityLog.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .filter(m -> m.contains("reason=" + reason))
            .findFirst()
            .orElseThrow(() -> new AssertionError("SECURITY 로그 없음: " + reason + " in " + securityLog.list));
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

    private static String token() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .issuer(issuer())
            .subject(SUB)
            .claim("token_use", "access")
            .claim("client_id", CLIENT_ID)
            .issueTime(new Date())
            .expirationTime(Date.from(Instant.now().plusSeconds(600)))
            .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey rsaKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("k1").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 받은 경로·헤더(소문자)·본문을 JSON으로 돌려주는 가짜 하위 서비스. 호출 수를 센다 */
    private static DisposableServer echoServer(String service, AtomicInteger hits) {
        return HttpServer.create().port(0)
            .handle((req, res) -> req.receive().aggregate().asString().defaultIfEmpty("").flatMap(received -> {
                hits.incrementAndGet();
                Map<String, String> headers = new LinkedHashMap<>();
                req.requestHeaders().forEach(e -> headers.put(e.getKey().toLowerCase(), e.getValue()));
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("service", service);
                body.put("path", req.uri());
                body.put("headers", headers);
                body.put("body", received);
                try {
                    return res.header("Content-Type", "application/json")
                        .sendString(Mono.just(MAPPER.writeValueAsString(body))).then();
                } catch (JsonProcessingException e) {
                    return Mono.error(e);
                }
            }))
            .bindNow();
    }
}

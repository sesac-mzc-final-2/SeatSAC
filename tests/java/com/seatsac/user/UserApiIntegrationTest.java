package com.seatsac.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatsac.common.test.MySqlContainerSupport;
import com.seatsac.common.test.WithCurrentUser;
import com.seatsac.user.client.StubHttpServer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 3.3.3 확인 — 프로필(첫 호출 생성·Cognito 실패)·USR-04 예매 내역(booking+catalog 합치기)·USR-05 선호(전체 교체·극장 검증)를
 * MySQL 8.4 컨테이너와 가짜 booking·catalog·Cognito(JDK HttpServer 하나)로 끝까지 돈다.
 * DDL은 Flyway가 만들고 엔티티는 {@code ddl-auto=validate}로 검증되므로 컨텍스트가 뜨면 매핑과 DDL이 맞는다는 뜻이다.
 * Clock은 KST 2026-11-16 12:00으로 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({MySqlContainerSupport.class, UserApiIntegrationTest.FixedClock.class})
class UserApiIntegrationTest {

    /** KST 2026-11-16 12:00 */
    static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
    static final String TRACE_FORMAT = "^[0-9a-f]{32}$";
    static final String USER = WithCurrentUser.DEFAULT_USER;
    static final String OTHER = "22222222-2222-4222-8222-222222222222";
    static final String BEARER = "Bearer access-token";

    enum Mode { OK, DOWN }
    enum CognitoMode { OK, NO_NAME, DOWN, REJECT, OTHER_SUB }

    static volatile Mode bookingMode = Mode.OK;
    static volatile Mode catalogMode = Mode.OK;
    static volatile CognitoMode cognitoMode = CognitoMode.OK;
    static StubHttpServer stub;

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @BeforeAll
    static void startStubs() throws Exception {
        stub = new StubHttpServer("/", UserApiIntegrationTest::respond);
    }

    @AfterAll
    static void stopStubs() {
        stub.close();
    }

    @DynamicPropertySource
    static void stubUrls(DynamicPropertyRegistry registry) {
        registry.add("app.services.booking-url", () -> stub.baseUrl());
        registry.add("app.services.catalog-url", () -> stub.baseUrl());
        registry.add("app.cognito.endpoint", () -> stub.baseUrl());
    }

    /** 경로로 booking·catalog·Cognito를 구분한다. 상태 코드는 {@code stub.status}로 돌려주기 전에 정한다 */
    private static String respond(StubHttpServer.Received request) {
        stub.status = 200;
        if (request.path().equals("/internal/bookings")) {
            if (bookingMode == Mode.DOWN) {
                stub.status = 503;
                return "{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"\",\"traceId\":\"" + "0".repeat(32) + "\",\"details\":[]}";
            }
            String status = request.query().contains("status=") ? "filtered" : "all";
            String confirmed = "{\"bookingId\":101,\"status\":\"CONFIRMED\",\"screeningId\":5001,\"seatLabels\":[\"E7\",\"E8\"],"
                + "\"seatCount\":2,\"amount\":30000,\"createdAt\":\"2026-11-16T11:00:00+09:00\","
                + "\"confirmedAt\":\"2026-11-16T11:00:30+09:00\",\"canceledAt\":null,\"extra\":true}";
            String expired = "{\"bookingId\":100,\"status\":\"EXPIRED\",\"screeningId\":5002,\"seatLabels\":[\"A1\"],"
                + "\"seatCount\":1,\"amount\":15000,\"createdAt\":\"2026-11-15T20:00:00+09:00\","
                + "\"confirmedAt\":null,\"canceledAt\":null}";
            return "all".equals(status)
                ? "{\"content\":[" + confirmed + "," + expired + "],\"page\":0,\"size\":20,\"totalElements\":2,\"totalPages\":1}"
                : "{\"content\":[" + confirmed + "],\"page\":0,\"size\":20,\"totalElements\":1,\"totalPages\":1}";
        }
        if (request.path().startsWith("/internal/")) {
            if (catalogMode == Mode.DOWN) {
                stub.status = 503;
                return "{}";
            }
            if (request.path().equals("/internal/screenings/summaries")) {
                // 회차 5001만 있다. 5002는 계약대로 결과에서 빠진다
                return request.body().contains("5001")
                    ? "{\"items\":[{\"screeningId\":5001,\"movieTitle\":\"첫 영화\",\"theaterName\":\"건대입구\","
                        + "\"auditoriumName\":\"6관\",\"startsAt\":\"2026-11-16T14:30:00+09:00\",\"endsAt\":\"2026-11-16T16:25:00+09:00\"}]}"
                    : "{\"items\":[]}";
            }
            // GET /internal/theaters?ids= — 1004·1016만 있다
            String items = Arrays.stream(request.query().substring("ids=".length()).split(","))
                .map(Long::valueOf).filter(id -> id == 1004 || id == 1016)
                .map(id -> "{\"theaterId\":" + id + ",\"name\":\"극장" + id + "\"}")
                .collect(Collectors.joining(","));
            return "{\"items\":[" + items + "]}";
        }
        // Cognito GetUser (POST /)
        switch (cognitoMode) {
            case DOWN -> {
                stub.status = 500;
                return "{\"__type\":\"InternalErrorException\"}";
            }
            case REJECT -> {
                stub.status = 400;
                return "{\"__type\":\"NotAuthorizedException\",\"message\":\"Invalid Access Token\"}";
            }
            default -> {
                String sub = cognitoMode == CognitoMode.OTHER_SUB ? OTHER : USER;
                String name = cognitoMode == CognitoMode.NO_NAME ? "" : "{\"Name\":\"name\",\"Value\":\"홍길동\"},";
                return "{\"Username\":\"hong\",\"UserAttributes\":[{\"Name\":\"sub\",\"Value\":\"" + sub + "\"}," + name
                    + "{\"Name\":\"email_verified\",\"Value\":\"true\"},{\"Name\":\"email\",\"Value\":\"hong@example.com\"}]}";
            }
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        bookingMode = Mode.OK;
        catalogMode = Mode.OK;
        cognitoMode = CognitoMode.OK;
        stub.received.clear();
        jdbc.update("DELETE FROM user_preferred_theater");
        jdbc.update("DELETE FROM user_preference");
        jdbc.update("DELETE FROM `user`");
    }

    private long cognitoCalls() {
        return stub.received.stream().filter(r -> r.path().equals("/")).count();
    }

    // --- 프로필 ---

    @Test
    void firstProfileCallCreatesRowFromCognitoAndLaterCallsSkipCognito() throws Exception {
        mvc.perform(get("/users/me").with(WithCurrentUser.user()).header(HttpHeaders.AUTHORIZATION, BEARER))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Trace-Id", matchesPattern(TRACE_FORMAT)))
            .andExpect(jsonPath("$.userId").value(USER))
            .andExpect(jsonPath("$.email").value("hong@example.com"))
            .andExpect(jsonPath("$.name").value("홍길동"))
            .andExpect(jsonPath("$.role").value("USER"))
            .andExpect(jsonPath("$.createdAt").value("2026-11-16T12:00:00+09:00"));

        assertThat(cognitoCalls()).isEqualTo(1);
        StubHttpServer.Received getUser = stub.received.get(0);
        assertThat(getUser.header("X-amz-target")).isEqualTo("AWSCognitoIdentityProviderService.GetUser");
        assertThat(getUser.body()).isEqualTo("{\"AccessToken\":\"access-token\"}");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE user_id = ?", Long.class, USER)).isEqualTo(1);

        // 두 번째부터는 토큰이 없어도 되고 Cognito를 부르지 않는다. 역할은 헤더 그대로
        mvc.perform(get("/users/me").with(WithCurrentUser.admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("홍길동"))
            .andExpect(jsonPath("$.role").value("ADMIN"));
        assertThat(cognitoCalls()).isEqualTo(1);
    }

    @Test
    void profileWithoutCognitoNameHasNullName() throws Exception {
        cognitoMode = CognitoMode.NO_NAME;

        mvc.perform(get("/users/me").with(WithCurrentUser.user()).header(HttpHeaders.AUTHORIZATION, BEARER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").isEmpty());
    }

    @Test
    void firstCallWithoutTokenIs401AndCreatesNothing() throws Exception {
        mvc.perform(get("/users/me").with(WithCurrentUser.user()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.details[0].field").value("Authorization"))
            .andExpect(jsonPath("$.details[0].reason").value("TOKEN_REQUIRED"));

        assertThat(cognitoCalls()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user`", Long.class)).isZero();
    }

    @Test
    void cognitoFailureIs503IdentityUnavailableAndCreatesNothing() throws Exception {
        cognitoMode = CognitoMode.DOWN;

        mvc.perform(get("/users/me").with(WithCurrentUser.user()).header(HttpHeaders.AUTHORIZATION, BEARER))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("IDENTITY_UNAVAILABLE"))
            .andExpect(jsonPath("$.traceId").value(matchesPattern(TRACE_FORMAT)))
            .andExpect(jsonPath("$.details").isArray())
            .andExpect(jsonPath("$.details[0].reason").value("IDENTITY_UNAVAILABLE"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user`", Long.class)).isZero();

        // 복구되면 같은 요청이 그대로 성공한다
        cognitoMode = CognitoMode.OK;
        mvc.perform(get("/users/me").with(WithCurrentUser.user()).header(HttpHeaders.AUTHORIZATION, BEARER))
            .andExpect(status().isOk());
    }

    @Test
    void rejectedTokenIs403AndForeignTokenIs401() throws Exception {
        // 403: 401이면 화면이 토큰 갱신 → signinRedirect를 자동 반복한다(계약 user.openapi.yaml getMyProfile)
        cognitoMode = CognitoMode.REJECT;
        mvc.perform(get("/users/me").with(WithCurrentUser.user()).header(HttpHeaders.AUTHORIZATION, BEARER))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            .andExpect(jsonPath("$.details[0].reason").value("TOKEN_REJECTED"));

        cognitoMode = CognitoMode.OTHER_SUB;
        mvc.perform(get("/users/me").with(WithCurrentUser.user()).header(HttpHeaders.AUTHORIZATION, BEARER))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.details[0].reason").value("IDENTITY_MISMATCH"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user`", Long.class)).isZero();
    }

    @Test
    void updateMyProfileRenamesAndCreatesProfileFirstIfMissing() throws Exception {
        mvc.perform(patch("/users/me").with(WithCurrentUser.user()).header(HttpHeaders.AUTHORIZATION, BEARER)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" 새이름 \"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("새이름"))
            .andExpect(jsonPath("$.email").value("hong@example.com"));
        assertThat(cognitoCalls()).isEqualTo(1);

        mvc.perform(get("/users/me").with(WithCurrentUser.user()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("새이름"));
        assertThat(jdbc.queryForObject("SELECT name FROM `user` WHERE user_id = ?", String.class, USER)).isEqualTo("새이름");
    }

    // --- USR-04 ---

    @Test
    void listMyBookingsMergesBookingWithCatalogSummaries() throws Exception {
        mvc.perform(get("/users/me/bookings").with(WithCurrentUser.user()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content", hasSize(2)))
            .andExpect(jsonPath("$.content[0].bookingId").value(101))
            .andExpect(jsonPath("$.content[0].status").value("CONFIRMED"))
            .andExpect(jsonPath("$.content[0].seatLabels", hasSize(2)))
            .andExpect(jsonPath("$.content[0].seatCount").value(2))
            .andExpect(jsonPath("$.content[0].amount").value(30000))
            .andExpect(jsonPath("$.content[0].createdAt").value("2026-11-16T11:00:00+09:00"))
            .andExpect(jsonPath("$.content[0].confirmedAt").value("2026-11-16T11:00:30+09:00"))
            .andExpect(jsonPath("$.content[0].canceledAt").isEmpty())
            .andExpect(jsonPath("$.content[0].screening.screeningId").value(5001))
            .andExpect(jsonPath("$.content[0].screening.movieTitle").value("첫 영화"))
            .andExpect(jsonPath("$.content[0].screening.theaterName").value("건대입구"))
            .andExpect(jsonPath("$.content[0].screening.startsAt").value("2026-11-16T14:30:00+09:00"))
            .andExpect(jsonPath("$.content[1].bookingId").value(100))
            .andExpect(jsonPath("$.content[1].screening").isEmpty())    // catalog에 없는 회차 → null
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.totalPages").value(1));

        // booking → catalog 순서, 쿼리에 sub·페이징, 내부 헤더 전파
        assertThat(stub.received).hasSize(2);
        StubHttpServer.Received bookingCall = stub.received.get(0);
        assertThat(bookingCall.path()).isEqualTo("/internal/bookings");
        assertThat(bookingCall.query()).isEqualTo("userId=" + USER + "&page=0&size=20");
        assertThat(bookingCall.header("X-user-id")).isEqualTo(USER);
        assertThat(bookingCall.header("X-trace-id")).isEqualTo(WithCurrentUser.DEFAULT_TRACE);
        assertThat(stub.received.get(1).path()).isEqualTo("/internal/screenings/summaries");
        assertThat(stub.received.get(1).body()).isEqualTo("{\"screeningIds\":[5001,5002]}");
    }

    @Test
    void listMyBookingsPassesStatusAndPagingThrough() throws Exception {
        mvc.perform(get("/users/me/bookings").with(WithCurrentUser.user()).param("status", "CONFIRMED").param("size", "5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content", hasSize(1)));

        assertThat(stub.received.get(0).query()).isEqualTo("userId=" + USER + "&status=CONFIRMED&page=0&size=5");
    }

    @Test
    void catalogFailureLeavesScreeningNull() throws Exception {
        catalogMode = Mode.DOWN;

        mvc.perform(get("/users/me/bookings").with(WithCurrentUser.user()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content", hasSize(2)))
            .andExpect(jsonPath("$.content[0].screening").isEmpty())
            .andExpect(jsonPath("$.content[0].status").value("CONFIRMED"));
    }

    @Test
    void bookingFailureIs503BookingUnavailable() throws Exception {
        bookingMode = Mode.DOWN;

        mvc.perform(get("/users/me/bookings").with(WithCurrentUser.user()))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("BOOKING_UNAVAILABLE"))
            .andExpect(jsonPath("$.details[0].reason").value("BOOKING_UNAVAILABLE"));

        // catalog는 부르지 않는다
        assertThat(stub.received).allMatch(r -> r.path().equals("/internal/bookings"));
    }

    // --- USR-05 ---

    @Test
    void preferencesAreEmptyUntilSavedThenReplacedWhole() throws Exception {
        mvc.perform(get("/users/me/preferences").with(WithCurrentUser.user()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.preferredTheaters", hasSize(0)))
            .andExpect(jsonPath("$.preferredTimeSlot").isEmpty())
            .andExpect(jsonPath("$.preferredGenres", hasSize(0)));
        assertThat(stub.received).isEmpty();   // 조회는 다른 서비스를 부르지 않는다

        // 첫 저장: 프로필이 없으므로 Cognito로 먼저 만든다(FK). 극장은 catalog로 확인. rank 순으로 돌려준다
        mvc.perform(put("/users/me/preferences").with(WithCurrentUser.user()).header(HttpHeaders.AUTHORIZATION, BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"preferredTheaters\":[{\"rank\":2,\"theaterId\":1016},{\"rank\":1,\"theaterId\":1004}],"
                    + "\"preferredTimeSlot\":\"EVENING\",\"preferredGenres\":[\"SF\",\"드라마\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.preferredTheaters", hasSize(2)))
            .andExpect(jsonPath("$.preferredTheaters[0].rank").value(1))
            .andExpect(jsonPath("$.preferredTheaters[0].theaterId").value(1004))
            .andExpect(jsonPath("$.preferredTheaters[1].rank").value(2))
            .andExpect(jsonPath("$.preferredTimeSlot").value("EVENING"))
            .andExpect(jsonPath("$.preferredGenres[1]").value("드라마"));
        assertThat(stub.received.stream().map(StubHttpServer.Received::path))
            .containsExactly("/internal/theaters", "/");
        assertThat(stub.received.get(0).query()).isEqualTo("ids=1016,1004");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user`", Long.class)).isEqualTo(1);

        mvc.perform(get("/users/me/preferences").with(WithCurrentUser.user()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.preferredTheaters", hasSize(2)))
            .andExpect(jsonPath("$.preferredTheaters[1].theaterId").value(1016))
            .andExpect(jsonPath("$.preferredTimeSlot").value("EVENING"));

        // 전체 교체: 같은 극장을 다른 순위로, 시간대 없음, 장르 비움 → 이전 행은 사라진다
        mvc.perform(put("/users/me/preferences").with(WithCurrentUser.user())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"preferredTheaters\":[{\"rank\":3,\"theaterId\":1004}],\"preferredTimeSlot\":null,\"preferredGenres\":[]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.preferredTheaters", hasSize(1)))
            .andExpect(jsonPath("$.preferredTheaters[0].rank").value(3))
            .andExpect(jsonPath("$.preferredTimeSlot").isEmpty())
            .andExpect(jsonPath("$.preferredGenres", hasSize(0)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_preferred_theater", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_preference", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT preferred_time_slot FROM user_preference", String.class)).isNull();
    }

    @Test
    void unknownTheaterIs400UnlessCatalogIsDown() throws Exception {
        mvc.perform(put("/users/me/preferences").with(WithCurrentUser.user()).header(HttpHeaders.AUTHORIZATION, BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"preferredTheaters\":[{\"rank\":1,\"theaterId\":1004},{\"rank\":2,\"theaterId\":9999}],\"preferredGenres\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details", hasSize(1)))
            .andExpect(jsonPath("$.details[0].field").value("preferredTheaters[1].theaterId"))
            .andExpect(jsonPath("$.details[0].value").value(9999))
            .andExpect(jsonPath("$.details[0].reason").value("THEATER_NOT_FOUND"));
        assertThat(cognitoCalls()).isZero();   // 검증에 걸리면 프로필도 만들지 않는다
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_preferred_theater", Long.class)).isZero();

        // catalog가 답하지 않으면 검증 없이 저장한다 (계약)
        catalogMode = Mode.DOWN;
        mvc.perform(put("/users/me/preferences").with(WithCurrentUser.user()).header(HttpHeaders.AUTHORIZATION, BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"preferredTheaters\":[{\"rank\":1,\"theaterId\":9999}],\"preferredGenres\":[]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.preferredTheaters[0].theaterId").value(9999));
    }

    @Test
    void duplicateRankOrTheaterIs400() throws Exception {
        mvc.perform(put("/users/me/preferences").with(WithCurrentUser.user()).header(HttpHeaders.AUTHORIZATION, BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"preferredTheaters\":[{\"rank\":1,\"theaterId\":1004},{\"rank\":1,\"theaterId\":1016},"
                    + "{\"rank\":2,\"theaterId\":1004}],\"preferredGenres\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details", hasSize(2)))
            .andExpect(jsonPath("$.details[0].field").value("preferredTheaters[1].rank"))
            .andExpect(jsonPath("$.details[0].reason").value("DUPLICATE_RANK"))
            .andExpect(jsonPath("$.details[1].field").value("preferredTheaters[2].theaterId"))
            .andExpect(jsonPath("$.details[1].reason").value("DUPLICATE_THEATER"));
        assertThat(stub.received).isEmpty();
    }

    @Test
    void savingPreferencesWithoutProfileNeedsToken() throws Exception {
        mvc.perform(put("/users/me/preferences").with(WithCurrentUser.user())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"preferredTheaters\":[],\"preferredGenres\":[]}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.details[0].reason").value("TOKEN_REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_preference", Long.class)).isZero();
    }
}

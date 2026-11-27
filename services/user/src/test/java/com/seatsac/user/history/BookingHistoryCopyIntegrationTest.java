package com.seatsac.user.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatsac.common.test.MySqlContainerSupport;
import com.seatsac.common.test.WithCurrentUser;
import com.seatsac.user.client.StubHttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 6.3.4b 예매 내역 복제본(API 명세서 v1.5 23-1) — {@code APP_BOOKING_HISTORY_MODE=local}에서 첫 조회 채우기·이벤트 반영
 * (CANCELED가 이긴다·확정 전 취소 무시·inbox 중복)·정렬을 MySQL 8.4 컨테이너와 가짜 booking·catalog로 확인한다.
 * 이벤트는 SQS 없이 {@link BookingEventHandler}를 직접 부른다(리스너는 {@code APP_MESSAGING_CONSUMER_ENABLED}가 꺼져 빈이 없다).
 */
@SpringBootTest(properties = "app.booking-history.mode=local")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({MySqlContainerSupport.class, BookingHistoryCopyIntegrationTest.FixedClock.class})
class BookingHistoryCopyIntegrationTest {

    static final Instant NOW = Instant.parse("2026-11-27T03:00:00Z");
    static final String USER = WithCurrentUser.DEFAULT_USER;
    static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    static volatile boolean bookingDown = false;
    /** true면 booking이 두 쪽으로 답한다(2쪽에 오래된 예매 105) */
    static volatile boolean twoPages = false;
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
        stub = new StubHttpServer("/", BookingHistoryCopyIntegrationTest::respond);
    }

    @AfterAll
    static void stopStubs() {
        stub.close();
    }

    @DynamicPropertySource
    static void stubUrls(DynamicPropertyRegistry registry) {
        registry.add("app.services.booking-url", () -> stub.baseUrl());
        registry.add("app.services.catalog-url", () -> stub.baseUrl());
    }

    /**
     * booking 내부 API: 확정 101 · 확정 뒤 취소 102 · 확정 전 취소 103(confirmedAt 없음) · 만료 104. 채우기는 101·102만 가져가야 한다
     */
    private static String respond(StubHttpServer.Received request) {
        stub.status = 200;
        if (request.path().equals("/internal/bookings")) {
            if (bookingDown) {
                stub.status = 503;
                return "{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"\",\"traceId\":\"" + "0".repeat(32) + "\",\"details\":[]}";
            }
            if (twoPages && request.query().contains("page=1")) {
                return "{\"content\":[" + item(105, "CONFIRMED", "\"2026-11-01T10:00:30+09:00\"", "null")
                    + "],\"page\":1,\"size\":50,\"totalElements\":5,\"totalPages\":2}";
            }
            return "{\"content\":["
                + item(101, "CONFIRMED", "\"2026-11-20T10:00:30+09:00\"", "null")
                + "," + item(102, "CANCELED", "\"2026-11-19T10:00:30+09:00\"", "\"2026-11-19T12:00:00+09:00\"")
                + "," + item(103, "CANCELED", "null", "\"2026-11-18T10:03:00+09:00\"")
                + "," + item(104, "EXPIRED", "null", "null")
                + "],\"page\":0,\"size\":50,\"totalElements\":" + (twoPages ? 5 : 4) + ",\"totalPages\":"
                + (twoPages ? 2 : 1) + "}";
        }
        if (request.path().equals("/internal/screenings/summaries")) {
            return "{\"items\":[]}";
        }
        stub.status = 404;
        return "{}";
    }

    private static String item(long bookingId, String status, String confirmedAt, String canceledAt) {
        return "{\"bookingId\":" + bookingId + ",\"status\":\"" + status + "\",\"screeningId\":5001,"
            + "\"seatLabels\":[\"E7\",\"E8\"],\"seatCount\":2,\"amount\":30000,"
            + "\"createdAt\":\"2026-11-16T11:00:00+09:00\",\"confirmedAt\":" + confirmedAt + ",\"canceledAt\":" + canceledAt + "}";
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    BookingEventHandler handler;
    @Autowired
    MeterRegistry meterRegistry;

    @BeforeEach
    void reset() {
        bookingDown = false;
        twoPages = false;
        stub.received.clear();
        jdbc.update("DELETE FROM booking_history");
        jdbc.update("DELETE FROM booking_history_sync");
        jdbc.update("DELETE FROM processed_message");
    }

    private long bookingCalls() {
        return stub.received.stream().filter(r -> r.path().equals("/internal/bookings")).count();
    }

    // --- 첫 조회 채우기 ---

    @Test
    void firstQueryFillsFromBookingOnceThenReadsTheCopyOnly() throws Exception {
        mvc.perform(get("/users/me/bookings").with(WithCurrentUser.user()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content", hasSize(2)))
            .andExpect(jsonPath("$.content[0].bookingId").value(101))
            .andExpect(jsonPath("$.content[0].status").value("CONFIRMED"))
            .andExpect(jsonPath("$.content[0].seatLabels", hasSize(2)))
            .andExpect(jsonPath("$.content[0].amount").value(30000))
            .andExpect(jsonPath("$.content[0].createdAt").value("2026-11-16T11:00:00+09:00"))
            .andExpect(jsonPath("$.content[0].confirmedAt").value("2026-11-20T10:00:30+09:00"))
            .andExpect(jsonPath("$.content[1].bookingId").value(102))
            .andExpect(jsonPath("$.content[1].status").value("CANCELED"))
            .andExpect(jsonPath("$.content[1].canceledAt").value("2026-11-19T12:00:00+09:00"))
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.totalPages").value(1));
        assertThat(bookingCalls()).isEqualTo(1);
        assertThat(stub.received.get(0).query()).isEqualTo("userId=" + USER + "&page=0&size=50");

        // 두 번째부터는 booking을 부르지 않는다(예매가 없는 사용자도 같다)
        mvc.perform(get("/users/me/bookings").with(WithCurrentUser.user()).param("status", "CANCELED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content", hasSize(1)))
            .andExpect(jsonPath("$.content[0].bookingId").value(102));
        assertThat(bookingCalls()).isEqualTo(1);
    }

    @Test
    void bookingFailureDuringFillIs503AndTheNextQueryFillsAgain() throws Exception {
        bookingDown = true;
        mvc.perform(get("/users/me/bookings").with(WithCurrentUser.user()))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("BOOKING_UNAVAILABLE"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking_history_sync", Integer.class)).isZero();

        bookingDown = false;
        mvc.perform(get("/users/me/bookings").with(WithCurrentUser.user()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content", hasSize(2)));
    }

    @Test
    void fillReadsEveryPageBeforeMarkingSynced() throws Exception {
        twoPages = true;
        mvc.perform(get("/users/me/bookings").with(WithCurrentUser.user()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content", hasSize(3)))
            .andExpect(jsonPath("$.content[2].bookingId").value(105));
        assertThat(bookingCalls()).isEqualTo(2);
        assertThat(stub.received.get(1).query()).isEqualTo("userId=" + USER + "&page=1&size=50");
    }

    @Test
    void fillDoesNotOverwriteTheFinalAmountFromTheEvent() throws Exception {
        // 이벤트(쿠폰 뒤 최종 15,000)가 먼저 → 첫 채우기(booking 내부 API는 원가 30,000)가 덮지 않는다
        handler.handle(confirmed("a1000000-0000-4000-8000-000000000001", 101, "2026-11-20T10:00:30+09:00",
            ",\"amount\":15000,\"discountAmount\":15000"));

        mvc.perform(get("/users/me/bookings").with(WithCurrentUser.user()).param("status", "CONFIRMED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].bookingId").value(101))
            .andExpect(jsonPath("$.content[0].amount").value(15000))
            .andExpect(jsonPath("$.content[0].createdAt").value("2026-11-16T11:00:00+09:00")); // 생성 시각은 채우기가 채운다
        assertThat(jdbc.queryForObject("SELECT discount_amount FROM booking_history WHERE booking_id = 101", Long.class))
            .isEqualTo(15000L);
    }

    @Test
    void notStoredStatusesAreEmptyWithoutCallingBooking() throws Exception {
        synced();
        mvc.perform(get("/users/me/bookings").with(WithCurrentUser.user()).param("status", "EXPIRED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content", hasSize(0)))
            .andExpect(jsonPath("$.totalElements").value(0));
        assertThat(bookingCalls()).isZero();
    }

    // --- 이벤트 ---

    @Test
    void confirmedEventAddsARowWithAmountAndDiscountNewestFirst() throws Exception {
        synced();
        assertThat(handler.handle(confirmed("a0000000-0000-4000-8000-000000000001", 201, "2026-11-26T10:00:00+09:00",
            ",\"amount\":15000,\"discountAmount\":15000"))).isEqualTo(BookingEventHandler.Result.APPLIED);
        handler.handle(confirmed("a0000000-0000-4000-8000-000000000002", 202, "2026-11-26T11:00:00+09:00", ""));

        mvc.perform(get("/users/me/bookings").with(WithCurrentUser.user()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content", hasSize(2)))
            .andExpect(jsonPath("$.content[0].bookingId").value(202))
            .andExpect(jsonPath("$.content[0].amount").value(30000)) // 금액 없는 이전 메시지 → 좌석 수 × 15,000
            .andExpect(jsonPath("$.content[0].createdAt").value("2026-11-26T11:00:00+09:00")) // 이벤트 행은 확정 시각
            .andExpect(jsonPath("$.content[1].bookingId").value(201))
            .andExpect(jsonPath("$.content[1].amount").value(15000));
        assertThat(jdbc.queryForObject("SELECT discount_amount FROM booking_history WHERE booking_id = 201", Long.class))
            .isEqualTo(15000L);
        assertThat(bookingCalls()).isZero();
    }

    @Test
    void canceledWinsEvenWhenTheConfirmedArrivesLate() throws Exception {
        synced();
        handler.handle(canceled("b0000000-0000-4000-8000-000000000001", 301, "USER_CANCELED"));
        // 확정 내용을 아직 모르는 행은 내지 않는다
        mvc.perform(get("/users/me/bookings").with(WithCurrentUser.user()))
            .andExpect(jsonPath("$.content", hasSize(0)));

        handler.handle(confirmed("b0000000-0000-4000-8000-000000000002", 301, "2026-11-26T10:00:00+09:00", ""));

        mvc.perform(get("/users/me/bookings").with(WithCurrentUser.user()))
            .andExpect(jsonPath("$.content", hasSize(1)))
            .andExpect(jsonPath("$.content[0].bookingId").value(301))
            .andExpect(jsonPath("$.content[0].status").value("CANCELED"))
            .andExpect(jsonPath("$.content[0].seatLabels", hasSize(2)))
            .andExpect(jsonPath("$.content[0].confirmedAt").value("2026-11-26T10:00:00+09:00"))
            .andExpect(jsonPath("$.content[0].canceledAt").value("2026-11-26T12:00:00+09:00"));
    }

    @Test
    void canceledAfterConfirmedTurnsTheRowCanceled() throws Exception {
        synced();
        handler.handle(confirmed("c0000000-0000-4000-8000-000000000001", 401, "2026-11-26T10:00:00+09:00", ""));
        handler.handle(canceled("c0000000-0000-4000-8000-000000000002", 401, "USER_CANCELED"));

        mvc.perform(get("/users/me/bookings").with(WithCurrentUser.user()))
            .andExpect(jsonPath("$.content[0].status").value("CANCELED"))
            .andExpect(jsonPath("$.content[0].amount").value(30000));
    }

    @Test
    void canceledOfANeverConfirmedBookingIsIgnored() {
        assertThat(handler.handle(canceled("d0000000-0000-4000-8000-000000000001", 501, "EXPIRED")))
            .isEqualTo(BookingEventHandler.Result.IGNORED);
        assertThat(handler.handle(canceled("d0000000-0000-4000-8000-000000000002", 502, "PAYMENT_FAILED")))
            .isEqualTo(BookingEventHandler.Result.IGNORED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking_history", Integer.class)).isZero();
        // 무시한 것도 inbox에 남긴다(재배달에 같은 판단을 다시 하지 않는다)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_message", Integer.class)).isEqualTo(2);
    }

    @Test
    void sameEventIdIsAppliedOnce() {
        String event = confirmed("e0000000-0000-4000-8000-000000000001", 601, "2026-11-26T10:00:00+09:00", "");
        assertThat(handler.handle(event)).isEqualTo(BookingEventHandler.Result.APPLIED);
        jdbc.update("UPDATE booking_history SET status = 'CANCELED' WHERE booking_id = 601");
        assertThat(handler.handle(event)).isEqualTo(BookingEventHandler.Result.DUPLICATE);
        assertThat(meterRegistry.counter("user_message_consumed_total", "type", "booking.confirmed", "result", "duplicate")
            .count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void malformedEventsThrowAndWriteNothing() {
        assertThatThrownBy(() -> handler.handle("not json")).isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> handler.handle("{\"eventId\":\"f0000000-0000-4000-8000-000000000001\","
            + "\"type\":\"booking.confirmed\",\"payload\":{\"bookingId\":1}}"))
            .isInstanceOf(MalformedEventException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_message", Integer.class)).isZero();
    }

    @Test
    void unknownTypeIsIgnored() {
        assertThat(handler.handle("{\"eventId\":\"f1000000-0000-4000-8000-000000000001\",\"type\":\"booking.held\","
            + "\"payload\":{}}")).isEqualTo(BookingEventHandler.Result.IGNORED);
    }

    private void synced() {
        jdbc.update("INSERT INTO booking_history_sync (user_id, synced_at) VALUES (?, ?)", USER,
            java.sql.Timestamp.from(NOW));
    }

    private static String confirmed(String eventId, long bookingId, String confirmedAt, String extra) {
        return "{\"eventId\":\"" + eventId + "\",\"type\":\"booking.confirmed\",\"version\":1,"
            + "\"occurredAt\":\"" + confirmedAt + "\",\"producer\":\"booking\",\"traceId\":\"" + TRACE + "\","
            + "\"payload\":{\"bookingId\":" + bookingId + ",\"userId\":\"" + USER + "\",\"screeningId\":5001,"
            + "\"movieTitle\":null,\"theaterName\":null,\"auditoriumName\":null,\"startsAt\":null,"
            + "\"seatLabels\":[\"J7\",\"J8\"],\"seatCount\":2,\"confirmedAt\":\"" + confirmedAt + "\"" + extra + "}}";
    }

    private static String canceled(String eventId, long bookingId, String reason) {
        return "{\"eventId\":\"" + eventId + "\",\"type\":\"booking.canceled\",\"version\":1,"
            + "\"occurredAt\":\"2026-11-26T12:00:00+09:00\",\"producer\":\"booking\",\"traceId\":\"" + TRACE + "\","
            + "\"payload\":{\"bookingId\":" + bookingId + ",\"userId\":\"" + USER + "\",\"screeningId\":5001,"
            + "\"reason\":\"" + reason + "\",\"canceledAt\":\"2026-11-26T12:00:00+09:00\"}}";
    }
}

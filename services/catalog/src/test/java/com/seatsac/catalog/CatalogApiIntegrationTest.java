package com.seatsac.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatsac.common.test.MySqlContainerSupport;
import com.seatsac.common.test.ValkeyContainerSupport;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 3.3.2 확인 — CAT-01~05 + 내부 3개를 MySQL 8.4·Valkey 컨테이너와 가짜 booking(JDK HttpServer)으로 끝까지 돈다.
 * DDL은 Flyway가 만들고 엔티티는 {@code ddl-auto=validate}로 검증되므로 컨텍스트가 뜨면 매핑과 DDL이 맞는다는 뜻이다.
 * 데이터는 합성 fixture(롯데 시드 아님). Clock은 KST 2026-11-16 12:00으로 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability   // 테스트 기본은 메트릭 내보내기를 끈다 → /actuator/prometheus 확인을 위해 켠다
@ActiveProfiles("test")
@Import({MySqlContainerSupport.class, ValkeyContainerSupport.class, CatalogApiIntegrationTest.FixedClock.class})
class CatalogApiIntegrationTest {

    /** KST 2026-11-16 12:00 */
    static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
    static final String TRACE_FORMAT = "^[0-9a-f]{32}$";

    enum BookingMode { OK, SLOW, DOWN }

    static volatile BookingMode bookingMode = BookingMode.OK;
    static HttpServer booking;
    static ExecutorService bookingPool;

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @BeforeAll
    static void startBookingStub() throws Exception {
        booking = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 기본 실행기는 요청을 한 스레드에서 차례로 처리한다 → SLOW 모드가 쉬는 동안 다음
        // 테스트의 요청이 뒤에 밀려 같이 타임아웃된다(대체값으로 떨어져 엉뚱한 테스트가 깨진다)
        bookingPool = Executors.newCachedThreadPool();
        booking.setExecutor(bookingPool);
        booking.createContext("/internal/screenings/seat-counts", exchange -> {
            String ids = Objects.requireNonNullElse(exchange.getRequestURI().getQuery(), "");
            BookingMode mode = bookingMode;
            if (mode == BookingMode.SLOW) {
                try {
                    Thread.sleep(2_000);   // 테스트 프로필의 응답 타임아웃(1.5초)을 넘긴다
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body;
            int status;
            if (mode == BookingMode.DOWN) {
                status = 503;
                body = "{}".getBytes(StandardCharsets.UTF_8);
            } else {
                status = 200;
                // 계약대로 요청한 회차를 모두 돌려준다. 5001만 선점 1 + 확정 1(→ 잔여 2), 나머지 0. 모르는 필드(extra)는 무시돼야 한다
                String items = Arrays.stream(ids.replace("ids=", "").split(","))
                    .filter(s -> !s.isBlank())
                    .map(id -> "{\"screeningId\":" + id + ",\"heldCount\":" + ("5001".equals(id) ? 1 : 0)
                        + ",\"reservedCount\":" + ("5001".equals(id) ? 1 : 0) + ",\"extra\":true}")
                    .collect(Collectors.joining(","));
                body = ("{\"items\":[" + items + "]}").getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        booking.start();
    }

    @AfterAll
    static void stopBookingStub() {
        booking.stop(0);
        bookingPool.shutdownNow();
    }

    @DynamicPropertySource
    static void bookingUrl(DynamicPropertyRegistry registry) {
        registry.add("app.services.booking-url", () -> "http://127.0.0.1:" + booking.getAddress().getPort());
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    MeterRegistry meterRegistry;

    @BeforeEach
    void fixture() {
        bookingMode = BookingMode.OK;
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();

        jdbc.update("DELETE FROM screening");
        jdbc.update("DELETE FROM seat");
        jdbc.update("DELETE FROM auditorium_layout");
        jdbc.update("DELETE FROM auditorium");
        jdbc.update("DELETE FROM theater");
        jdbc.update("DELETE FROM movie");

        jdbc.update("INSERT INTO theater (theater_id, name, source_provider, source_theater_id, region, has_layout) "
            + "VALUES (1, '테스트극장', 'TEST', 1, '서울', 1), (2, '배치없음극장', 'TEST', 2, '부산', 0)");
        jdbc.update("INSERT INTO auditorium (auditorium_id, theater_id, name, screen_type_code, source_auditorium_id, "
            + "total_seats, layout_version, layout_width, layout_height) VALUES (10, 1, '1관', 986, 'A', 4, 1, 10.000, 8.000)");
        jdbc.update("INSERT INTO auditorium_layout (auditorium_id, layout_version, screen_x_from, screen_x_to, entrances, "
            + "vertical_aisles, horizontal_aisles, source_provider, fetched_at) VALUES (10, 1, 0.000, 9.500, "
            + "'[{\"label\":\"입구\",\"x\":8.250,\"y\":0.082,\"angle_code\":20}]', "
            + "'[{\"x_from\":1.799,\"x_to\":2.962,\"rows\":9}]', "
            + "'[{\"after_row\":\"A\",\"before_row\":\"B\",\"y_from\":4.809,\"y_to\":5.544}]', "
            + "'LOTTE', '2026-10-14 00:45:47.000')");
        jdbc.update("INSERT INTO seat (seat_id, auditorium_id, layout_version, row_label, col_no, seat_label, x, y, w, h, "
            + "zone_no, seat_type, source_seat_code, active) VALUES "
            + "(100001, 10, 1, 'A', 1, 'A1', 1.0, 1.0, 0.9, 0.9, 1, 'NORMAL', 1, 1), "
            + "(100002, 10, 1, 'A', 2, 'A2', 2.0, 1.0, 0.9, 0.9, 1, 'NORMAL', 1, 0), "
            + "(100003, 10, 1, 'B', 1, 'B1', 1.0, 2.0, 0.9, 0.9, 1, 'RECLINER', 190, 1), "
            + "(100004, 10, 1, 'B', 2, 'B2', 2.0, 2.0, 0.9, 0.9, 1, 'UNKNOWN', 290, 1)");
        jdbc.update("INSERT INTO movie (movie_id, source_provider, source_movie_code, title, title_en, view_grade, "
            + "view_grade_code, slot_minutes, genres, overview, release_date, runtime_minutes, poster_path, director, cast_names) VALUES "
            + "(1, 'TEST', 'M1', '첫 영화', 'First', '12', 12, 115, '[\"드라마\",\"SF\"]', '줄거리', '2026-10-01', 108, "
            + "'/poster1.jpg', '감독', '[\"배우A\",\"배우B\"]'), "
            + "(2, 'TEST', 'M2', '둘째 영화', NULL, '전체', 0, 100, NULL, NULL, NULL, NULL, NULL, NULL, NULL)");
        // 5001 KST 14:30(아직), 5002 KST 10:00(시작함), 5003 다음 영업일
        jdbc.update("INSERT INTO screening (screening_id, auditorium_id, layout_version, movie_id, business_date, play_sequence, "
            + "starts_at, ends_at, raw_start_time, raw_end_time, film_format) VALUES "
            + "(5001, 10, 1, 1, '2026-11-16', 2, '2026-11-16 05:30:00.000', '2026-11-16 07:25:00.000', '14:30', '16:25', '2D'), "
            + "(5002, 10, 1, 2, '2026-11-16', 1, '2026-11-16 01:00:00.000', '2026-11-16 02:40:00.000', '10:00', '11:40', '2D'), "
            + "(5003, 10, 1, 1, '2026-11-17', 1, '2026-11-17 01:00:00.000', '2026-11-17 02:55:00.000', '10:00', '11:55', '2D')");
    }

    // --- CAT-01 · CAT-02 ---

    @Test
    void listMoviesReturnsPageWithContractFields() throws Exception {
        mvc.perform(get("/movies"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content", hasSize(2)))
            .andExpect(jsonPath("$.content[0].movieId").value(1))
            .andExpect(jsonPath("$.content[0].viewGrade").value("12"))
            .andExpect(jsonPath("$.content[0].posterUrl").value(startsWith("https://image.tmdb.org/t/p/")))
            .andExpect(jsonPath("$.content[1].viewGrade").value("ALL"))
            .andExpect(jsonPath("$.content[1].posterUrl").isEmpty())
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.totalPages").value(1));

        mvc.perform(get("/movies").param("status", "COMING_SOON"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content", hasSize(0)))
            .andExpect(jsonPath("$.totalElements").value(0));

        assertThat(redis.hasKey("catalog:movies:NOW_SHOWING:0:20")).isTrue();
    }

    @Test
    void getMovieFillsTmdbFieldsAndNullsWhenMissing() throws Exception {
        mvc.perform(get("/movies/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.synopsis").value("줄거리"))
            .andExpect(jsonPath("$.director").value("감독"))
            .andExpect(jsonPath("$.cast", hasSize(2)))
            .andExpect(jsonPath("$.genres[0]").value("드라마"))
            .andExpect(jsonPath("$.releaseDate").value("2026-10-01"))
            .andExpect(jsonPath("$.runtimeMinutes").value(108))
            .andExpect(jsonPath("$.slotMinutes").value(115));

        mvc.perform(get("/movies/2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.synopsis").isEmpty())
            .andExpect(jsonPath("$.cast", hasSize(0)))
            .andExpect(jsonPath("$.genres", hasSize(0)))
            .andExpect(jsonPath("$.releaseDate").isEmpty());
    }

    @Test
    void unknownMovieIs404WithErrorContract() throws Exception {
        mvc.perform(get("/movies/999"))
            .andExpect(status().isNotFound())
            .andExpect(header().string("X-Trace-Id", matchesPattern(TRACE_FORMAT)))
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.traceId").value(matchesPattern(TRACE_FORMAT)))
            .andExpect(jsonPath("$.details").isArray())
            .andExpect(jsonPath("$.details[0].reason").value("MOVIE_NOT_FOUND"));

        assertThat(redis.hasKey("catalog:movie:999")).isFalse();   // 404는 캐시하지 않는다
    }

    // --- CAT-03 ---

    @Test
    void listTheatersDefaultsToLayoutOnly() throws Exception {
        mvc.perform(get("/theaters"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items", hasSize(1)))
            .andExpect(jsonPath("$.items[0].theaterId").value(1))
            .andExpect(jsonPath("$.items[0].region").value("서울"))
            .andExpect(jsonPath("$.items[0].hasLayout").value(true));

        mvc.perform(get("/theaters").param("hasLayout", "false").param("region", "부산"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items", hasSize(1)))
            .andExpect(jsonPath("$.items[0].theaterId").value(2));

        // region은 캐시 키에 들어가지 않는다 — 전체를 캐시하고 메모리에서 거른다 (키 수 제한)
        mvc.perform(get("/theaters").param("hasLayout", "false").param("region", "없는지역"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items", hasSize(0)));
        assertThat(redis.hasKey("catalog:theaters:true:all")).isTrue();
        assertThat(redis.hasKey("catalog:theaters:false:all")).isTrue();
        assertThat(redis.keys("catalog:theaters:*")).hasSize(2);
    }

    @Test
    void listScreeningsExcludesStartedAndFillsRemainingSeatsFromBooking() throws Exception {
        mvc.perform(get("/screenings").param("date", "2026-11-16").param("theaterId", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items", hasSize(1)))          // 5002(KST 10:00)는 시작했으므로 제외
            .andExpect(jsonPath("$.items[0].screeningId").value(5001))
            .andExpect(jsonPath("$.items[0].movie.title").value("첫 영화"))
            .andExpect(jsonPath("$.items[0].movie.viewGrade").value("12"))
            .andExpect(jsonPath("$.items[0].theater.name").value("테스트극장"))
            .andExpect(jsonPath("$.items[0].auditorium.screenType").value("RECLINER"))
            .andExpect(jsonPath("$.items[0].layoutVersion").value(1))
            .andExpect(jsonPath("$.items[0].businessDate").value("2026-11-16"))
            .andExpect(jsonPath("$.items[0].startsAt").value("2026-11-16T14:30:00+09:00"))
            .andExpect(jsonPath("$.items[0].endsAt").value("2026-11-16T16:25:00+09:00"))
            .andExpect(jsonPath("$.items[0].totalSeats").value(4))
            .andExpect(jsonPath("$.items[0].remainingSeats").value(2))
            .andExpect(jsonPath("$.items[0].remainingSeatsSource").value("BOOKING"));

        // 영업일 전체(5001·5002)가 캐시에 들어가고, 적중해도 시각은 +09:00 그대로, 잔여 좌석은 매번 booking에서
        assertThat(redis.opsForValue().get("catalog:screenings:2026-11-16:1:all"))
            .contains("\"screeningId\":5001").contains("\"screeningId\":5002").doesNotContain("remainingSeats");
        double hitsBefore = hits();
        mvc.perform(get("/screenings").param("date", "2026-11-16").param("theaterId", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items", hasSize(1)))
            .andExpect(jsonPath("$.items[0].startsAt").value("2026-11-16T14:30:00+09:00"))
            .andExpect(jsonPath("$.items[0].remainingSeats").value(2));
        assertThat(hits()).isGreaterThan(hitsBefore);

        // movieId 필터 → 다른 키. 시드 기간 밖은 빈 목록
        mvc.perform(get("/screenings").param("date", "2026-11-16").param("theaterId", "1").param("movieId", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(get("/screenings").param("date", "2027-01-01").param("theaterId", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items", hasSize(0)));
        assertThat(redis.hasKey("catalog:screenings:2026-11-16:1:2")).isTrue();
    }

    @Test
    void listScreeningsFallsBackToTotalWhenBookingIsSlowOrDown() throws Exception {
        double fallbacksBefore = fallbacks();

        bookingMode = BookingMode.SLOW;
        mvc.perform(get("/screenings").param("date", "2026-11-16").param("theaterId", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].remainingSeats").value(4))
            .andExpect(jsonPath("$.items[0].remainingSeatsSource").value("TOTAL"));

        bookingMode = BookingMode.DOWN;
        mvc.perform(get("/screenings/5001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.remainingSeats").value(4))
            .andExpect(jsonPath("$.remainingSeatsSource").value("TOTAL"));

        assertThat(fallbacks()).isEqualTo(fallbacksBefore + 2);
    }

    @Test
    void getScreeningAndValidation() throws Exception {
        mvc.perform(get("/screenings/5003"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.screeningId").value(5003))
            .andExpect(jsonPath("$.businessDate").value("2026-11-17"))
            .andExpect(jsonPath("$.remainingSeats").value(4))
            .andExpect(jsonPath("$.remainingSeatsSource").value("BOOKING"));

        mvc.perform(get("/screenings/999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.details[0].reason").value("SCREENING_NOT_FOUND"));

        mvc.perform(get("/screenings").param("theaterId", "1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details[0].field").value("date"));

        mvc.perform(get("/screenings").param("date", "2026-13-01").param("theaterId", "1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // --- CAT-04 ---

    @Test
    void layoutMapsSeedJsonToContractShape() throws Exception {
        mvc.perform(get("/auditoriums/10/layout"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.auditoriumId").value(10))
            .andExpect(jsonPath("$.name").value("1관"))
            .andExpect(jsonPath("$.screenType").value("RECLINER"))
            .andExpect(jsonPath("$.layoutVersion").value(1))
            .andExpect(jsonPath("$.width").value(10.0))
            .andExpect(jsonPath("$.screen.xFrom").value(0.0))
            .andExpect(jsonPath("$.screen.xTo").value(9.5))
            .andExpect(jsonPath("$.entrances[0].x").value(8.25))
            .andExpect(jsonPath("$.entrances[0].label").doesNotExist())
            .andExpect(jsonPath("$.verticalAisles[0].xFrom").value(1.799))
            .andExpect(jsonPath("$.verticalAisles[0].xTo").value(2.962))
            .andExpect(jsonPath("$.horizontalAisles[0].yFrom").value(4.809))
            .andExpect(jsonPath("$.seats", hasSize(4)))
            .andExpect(jsonPath("$.seats[0].seatId").value(100001))
            .andExpect(jsonPath("$.seats[0].seatLabel").value("A1"))
            .andExpect(jsonPath("$.seats[0].seatType").value("NORMAL"))
            .andExpect(jsonPath("$.seats[1].active").value(false))
            .andExpect(jsonPath("$.seats[2].seatType").value("RECLINER"))
            .andExpect(jsonPath("$.seats[3].seatType").value("UNKNOWN"))
            .andExpect(jsonPath("$.source.provider").value("LOTTE"))
            .andExpect(jsonPath("$.source.fetchedAt").value("2026-10-14T09:45:47+09:00"));

        assertThat(redis.hasKey("catalog:layout:10:1")).isTrue();

        // 캐시 적중 응답도 같은 모양·같은 오프셋
        mvc.perform(get("/auditoriums/10/layout").param("version", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.seats", hasSize(4)))
            .andExpect(jsonPath("$.source.fetchedAt").value("2026-10-14T09:45:47+09:00"));

        mvc.perform(get("/auditoriums/10/layout").param("version", "2"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.details[0].reason").value("LAYOUT_NOT_FOUND"));
        mvc.perform(get("/auditoriums/99/layout"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.details[0].reason").value("AUDITORIUM_NOT_FOUND"));
    }

    // --- 내부 API ---

    @Test
    void internalScreeningForBooking() throws Exception {
        mvc.perform(get("/internal/screenings/5001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.screeningId").value(5001))
            .andExpect(jsonPath("$.auditoriumId").value(10))
            .andExpect(jsonPath("$.layoutVersion").value(1))
            .andExpect(jsonPath("$.startsAt").value("2026-11-16T14:30:00+09:00"))
            .andExpect(jsonPath("$.movieTitle").value("첫 영화"))
            .andExpect(jsonPath("$.theaterName").value("테스트극장"))
            .andExpect(jsonPath("$.auditoriumName").value("1관"))
            .andExpect(jsonPath("$.seats").doesNotExist());   // 2.2.4 v0.3에서 좌석 ID 삭제

        mvc.perform(get("/internal/screenings/999"))
            .andExpect(status().isNotFound());
    }

    @Test
    void internalSummariesAndTheaters() throws Exception {
        mvc.perform(post("/internal/screenings/summaries").contentType(MediaType.APPLICATION_JSON)
                .content("{\"screeningIds\":[5003,5001,999]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items", hasSize(2)))            // 없는 ID는 빠진다
            .andExpect(jsonPath("$.items[0].screeningId").value(5001))
            .andExpect(jsonPath("$.items[0].movieTitle").value("첫 영화"))
            .andExpect(jsonPath("$.items[0].endsAt").value("2026-11-16T16:25:00+09:00"));

        mvc.perform(post("/internal/screenings/summaries").contentType(MediaType.APPLICATION_JSON)
                .content("{\"screeningIds\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details[0].field").value("screeningIds"));

        mvc.perform(get("/internal/theaters").param("ids", "2,1,77"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items", hasSize(2)))
            .andExpect(jsonPath("$.items[0].theaterId").value(1))
            .andExpect(jsonPath("$.items[0].name").value("테스트극장"));

        mvc.perform(get("/internal/theaters").param("ids", "1,2,3,4,5,6,7,8,9,10,11"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void readinessIncludesDbAndPrometheusExposesCatalogMetrics() throws Exception {
        mvc.perform(get("/movies")).andExpect(status().isOk());
        mvc.perform(get("/movies")).andExpect(status().isOk());

        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isOk())
            .andExpect(result -> {
                String body = result.getResponse().getContentAsString();
                assertThat(body).contains("catalog_cache_requests_total{").contains("result=\"hit\"").contains("result=\"miss\"");
                assertThat(body).contains("catalog_seat_counts_fallback_total");
                assertThat(body).contains("application=\"catalog\"");
            });
    }

    private double hits() {
        return meterRegistry.get("catalog.cache.requests").tag("result", "hit").counter().count();
    }

    private double fallbacks() {
        return meterRegistry.get("catalog.seat.counts.fallback").counter().count();
    }
}

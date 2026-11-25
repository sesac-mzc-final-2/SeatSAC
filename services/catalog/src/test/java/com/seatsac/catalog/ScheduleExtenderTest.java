package com.seatsac.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatsac.catalog.schedule.ScheduleExtender;
import com.seatsac.common.test.MySqlContainerSupport;
import com.seatsac.common.test.ValkeyContainerSupport;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 상영 일정 이어 붙이기 — Clock은 KST 2026-11-16 12:00 고정(통합 테스트의 FixedClock).
 * 편성은 11/16(2회차)·11/17(1회차)만 있고, 기본 7일 창(11/16~11/22) 중 11/18~11/22를 앞선 날 편성으로 채워야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
@Import({MySqlContainerSupport.class, ValkeyContainerSupport.class, CatalogApiIntegrationTest.FixedClock.class})
class ScheduleExtenderTest {

    @Autowired
    ScheduleExtender extender;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void fixture() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        jdbc.update("DELETE FROM screening");
        jdbc.update("DELETE FROM seat");
        jdbc.update("DELETE FROM auditorium_layout");
        jdbc.update("DELETE FROM auditorium");
        jdbc.update("DELETE FROM theater");
        jdbc.update("DELETE FROM movie");
        jdbc.update("INSERT INTO theater (theater_id, name, source_provider, source_theater_id, region, has_layout) "
            + "VALUES (1, '테스트극장', 'TEST', 1, '서울', 1)");
        jdbc.update("INSERT INTO auditorium (auditorium_id, theater_id, name, screen_type_code, source_auditorium_id, "
            + "total_seats, layout_version, layout_width, layout_height) VALUES (10, 1, '1관', 986, 'A', 4, 1, 10.000, 8.000)");
        jdbc.update("INSERT INTO auditorium_layout (auditorium_id, layout_version, screen_x_from, screen_x_to, entrances, "
            + "vertical_aisles, horizontal_aisles, source_provider, fetched_at) VALUES (10, 1, 0.000, 9.500, '[]', '[]', '[]', "
            + "'TEST', '2026-10-14 00:45:47.000')");
        jdbc.update("INSERT INTO movie (movie_id, source_provider, source_movie_code, title, view_grade, view_grade_code, "
            + "slot_minutes) VALUES (1, 'TEST', 'M1', '첫 영화', '12', 12, 115)");
        // 11/17 회차는 KST 24:10 종료(UTC 다음 날)라 날짜를 옮겨도 종료가 시작보다 뒤인지 본다
        jdbc.update("INSERT INTO screening (screening_id, auditorium_id, layout_version, movie_id, business_date, play_sequence, "
            + "starts_at, ends_at, raw_start_time, raw_end_time, film_format) VALUES "
            + "(1, 10, 1, 1, '2026-11-16', 1, '2026-11-16 01:00:00.000', '2026-11-16 02:55:00.000', '10:00', '11:55', '2D'), "
            + "(2, 10, 1, 1, '2026-11-16', 2, '2026-11-16 05:30:00.000', '2026-11-16 07:25:00.000', '14:30', '16:25', '2D'), "
            + "(3, 10, 1, 1, '2026-11-17', 1, '2026-11-17 13:15:00.000', '2026-11-17 15:10:00.000', '22:15', '24:10', '2D')");
    }

    @Test
    void fillsMissingDaysFromPreviousDayAndKeepsExistingDays() {
        List<LocalDate> filled = extender.extend();

        assertThat(filled).containsExactly(LocalDate.parse("2026-11-18"), LocalDate.parse("2026-11-19"),
            LocalDate.parse("2026-11-20"), LocalDate.parse("2026-11-21"), LocalDate.parse("2026-11-22"));
        // 이미 있는 날은 그대로
        assertThat(count("2026-11-16")).isEqualTo(2);
        assertThat(count("2026-11-17")).isEqualTo(1);

        Map<String, Object> copied = jdbc.queryForMap("SELECT screening_id, play_sequence, raw_start_time, raw_end_time, "
            + "DATE_FORMAT(starts_at, '%Y-%m-%d %H:%i') AS starts, DATE_FORMAT(ends_at, '%Y-%m-%d %H:%i') AS ends "
            + "FROM screening WHERE business_date = '2026-11-20'");
        assertThat(copied.get("screening_id")).isEqualTo(LocalDate.parse("2026-11-20").toEpochDay() * 100_000L + 1);
        assertThat(copied.get("play_sequence")).isEqualTo(1);
        assertThat(copied.get("raw_start_time")).isEqualTo("22:15");
        assertThat(copied.get("raw_end_time")).isEqualTo("24:10");
        assertThat(copied.get("starts")).isEqualTo("2026-11-20 13:15");
        assertThat(copied.get("ends")).isEqualTo("2026-11-20 15:10");
    }

    @Test
    void secondRunAddsNothing() {
        extender.extend();
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM screening", Integer.class);

        assertThat(extender.extend()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM screening", Integer.class)).isEqualTo(total);
    }

    @Test
    void evictsScreeningListCacheOfFilledDaysOnly() {
        redis.opsForValue().set("catalog:screenings:2026-11-19:1:all", "{\"items\":[]}");
        redis.opsForValue().set("catalog:screenings:2026-11-19:1:1", "{\"items\":[]}");
        redis.opsForValue().set("catalog:screenings:2026-11-16:1:all", "{\"items\":[]}");

        extender.extend();

        assertThat(redis.hasKey("catalog:screenings:2026-11-19:1:all")).isFalse();
        assertThat(redis.hasKey("catalog:screenings:2026-11-19:1:1")).isFalse();
        assertThat(redis.hasKey("catalog:screenings:2026-11-16:1:all")).isTrue();
    }

    @Test
    void emptyDatabaseFillsNothing() {
        jdbc.update("DELETE FROM screening");

        assertThat(extender.extend()).isEmpty();
    }

    private int count(String date) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM screening WHERE business_date = ?", Integer.class, date);
    }
}

package com.seatsac.catalog.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.catalog.event.CatalogChangeReader.Snapshot;
import com.seatsac.common.test.MySqlContainerSupport;
import com.seatsac.common.test.ValkeyContainerSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 6.3.4a — 변경 찾기(장부 비교) → outbox → 릴레이를 MySQL 8.4 컨테이너로 끝까지 돈다. 원본 변경은 시드·TMDB 스크립트처럼
 * SQL로 직접 한다(catalog에는 쓰기 API가 없다). 메시지 모양은 계약 파일({@code contracts/events/*.changed.v1.json})의
 * {@code required}·{@code then.required}로 확인한다 — 값이 null이어도 키는 있어야 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({MySqlContainerSupport.class, ValkeyContainerSupport.class})
class CatalogChangeEventsIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path CONTRACTS = Path.of("../../contracts/events");

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DataSource dataSource;
    @Autowired
    CatalogChangeScanner scanner;
    @Autowired
    CatalogChangeReader reader;
    @Autowired
    CatalogChangeWriter writer;
    @Autowired
    CatalogOutboxRelay relay;
    @Autowired
    CatalogOutboxRepository outboxRepository;
    @MockitoBean
    CatalogEventPublisher publisher;

    @BeforeEach
    void fixture() {
        reset(publisher);
        jdbc.update("DELETE FROM outbox_message");
        jdbc.update("DELETE FROM change_ledger");
        jdbc.update("DELETE FROM screening");
        jdbc.update("DELETE FROM seat");
        jdbc.update("DELETE FROM auditorium_layout");
        jdbc.update("DELETE FROM auditorium");
        jdbc.update("DELETE FROM theater");
        jdbc.update("DELETE FROM movie");

        jdbc.update("INSERT INTO theater (theater_id, name, source_provider, source_theater_id, region, has_layout) "
            + "VALUES (1, '테스트극장', 'TEST', 1, '서울', 1)");
        jdbc.update("INSERT INTO auditorium (auditorium_id, theater_id, name, screen_type_code, source_auditorium_id, "
            + "total_seats, layout_version) VALUES (10, 1, '1관', 986, 'A', 4, 1)");
        jdbc.update("INSERT INTO auditorium_layout (auditorium_id, layout_version, screen_x_from, screen_x_to, entrances, "
            + "vertical_aisles, horizontal_aisles, source_provider, fetched_at) VALUES (10, 1, 0, 9.5, '[]', '[]', '[]', "
            + "'TEST', '2026-10-14 00:45:47.000')");
        jdbc.update("INSERT INTO movie (movie_id, source_provider, source_movie_code, title, title_en, view_grade, "
            + "view_grade_code, slot_minutes, genres, overview, release_date, runtime_minutes, poster_path, director, cast_names) VALUES "
            + "(1, 'TEST', 'M1', '첫 영화', 'First', '12', 12, 115, '[\"드라마\",\"SF\"]', '줄거리', '2026-10-01', 108, "
            + "'/poster1.jpg', '감독', '[\"배우A\",\"배우B\"]'), "
            + "(2, 'TEST', 'M2', '둘째 영화', NULL, '전체', 0, 100, NULL, NULL, NULL, NULL, NULL, NULL, NULL)");
        jdbc.update("INSERT INTO screening (screening_id, auditorium_id, layout_version, movie_id, business_date, play_sequence, "
            + "starts_at, ends_at, raw_start_time, raw_end_time, film_format) VALUES "
            + "(5001, 10, 1, 1, '2026-11-16', 2, '2026-11-16 05:30:00.000', '2026-11-16 07:25:00.000', '14:30', '16:25', '2D'), "
            + "(5002, 10, 1, 2, '2026-11-16', 3, '2026-11-15 15:30:00.000', '2026-11-15 17:10:00.000', '00:30', '02:10', NULL)");
    }

    @Test
    void firstScanEmitsEveryScreeningAndMovieOnceInContractShape() throws Exception {
        CatalogChangeScanner.Result result = scanner.scanOnce();

        assertThat(result.ran()).isTrue();
        assertThat(result.upserts()).isEqualTo(4);
        assertThat(result.deletes()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM change_ledger WHERE version = 1 AND deleted = 0",
            Integer.class)).isEqualTo(4);

        JsonNode screening = onlyMessage("screening.changed", 5001);
        assertContractKeys(screening, "screening.changed.v1.json");
        assertThat(screening.path("producer").asText()).isEqualTo("catalog");
        assertThat(screening.path("version").asInt()).isEqualTo(1);
        assertThat(screening.path("traceId").asText()).matches("^[0-9a-f]{32}$");
        assertThat(screening.has("correlationId")).isTrue();
        assertThat(screening.path("correlationId").isNull()).isTrue();
        JsonNode p = screening.path("payload");
        assertThat(p.path("screeningId").asLong()).isEqualTo(5001);
        assertThat(p.path("version").asLong()).isEqualTo(1);
        assertThat(p.path("deleted").asBoolean()).isFalse();
        assertThat(p.path("theaterName").asText()).isEqualTo("테스트극장");
        assertThat(p.path("auditoriumName").asText()).isEqualTo("1관");
        assertThat(p.path("screenType").asText()).isEqualTo("RECLINER");
        assertThat(p.path("businessDate").asText()).isEqualTo("2026-11-16");
        assertThat(p.path("startsAt").asText()).isEqualTo("2026-11-16T14:30:00+09:00");   // UTC 05:30 → KST
        assertThat(p.path("endsAt").asText()).isEqualTo("2026-11-16T16:25:00+09:00");
        assertThat(p.path("totalSeats").asInt()).isEqualTo(4);
        assertThat(p.has("remainingSeats")).isFalse();   // 잔여 좌석은 booking 값이라 넣지 않는다

        // 새벽 회차: UTC 날짜(11/15)가 영업일(11/16)과 다르다. film_format NULL도 키는 있어야 한다
        JsonNode dawn = onlyMessage("screening.changed", 5002).path("payload");
        assertThat(dawn.path("startsAt").asText()).isEqualTo("2026-11-16T00:30:00+09:00");
        assertThat(dawn.path("businessDate").asText()).isEqualTo("2026-11-16");
        assertThat(dawn.has("filmFormat")).isTrue();
        assertThat(dawn.path("filmFormat").isNull()).isTrue();

        JsonNode movie = onlyMessage("movie.changed", 1);
        assertContractKeys(movie, "movie.changed.v1.json");
        JsonNode m = movie.path("payload");
        assertThat(m.path("viewGrade").asText()).isEqualTo("12");
        assertThat(m.path("status").asText()).isEqualTo("NOW_SHOWING");
        assertThat(m.path("cast").toString()).isEqualTo("[\"배우A\",\"배우B\"]");
        assertThat(m.path("genres").toString()).isEqualTo("[\"드라마\",\"SF\"]");
        assertThat(m.path("synopsis").asText()).isEqualTo("줄거리");
        assertThat(m.path("releaseDate").asText()).isEqualTo("2026-10-01");
        assertThat(m.has("posterUrl")).isFalse();   // 롯데·TMDB 포스터는 넣지 않는다

        JsonNode bare = onlyMessage("movie.changed", 2);
        assertContractKeys(bare, "movie.changed.v1.json");   // TMDB 보충 전 — null이어도 키는 있다
        assertThat(bare.path("payload").path("viewGrade").asText()).isEqualTo("ALL");
        assertThat(bare.path("payload").path("cast").isNull()).isTrue();

        // 바뀐 것이 없으면 다음 주기는 아무것도 쓰지 않는다
        CatalogChangeScanner.Result again = scanner.scanOnce();
        assertThat(again.upserts() + again.deletes() + again.skipped()).isZero();
        assertThat(outboxCount()).isEqualTo(4);
    }

    @Test
    void eachChangeAddsOneRowWithNextVersionAndDeletionKeepsTheLastVersion() throws Exception {
        scanner.scanOnce();

        // TMDB 보충처럼 SQL로 영화 한 편을 고친다 → 그 영화만 version 2
        jdbc.update("UPDATE movie SET title = '첫 영화 (재개봉)' WHERE movie_id = 1");
        CatalogChangeScanner.Result changed = scanner.scanOnce();
        assertThat(changed.upserts()).isEqualTo(1);
        List<JsonNode> movieMessages = messages("movie.changed", 1);
        assertThat(movieMessages).hasSize(2);
        assertThat(movieMessages.get(1).path("payload").path("version").asLong()).isEqualTo(2);
        assertThat(movieMessages.get(1).path("payload").path("title").asText()).isEqualTo("첫 영화 (재개봉)");
        assertThat(outboxRepository.findByEventTypeAndAggregateIdOrderByOutboxIdAsc("movie.changed", 1L))
            .extracting(CatalogOutbox::getAggregateKey).containsExactly("1", "2");

        // 극장 이름이 바뀌면 그 극장 회차가 모두 다시 나간다(공개 필드 전체를 보내므로)
        jdbc.update("UPDATE theater SET name = '새이름극장' WHERE theater_id = 1");
        assertThat(scanner.scanOnce().upserts()).isEqualTo(2);

        // 시드 재적재처럼 회차를 지운다 → deleted=true, version 3, 장부 행은 남는다
        jdbc.update("DELETE FROM screening WHERE screening_id = 5002");
        CatalogChangeScanner.Result deleted = scanner.scanOnce();
        assertThat(deleted.deletes()).isEqualTo(1);
        JsonNode tombstone = last(messages("screening.changed", 5002));
        assertContractKeys(tombstone, "screening.changed.v1.json");
        assertThat(tombstone.path("payload").path("deleted").asBoolean()).isTrue();
        assertThat(tombstone.path("payload").path("version").asLong()).isEqualTo(3);
        assertThat(tombstone.path("payload").has("theaterName")).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM change_ledger WHERE aggregate_type = 'SCREENING' "
            + "AND aggregate_id = 5002 AND version = 3 AND deleted = 1 AND content_hash IS NULL", Integer.class))
            .isEqualTo(1);

        // 지운 회차는 다음 주기에 또 나가지 않는다
        assertThat(scanner.scanOnce().deletes()).isZero();

        // 같은 ID가 다시 생기면 더 큰 version으로(계약 — 삭제 v3 뒤의 v2가 되살리지 못하게)
        jdbc.update("INSERT INTO screening (screening_id, auditorium_id, layout_version, movie_id, business_date, play_sequence, "
            + "starts_at, ends_at, raw_start_time, raw_end_time, film_format) VALUES "
            + "(5002, 10, 1, 2, '2026-11-16', 3, '2026-11-15 15:30:00.000', '2026-11-15 17:10:00.000', '00:30', '02:10', NULL)");
        assertThat(scanner.scanOnce().upserts()).isEqualTo(1);
        JsonNode reborn = last(messages("screening.changed", 5002)).path("payload");
        assertThat(reborn.path("version").asLong()).isEqualTo(4);
        assertThat(reborn.path("deleted").asBoolean()).isFalse();
    }

    @Test
    void staleWriterDoesNotWriteTheSameChangeTwice() {
        scanner.scanOnce();
        jdbc.update("UPDATE movie SET title = '바뀐 제목' WHERE movie_id = 2");
        Snapshot snapshot = reader.current(ChangeType.MOVIE).get(2L);
        Instant now = Instant.parse("2026-11-25T06:00:00Z");

        // 두 파드가 같은 장부(version 1)를 보고 같은 변경을 쓰려 한다 → 한 곳만 기록
        assertThat(writer.upsert(ChangeType.MOVIE, snapshot, 1L, now, "0".repeat(32))).isTrue();
        assertThat(writer.upsert(ChangeType.MOVIE, snapshot, 1L, now, "0".repeat(32))).isFalse();
        // 장부에 없던 원본을 두 곳이 동시에 처음 쓰는 경우도 PK가 막는다
        jdbc.update("DELETE FROM change_ledger WHERE aggregate_type = 'MOVIE' AND aggregate_id = 1");
        Snapshot first = reader.current(ChangeType.MOVIE).get(1L);
        jdbc.update("INSERT INTO change_ledger (aggregate_type, aggregate_id, version, content_hash, deleted, changed_at) "
            + "VALUES ('MOVIE', 1, 1, 'x', 0, '2026-11-25 06:00:00.000')");
        assertThat(writer.upsert(ChangeType.MOVIE, first, null, now, "0".repeat(32))).isFalse();

        assertThat(outboxRepository.findByEventTypeAndAggregateIdOrderByOutboxIdAsc("movie.changed", 2L))
            .extracting(CatalogOutbox::getAggregateKey).containsExactly("1", "2");
    }

    @Test
    void scanIsSkippedWhileAnotherPodHoldsTheLock() throws Exception {
        try (Connection other = dataSource.getConnection()) {
            try (PreparedStatement lock = other.prepareStatement("SELECT GET_LOCK(?, 0)")) {
                lock.setString(1, CatalogChangeScanner.LOCK_NAME);
                try (ResultSet rs = lock.executeQuery()) {
                    assertThat(rs.next() && rs.getInt(1) == 1).isTrue();
                }
            }
            assertThat(scanner.scanOnce().ran()).isFalse();
            assertThat(outboxCount()).isZero();
            try (PreparedStatement unlock = other.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                unlock.setString(1, CatalogChangeScanner.LOCK_NAME);
                unlock.execute();
            }
        }
        assertThat(scanner.scanOnce().upserts()).isEqualTo(4);
    }

    @Test
    void relaySendsPendingRowsAndRetriesOnFailure() {
        scanner.scanOnce();

        doThrow(new IllegalStateException("SNS 없음")).when(publisher).publish(any());
        assertThat(relay.relayOnce()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_message WHERE status = 'PENDING' "
            + "AND attempt_count = 1 AND last_error LIKE 'IllegalStateException%'", Integer.class)).isEqualTo(4);

        reset(publisher);
        jdbc.update("UPDATE outbox_message SET next_attempt_at = '2000-01-01 00:00:00.000'");   // 재시도 시각을 당긴다
        assertThat(relay.relayOnce()).isEqualTo(4);
        verify(publisher, times(4)).publish(any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_message WHERE status = 'SENT'", Integer.class))
            .isEqualTo(4);
        assertThat(outboxRepository.countUnsent()).isZero();
    }

    // --- helpers ---

    private int outboxCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_message", Integer.class);
    }

    private JsonNode onlyMessage(String type, long id) throws Exception {
        List<JsonNode> found = messages(type, id);
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private List<JsonNode> messages(String type, long id) throws Exception {
        List<JsonNode> nodes = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
            "SELECT payload, aggregate_key, message_key FROM outbox_message WHERE event_type = ? AND aggregate_id = ? "
                + "ORDER BY outbox_id", type, id)) {
            JsonNode node = JSON.readTree((String) row.get("payload"));
            // outbox 열과 envelope이 맞는지 — eventId = message_key, aggregate_key = payload.version
            assertThat(node.path("eventId").asText()).isEqualTo(row.get("message_key"));
            assertThat(node.path("payload").path("version").asText()).isEqualTo(row.get("aggregate_key"));
            assertThat(node.path("type").asText()).isEqualTo(type);
            nodes.add(node);
        }
        return nodes;
    }

    private static JsonNode last(List<JsonNode> nodes) {
        return nodes.get(nodes.size() - 1);
    }

    /** 계약의 envelope required + payload required(+ deleted=false면 then.required) 키가 모두 있는지 */
    private static void assertContractKeys(JsonNode message, String schemaFile) throws Exception {
        JsonNode schema = JSON.readTree(Files.readString(CONTRACTS.resolve(schemaFile)));
        schema.path("required").forEach(key -> assertThat(message.has(key.asText())).as(key.asText()).isTrue());
        JsonNode payloadSchema = schema.path("properties").path("payload");
        JsonNode payload = message.path("payload");
        payloadSchema.path("required").forEach(key -> assertThat(payload.has(key.asText())).as(key.asText()).isTrue());
        if (!payload.path("deleted").asBoolean()) {
            payloadSchema.path("then").path("required")
                .forEach(key -> assertThat(payload.has(key.asText())).as(key.asText()).isTrue());
        }
        // 계약에 없는 필드를 내보내지 않는다(포스터 등)
        List<String> allowed = new ArrayList<>();
        payloadSchema.path("properties").fieldNames().forEachRemaining(allowed::add);
        payload.fieldNames().forEachRemaining(name -> assertThat(allowed).as("payload." + name).contains(name));
    }
}

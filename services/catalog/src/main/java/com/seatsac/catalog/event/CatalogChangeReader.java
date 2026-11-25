package com.seatsac.catalog.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.catalog.dto.MovieStatus;
import com.seatsac.catalog.dto.ScreenType;
import com.seatsac.catalog.dto.ViewGrade;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 원본(회차·영화)의 지금 공개 필드와 장부({@code change_ledger})를 읽는다. 엔티티가 아니라 JDBC로 읽는 이유: 회차 8천여 건을
 * 1분마다 보는데 JPA로 읽으면 연관(상영관·극장)을 건마다 끌어오고 1차 캐시에 다 쌓인다. 조인 한 번으로 필요한 열만 읽는다.
 *
 * <p>공개 필드는 계약 payload 그대로다 — 포스터(롯데 이미지)·시드 원본 열·잔여 좌석·배치도는 넣지 않는다.
 * 시각은 DB의 UTC를 +09:00 문자열로, 날짜는 {@code YYYY-MM-DD} 문자열로 바꿔 둔다 — 해시가 직렬화 방식에 흔들리지 않게.
 */
@Component
public class CatalogChangeReader {

    /** 계약 date-time — 초까지 항상 쓰고 오프셋은 +09:00 (규약 6장) */
    private static final DateTimeFormatter API_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private static final String SCREENINGS = """
        SELECT s.screening_id, s.movie_id, a.theater_id, t.name AS theater_name, s.auditorium_id,
               a.name AS auditorium_name, a.screen_type_code, s.layout_version, s.business_date,
               s.starts_at, s.ends_at, s.film_format, a.total_seats
          FROM screening s
          JOIN auditorium a ON a.auditorium_id = s.auditorium_id
          JOIN theater t ON t.theater_id = a.theater_id
        """;

    private static final String MOVIES = """
        SELECT movie_id, title, title_en, view_grade_code, slot_minutes, runtime_minutes, release_date,
               director, cast_names, genres, overview
          FROM movie
        """;

    /**
     * 앱 공용 ObjectMapper를 쓰지 않는다 — 값은 문자열·숫자·목록뿐이고, 공용 설정(null 생략 등)이 바뀌면 해시와 payload 모양
     * (null 필드도 반드시 있어야 한다 — 계약 then.required)이 함께 바뀌기 때문이다.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Logger log = LoggerFactory.getLogger(CatalogChangeReader.class);

    /** 깨진 JSON 경고를 이미 남긴 (movieId:열) — 파드 수명 동안만 */
    private final Set<String> brokenJsonLogged = ConcurrentHashMap.newKeySet();

    private final JdbcTemplate jdbc;

    public CatalogChangeReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 원본 한 행의 공개 필드(payload에서 ID·version·deleted·changedAt을 뺀 나머지)와 그 해시 */
    public record Snapshot(long id, Map<String, Object> fields, String hash) {
    }

    /** 장부 한 행 — 마지막으로 내보낸 version·해시·삭제 여부 */
    public record LedgerEntry(long version, String hash, boolean deleted) {
    }

    public Map<Long, Snapshot> current(ChangeType type) {
        return switch (type) {
            case SCREENING -> query(SCREENINGS, this::screening);
            case MOVIE -> query(MOVIES, this::movie);
        };
    }

    public Map<Long, LedgerEntry> ledger(ChangeType type) {
        Map<Long, LedgerEntry> entries = new HashMap<>();
        jdbc.query("SELECT aggregate_id, version, content_hash, deleted FROM change_ledger WHERE aggregate_type = ?",
            rs -> {
                entries.put(rs.getLong("aggregate_id"), new LedgerEntry(rs.getLong("version"),
                    rs.getString("content_hash"), rs.getBoolean("deleted")));
            }, type.name());
        return entries;
    }

    private Map<Long, Snapshot> query(String sql, RowReader reader) {
        Map<Long, Snapshot> rows = new HashMap<>();
        jdbc.query(sql, rs -> {
            Snapshot snapshot = reader.read(rs);
            rows.put(snapshot.id(), snapshot);
        });
        return rows;
    }

    private Snapshot screening(ResultSet rs) throws SQLException {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("movieId", rs.getLong("movie_id"));
        f.put("theaterId", rs.getLong("theater_id"));
        f.put("theaterName", rs.getString("theater_name"));
        f.put("auditoriumId", rs.getLong("auditorium_id"));
        f.put("auditoriumName", rs.getString("auditorium_name"));
        f.put("screenType", ScreenType.fromCode(nullableInt(rs, "screen_type_code")).name());
        f.put("layoutVersion", rs.getInt("layout_version"));
        f.put("businessDate", date(rs.getObject("business_date", LocalDate.class)));
        f.put("startsAt", apiTime(rs.getObject("starts_at", LocalDateTime.class)));
        f.put("endsAt", apiTime(rs.getObject("ends_at", LocalDateTime.class)));
        f.put("filmFormat", rs.getString("film_format"));
        f.put("totalSeats", rs.getInt("total_seats"));
        return snapshot(rs.getLong("screening_id"), f);
    }

    private Snapshot movie(ResultSet rs) throws SQLException {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("title", rs.getString("title"));
        f.put("titleEn", rs.getString("title_en"));
        f.put("viewGrade", ViewGrade.fromCode(nullableInt(rs, "view_grade_code")).json());
        // 시드에 개봉 예정작이 없다 — 목록 API와 같이 전부 NOW_SHOWING (MovieStatus)
        f.put("status", MovieStatus.NOW_SHOWING.name());
        f.put("slotMinutes", nullableInt(rs, "slot_minutes"));
        f.put("runtimeMinutes", nullableInt(rs, "runtime_minutes"));
        f.put("releaseDate", date(rs.getObject("release_date", LocalDate.class)));
        f.put("director", rs.getString("director"));
        long movieId = rs.getLong("movie_id");
        f.put("cast", stringList(movieId, "cast_names", rs.getString("cast_names")));
        f.put("genres", stringList(movieId, "genres", rs.getString("genres")));
        f.put("synopsis", rs.getString("overview"));
        return snapshot(movieId, f);
    }

    private Snapshot snapshot(long id, Map<String, Object> fields) {
        return new Snapshot(id, fields, sha256(json(fields)));
    }

    String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("변경 이벤트 직렬화 실패", e);
        }
    }

    private List<String> stringList(long movieId, String column, String json) {
        if (json == null) {
            return null;
        }
        try {
            return JSON.readValue(json, STRING_LIST);
        } catch (JsonProcessingException e) {
            // 시드 JSON 열이 깨졌으면 그 필드만 비운다 — 한 영화 때문에 스캔 전체가 멈추지 않게.
            // 비운 값(null)이 그대로 나가므로 원인을 남긴다. 스캔이 1분마다 같은 행을 읽으니 (영화, 열)마다 한 번만
            if (brokenJsonLogged.add(movieId + ":" + column)) {
                log.warn("movie JSON 열을 읽지 못해 null로 보낸다 movieId={} column={} 원인={}", movieId, column,
                    e.getOriginalMessage());
            }
            return null;
        }
    }

    /** DB DATETIME(3)은 UTC 값이다(규약 6장). 세션 시간대와 무관하게 리터럴을 그대로 읽어 UTC로 본다 */
    static String apiTime(LocalDateTime utc) {
        if (utc == null) {
            return null;
        }
        Instant instant = utc.toInstant(ZoneOffset.UTC);
        return API_TIME.format(instant.atOffset(KST));
    }

    private static String date(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    private interface RowReader {
        Snapshot read(ResultSet rs) throws SQLException;
    }
}

package com.seatsac.user.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.user.client.BookingClient.BookingItem;
import com.seatsac.user.dto.BookingHistoryStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code user_db.booking_history}·{@code booking_history_sync}·{@code processed_message} 읽기·쓰기(6.3.4b, API 명세서 v1.5 23-1).
 *
 * <p>쓰기는 한 문장 upsert라 이벤트 소비와 첫 조회 채우기가 동시에 같은 예매를 써도 규칙이 깨지지 않는다:
 * <ul>
 *   <li><b>CANCELED가 이긴다</b> — 이미 CANCELED면 CONFIRMED로 되돌리지 않는다(순서가 바뀌어 늦게 온 confirmed)</li>
 *   <li>상세(좌석·금액·시각)는 새 값이 있을 때만 덮는다 — canceled 이벤트는 상세가 없어 confirmed가 채운 값을 지우지 않는다</li>
 *   <li>{@code canceled_at}은 처음 값을 지킨다</li>
 * </ul>
 * 시각은 UTC로 저장한다(데이터 모델 1장).
 */
@Repository
public class BookingHistoryStore {

    private static final String UPSERT = """
        INSERT INTO booking_history (booking_id, user_id, status, screening_id, seat_labels, seat_count, amount,
            discount_amount, created_at, confirmed_at, canceled_at, updated_at)
        VALUES (:bookingId, :userId, :status, :screeningId, :seatLabels, :seatCount, :amount,
            :discountAmount, :createdAt, :confirmedAt, :canceledAt, :now) AS incoming
        ON DUPLICATE KEY UPDATE
            status = IF(booking_history.status = 'CANCELED', 'CANCELED', incoming.status),
            screening_id = incoming.screening_id,
            seat_labels = COALESCE(incoming.seat_labels, booking_history.seat_labels),
            seat_count = COALESCE(incoming.seat_count, booking_history.seat_count),
            amount = COALESCE(incoming.amount, booking_history.amount),
            discount_amount = COALESCE(incoming.discount_amount, booking_history.discount_amount),
            created_at = COALESCE(booking_history.created_at, incoming.created_at),
            confirmed_at = COALESCE(incoming.confirmed_at, booking_history.confirmed_at),
            canceled_at = COALESCE(booking_history.canceled_at, incoming.canceled_at),
            updated_at = incoming.updated_at
        """;

    /** 확정 내용을 아직 받지 못한 행(canceled가 먼저 와서 만든 행)은 목록에 내지 않는다 */
    private static final String VISIBLE = "user_id = :userId AND confirmed_at IS NOT NULL";

    private static final TypeReference<List<String>> LABELS = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public BookingHistoryStore(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 한 행의 새 값 — null 칸은 「모름」이라 기존 값을 지킨다(위 규칙) */
    public record Row(
        long bookingId,
        String userId,
        BookingHistoryStatus status,
        long screeningId,
        List<String> seatLabels,
        Integer seatCount,
        Long amount,
        Long discountAmount,
        Instant createdAt,
        Instant confirmedAt,
        Instant canceledAt
    ) {
    }

    public void upsert(Row row, Instant now) {
        jdbc.update(UPSERT, new MapSqlParameterSource()
            .addValue("bookingId", row.bookingId())
            .addValue("userId", row.userId())
            .addValue("status", row.status().name())
            .addValue("screeningId", row.screeningId())
            .addValue("seatLabels", row.seatLabels() == null ? null : json(row.seatLabels()))
            .addValue("seatCount", row.seatCount())
            .addValue("amount", row.amount())
            .addValue("discountAmount", row.discountAmount())
            .addValue("createdAt", ts(row.createdAt()))
            .addValue("confirmedAt", ts(row.confirmedAt()))
            .addValue("canceledAt", ts(row.canceledAt()))
            .addValue("now", ts(now)));
    }

    /** inbox — 처음 받은 eventId면 true. 같은 트랜잭션의 반영과 함께 커밋된다 */
    public boolean markProcessed(String messageId, String messageType, Instant now) {
        return jdbc.update("INSERT IGNORE INTO processed_message (message_id, message_type, received_at) "
            + "VALUES (:id, :type, :now)", new MapSqlParameterSource()
            .addValue("id", messageId).addValue("type", messageType).addValue("now", ts(now))) == 1;
    }

    public boolean isSynced(String userId) {
        Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM booking_history_sync WHERE user_id = :userId",
            new MapSqlParameterSource("userId", userId), Integer.class);
        return found != null && found > 0;
    }

    /** 동시에 두 요청이 채워도 기록은 하나 — 둘째는 아무것도 하지 않는다 */
    public void markSynced(String userId, Instant now) {
        jdbc.update("INSERT IGNORE INTO booking_history_sync (user_id, synced_at) VALUES (:userId, :now)",
            new MapSqlParameterSource().addValue("userId", userId).addValue("now", ts(now)));
    }

    /**
     * @param status null이면 CONFIRMED·CANCELED 전부. PAYMENT_FAILED·EXPIRED는 복제본에 없다(확정된 적 없는 예매는 내역 대상이 아니다)
     */
    public long count(String userId, BookingHistoryStatus status) {
        if (notStored(status)) {
            return 0;
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM booking_history WHERE " + VISIBLE + statusFilter(status),
            params(userId, status), Long.class);
        return total == null ? 0 : total;
    }

    /** {@code confirmed_at} 내림차순(같으면 bookingId 내림차순) — 23-1 정렬 */
    public List<BookingItem> page(String userId, BookingHistoryStatus status, int page, int size) {
        if (notStored(status)) {
            return List.of();
        }
        MapSqlParameterSource params = params(userId, status)
            .addValue("limit", size)
            .addValue("offset", (long) page * size);
        return jdbc.query("SELECT booking_id, status, screening_id, seat_labels, seat_count, amount, created_at, "
                + "confirmed_at, canceled_at FROM booking_history WHERE " + VISIBLE + statusFilter(status)
                + " ORDER BY confirmed_at DESC, booking_id DESC LIMIT :limit OFFSET :offset",
            params, this::item);
    }

    private BookingItem item(ResultSet rs, int rowNum) throws SQLException {
        List<String> labels = labels(rs.getString("seat_labels"));
        Integer seatCount = (Integer) rs.getObject("seat_count", Integer.class);
        if (seatCount == null) {
            seatCount = labels.size();
        }
        Long amount = rs.getObject("amount", Long.class);
        if (amount == null) {
            amount = seatCount * 15_000L; // 이전 메시지(금액 없음) — conventions 6장 1석 15,000원
        }
        OffsetDateTime confirmedAt = odt(rs.getTimestamp("confirmed_at"));
        OffsetDateTime createdAt = odt(rs.getTimestamp("created_at"));
        return new BookingItem(rs.getLong("booking_id"), rs.getString("status"), rs.getLong("screening_id"), labels,
            seatCount, amount, createdAt != null ? createdAt : confirmedAt, confirmedAt,
            odt(rs.getTimestamp("canceled_at")));
    }

    private static boolean notStored(BookingHistoryStatus status) {
        return status == BookingHistoryStatus.PAYMENT_FAILED || status == BookingHistoryStatus.EXPIRED;
    }

    private static String statusFilter(BookingHistoryStatus status) {
        return status == null ? "" : " AND status = :status";
    }

    private static MapSqlParameterSource params(String userId, BookingHistoryStatus status) {
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId);
        if (status != null) {
            params.addValue("status", status.name());
        }
        return params;
    }

    private String json(List<String> labels) {
        try {
            return objectMapper.writeValueAsString(labels);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> labels(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, LABELS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("booking_history.seat_labels가 JSON 배열이 아니다", e);
        }
    }

    private static Timestamp ts(Instant at) {
        return at == null ? null : Timestamp.from(at);
    }

    /** 드라이버·세션이 UTC(JDBC URL connectionTimeZone=UTC)라 Timestamp의 순간 그대로 */
    private static OffsetDateTime odt(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }
}

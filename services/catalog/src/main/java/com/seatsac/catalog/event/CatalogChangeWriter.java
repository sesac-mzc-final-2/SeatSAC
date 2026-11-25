package com.seatsac.catalog.event;

import com.seatsac.catalog.event.CatalogChangeReader.Snapshot;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 변경 1건 = <b>장부 version + 1과 outbox 행 1개를 같은 트랜잭션에</b>(data-model 7-2 「변경 1번 = 새 행 1개」).
 *
 * <p>장부 갱신은 조건부다 — 새 원본은 INSERT(PK 충돌이면 다른 파드가 먼저 씀), 있던 원본은 {@code WHERE version = :expected}.
 * 0행이면 다른 파드가 이미 이 변경을 기록했으므로 아무것도 하지 않는다(outbox 유일 키 (event_type, aggregate_id, version)이
 * 2차 방어선). 그래서 스캔이 두 파드에서 겹쳐도 같은 변경이 두 번 나가지 않는다.
 */
@Component
public class CatalogChangeWriter {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final DateTimeFormatter API_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CatalogChangeReader reader;

    public CatalogChangeWriter(JdbcTemplate jdbc, TransactionTemplate tx, CatalogChangeReader reader) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.reader = reader;
    }

    /**
     * 생기거나 바뀐 원본. {@code expectedVersion}이 null이면 장부에 없던 원본(version 1).
     *
     * @return 이번에 기록했으면 true, 다른 파드가 먼저 기록했으면 false
     */
    public boolean upsert(ChangeType type, Snapshot snapshot, Long expectedVersion, Instant now, String traceId) {
        long version = expectedVersion == null ? 1 : expectedVersion + 1;
        Map<String, Object> payload = head(type, snapshot.id(), version, false, now);
        payload.putAll(snapshot.fields());
        return write(type, snapshot.id(), version, expectedVersion, snapshot.hash(), false, payload, now, traceId);
    }

    /** 장부에는 살아 있는데 원본에서 없어진 행. payload는 ID·version·deleted·changedAt만(계약) */
    public boolean delete(ChangeType type, long id, long expectedVersion, Instant now, String traceId) {
        long version = expectedVersion + 1;
        Map<String, Object> payload = head(type, id, version, true, now);
        return write(type, id, version, expectedVersion, null, true, payload, now, traceId);
    }

    private boolean write(ChangeType type, long id, long version, Long expectedVersion, String hash, boolean deleted,
                          Map<String, Object> payload, Instant now, String traceId) {
        String eventId = UUID.randomUUID().toString();
        String envelope = reader.json(envelope(type, eventId, now, traceId, payload));
        LocalDateTime at = LocalDateTime.ofInstant(now, ZoneOffset.UTC);   // DATETIME(3) UTC 리터럴
        Boolean written = tx.execute(status -> {
            try {
                int changed = expectedVersion == null
                    ? jdbc.update("""
                        INSERT INTO change_ledger (aggregate_type, aggregate_id, version, content_hash, deleted, changed_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, type.name(), id, version, hash, deleted, at)
                    : jdbc.update("""
                        UPDATE change_ledger
                           SET version = ?, content_hash = ?, deleted = ?, changed_at = ?
                         WHERE aggregate_type = ? AND aggregate_id = ? AND version = ?
                        """, version, hash, deleted, at, type.name(), id, expectedVersion);
                if (changed == 0) {
                    return false;
                }
                jdbc.update("""
                    INSERT INTO outbox_message (message_key, event_type, aggregate_id, aggregate_key, payload, trace_id,
                                                status, attempt_count, next_attempt_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
                    """, eventId, type.eventType(), id, Long.toString(version), envelope, traceId, at, at, at);
                return true;
            } catch (DuplicateKeyException e) {
                // 장부 PK 또는 outbox (event_type, aggregate_id, aggregate_key) — 다른 파드가 같은 변경을 먼저 썼다
                status.setRollbackOnly();
                return false;
            }
        });
        return Boolean.TRUE.equals(written);
    }

    private static Map<String, Object> head(ChangeType type, long id, long version, boolean deleted, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(type.idField(), id);
        payload.put("version", version);
        payload.put("deleted", deleted);
        payload.put("changedAt", apiTime(now));
        return payload;
    }

    /** 공통 envelope (이름 규칙 5장) — catalog 이벤트는 saga가 아니라 correlationId는 null */
    private static Map<String, Object> envelope(ChangeType type, String eventId, Instant now, String traceId,
                                                Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("type", type.eventType());
        envelope.put("version", 1);
        envelope.put("occurredAt", apiTime(now));
        envelope.put("producer", "catalog");
        envelope.put("traceId", traceId);
        envelope.put("correlationId", null);
        envelope.put("payload", payload);
        return envelope;
    }

    private static String apiTime(Instant instant) {
        return API_TIME.format(instant.atOffset(KST));
    }
}

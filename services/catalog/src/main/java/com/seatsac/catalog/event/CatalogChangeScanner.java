package com.seatsac.catalog.event;

import com.seatsac.catalog.event.CatalogChangeReader.LedgerEntry;
import com.seatsac.catalog.event.CatalogChangeReader.Snapshot;
import com.seatsac.common.core.trace.TraceIds;
import com.seatsac.common.core.trace.TraceKeys;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 변경 찾기 — 원본(회차·영화)의 지금 공개 필드와 장부({@code change_ledger})를 비교해 생김·바뀜·없어짐마다 이벤트를 남긴다
 * (6.3.4a, 계약 {@code screening.changed}·{@code movie.changed}).
 *
 * <p>catalog에는 쓰기 API가 없다 — 회차·영화는 lotte-seed 적재(재적재 포함)·TMDB 보충 SQL로만 바뀐다. 그 쓰기와 같은
 * 트랜잭션에 outbox를 넣을 수 없으므로 앱이 주기적으로 차이를 찾는다. 차이를 찾은 순간 「장부 + outbox」는 한 트랜잭션이라
 * 이벤트를 잃지 않고, 스캔 사이에 여러 번 바뀌면 마지막 상태 하나로 나간다(받는 쪽은 행을 통째로 바꾸므로 같은 결과).
 *
 * <ul>
 *   <li>장부에 없음 → version 1, {@code deleted=false} — 처음 켠 날에는 회차·영화 전부가 한 번씩 나간다(받는 쪽 백필과 같은 값)</li>
 *   <li>해시가 다름, 또는 삭제됐던 ID가 다시 생김 → version + 1</li>
 *   <li>장부에는 살아 있는데 원본에 없음 → version + 1, {@code deleted=true}. 장부 행은 남아 마지막 version을 지킨다</li>
 * </ul>
 *
 * <p>파드 여럿이 동시에 돌지 않게 MySQL 이름 잠금({@code GET_LOCK}, 기다리지 않음)을 건다. 잠금은 연결에 묶여 있어 파드가
 * 죽으면 저절로 풀린다. 잠금이 없어도 장부 조건부 갱신이 중복을 막는다({@link CatalogChangeWriter}) — 잠금은 헛일을 줄일 뿐이다.
 */
@Component
public class CatalogChangeScanner {

    private static final Logger log = LoggerFactory.getLogger(CatalogChangeScanner.class);
    static final String LOCK_NAME = "catalog_change_scan";

    private final JdbcTemplate jdbc;
    private final CatalogChangeReader reader;
    private final CatalogChangeWriter writer;
    private final Clock clock;

    public CatalogChangeScanner(JdbcTemplate jdbc, CatalogChangeReader reader, CatalogChangeWriter writer,
                                Clock clock) {
        this.jdbc = jdbc;
        this.reader = reader;
        this.writer = writer;
        this.clock = clock;
    }

    /** 한 바퀴의 결과 — 테스트·로그 */
    public record Result(boolean ran, int upserts, int deletes, int skipped) {
        static final Result NOT_RUN = new Result(false, 0, 0, 0);

        Result plus(Result other) {
            return new Result(true, upserts + other.upserts, deletes + other.deletes, skipped + other.skipped);
        }
    }

    /**
     * 한 바퀴. 예외를 밖으로 내지 않는다 — 스케줄러 스레드로 나가면 주기가 멈추고, 부르는 쪽 로그는 traceId가 비어 있다
     * (outbox 릴레이와 같은 이유).
     */
    public Result scanOnce() {
        String traceId = TraceIds.newId();
        MDC.put(TraceKeys.MDC_TRACE_ID, traceId);
        try {
            Result result = jdbc.execute((ConnectionCallback<Result>) connection -> {
                if (!lock(connection)) {
                    return Result.NOT_RUN;   // 다른 파드가 스캔 중
                }
                try {
                    return scan(traceId);
                } finally {
                    unlock(connection);
                }
            });
            if (result != null && (result.upserts() > 0 || result.deletes() > 0 || result.skipped() > 0)) {
                log.info("catalog 변경 찾기 생김·바뀜={} 없어짐={} 다른파드가먼저={}", result.upserts(), result.deletes(),
                    result.skipped());
            }
            return result == null ? Result.NOT_RUN : result;
        } catch (RuntimeException e) {
            log.warn("catalog 변경 찾기 실패 — 다음 주기에 다시 본다: {}", e.getMessage(), e);
            return Result.NOT_RUN;
        } finally {
            MDC.remove(TraceKeys.MDC_TRACE_ID);
        }
    }

    private Result scan(String traceId) {
        // 영화를 먼저 — 받는 쪽이 회차의 movieId를 영화 복제본에서 찾을 때 영화가 먼저 와 있는 편이 낫다(순서 보장은 아님)
        Result total = new Result(true, 0, 0, 0);
        for (ChangeType type : new ChangeType[] {ChangeType.MOVIE, ChangeType.SCREENING}) {
            total = total.plus(scan(type, traceId));
        }
        return total;
    }

    private Result scan(ChangeType type, String traceId) {
        Map<Long, Snapshot> current = reader.current(type);
        Map<Long, LedgerEntry> ledger = reader.ledger(type);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);   // DATETIME(3)
        int upserts = 0;
        int deletes = 0;
        int skipped = 0;

        for (Snapshot snapshot : current.values()) {
            LedgerEntry entry = ledger.get(snapshot.id());
            if (entry != null && !entry.deleted() && snapshot.hash().equals(entry.hash())) {
                continue;   // 그대로 — 대부분의 주기는 여기서 끝난다
            }
            Long expected = entry == null ? null : entry.version();
            if (writer.upsert(type, snapshot, expected, now, traceId)) {
                upserts++;
            } else {
                skipped++;
            }
        }
        for (Map.Entry<Long, LedgerEntry> e : ledger.entrySet()) {
            LedgerEntry entry = e.getValue();
            if (entry.deleted() || current.containsKey(e.getKey())) {
                continue;
            }
            if (writer.delete(type, e.getKey(), entry.version(), now, traceId)) {
                deletes++;
            } else {
                skipped++;
            }
        }
        return new Result(true, upserts, deletes, skipped);
    }

    private static boolean lock(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
            statement.setString(1, LOCK_NAME);
            try (var rs = statement.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        }
    }

    private static void unlock(Connection connection) {
        try (var statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, LOCK_NAME);
            statement.execute();
        } catch (SQLException e) {
            // 잠금이 이 연결(세션)에 남는다. Hikari가 연결을 닫을 때(최대 수명 30분) 풀리고, 그동안 다른 파드는 스캔을 건너뛴다
            log.warn("변경 찾기 잠금 해제 실패: {}", e.getMessage());
        }
    }
}

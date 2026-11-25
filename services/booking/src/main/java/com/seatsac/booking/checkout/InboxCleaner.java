package com.seatsac.booking.checkout;

import com.seatsac.common.core.trace.TraceIds;
import com.seatsac.common.core.trace.TraceKeys;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결과 inbox({@code processed_message}) 정리 — 받은 지 7일이 지난 행을 지운다(설계 04 4장, payment #191과 같은 방식).
 *
 * <p>DLQ 보존(14일)보다 짧아 7일 뒤 DLQ에서 다시 보낸 결과는 한 번 더 처리되지만, saga 전이가 모두 기대 상태를 건
 * 조건부 UPDATE라 0행으로 끝난다.
 *
 * <p>하루 1회 <b>15:00(KST)</b> — 노드가 켜져 있는 시간(평일 08:23~23:07, ADR-34) 안이라 빠지지 않는다. 노드가 꺼진 날은
 * 다음 실행에서 지운다. 여러 파드(블루/그린 preview 포함)가 같은 시각에 돌아도 같은 행을 지울 뿐이다. 만료 스캔과 같은
 * 스위치({@code app.expiry.enabled}, 테스트만 끈다)를 따른다 — 테스트 중 15시에 끼어들지 않게.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.expiry", name = "enabled", havingValue = "true", matchIfMissing = true)
public class InboxCleaner {

    static final Duration RETENTION = Duration.ofDays(7);
    static final int BATCH_SIZE = 1_000;
    /** 안전 상한 (1,000 × 50 = 5만 건). 남으면 다음 실행에서 이어서 */
    static final int MAX_ROUNDS = 50;

    private final ProcessedMessageRepository inbox;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Scheduled(cron = "0 0 15 * * *", zone = "Asia/Seoul")
    public void scheduled() {
        MDC.put(TraceKeys.MDC_TRACE_ID, TraceIds.newId());
        try {
            cleanOnce();
        } catch (RuntimeException e) {
            // 파드 여럿이 같은 시각에 지우다 드물게 잠금 충돌로 실패할 수 있다 — 그날은 건너뛰고 다음 실행에서 이어서
            log.warn("결과 inbox 정리 실패 — 다음 실행에서 이어서 지운다: {}", e.toString());
        } finally {
            MDC.remove(TraceKeys.MDC_TRACE_ID);
        }
    }

    /** @return 지운 행 수 */
    public int cleanOnce() {
        Instant before = clock.instant().minus(RETENTION);
        int deleted = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            Integer n = transactions.execute(status -> inbox.deleteReceivedBefore(before, BATCH_SIZE));
            int count = n == null ? 0 : n;
            deleted += count;
            if (count < BATCH_SIZE) {
                break;
            }
        }
        if (deleted > 0) {
            log.info("결과 inbox 정리 — 받은 지 7일이 지난 행을 지웠다 deleted={}", deleted);
        }
        return deleted;
    }
}

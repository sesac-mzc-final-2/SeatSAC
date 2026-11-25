package com.seatsac.payment.saga;

import com.seatsac.common.core.trace.TraceIds;
import com.seatsac.common.core.trace.TraceKeys;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 명령 inbox({@code processed_message}) 정리 — 받은 지 7일이 지난 행을 지운다(설계 04 4장).
 *
 * <p>DLQ 보존(14일)보다 짧아 7일 뒤 DLQ에서 다시 보낸 명령은 한 번 더 처리되지만, 명령 처리가 멱등이라 안전하다(같은 결제 ID면
 * 저장된 상태를 돌려주고, 해제 표시는 첫 시각을 유지하고, 결과가 다시 나가도 booking의 조건부 전이가 0행).
 *
 * <p>하루 1회 <b>15:00(KST)</b> — 노드가 켜져 있는 시간(평일 08:23~23:07, ADR-34) 안이라 빠지지 않는다. 주말처럼 노드가 꺼진
 * 날은 건너뛰고 다음 실행에서 지운다(행이 며칠 더 남을 뿐이다). 여러 파드가 같은 시각에 돌아도 같은 행을 지울 뿐이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InboxCleaner {

    static final Duration RETENTION = Duration.ofDays(7);
    static final int BATCH_SIZE = 1_000;
    /** 안전 상한 (1,000 × 50 = 5만 건). 남으면 다음 주기에 이어서 */
    static final int MAX_ROUNDS = 50;

    private final ProcessedMessageRepository inbox;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Scheduled(cron = "0 0 15 * * *", zone = "Asia/Seoul")
    public void scheduled() {
        MDC.put(TraceKeys.MDC_TRACE_ID, TraceIds.newId());
        try {
            cleanOnce();
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
            log.info("명령 inbox 정리 — 받은 지 7일이 지난 행을 지웠다 deleted={}", deleted);
        }
        return deleted;
    }
}

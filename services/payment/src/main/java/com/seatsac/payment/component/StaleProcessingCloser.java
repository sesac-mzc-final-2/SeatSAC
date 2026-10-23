package com.seatsac.payment.component;

import com.seatsac.payment.common.PaymentPolicy;
import com.seatsac.payment.enums.PaymentFailureCode;
import com.seatsac.payment.enums.PaymentStatus;
import com.seatsac.payment.service.PaymentStateService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * 기동 시 남은 {@code PROCESSING} 정리 — 명세 3·7장.
 *
 * <p>모의 판정 중에 파드가 죽으면 행이 {@code PROCESSING}으로 남는다. 외부 PG가 없어 결과를
 * 다시 물을 곳이 없으므로 {@code FAILED(PROCESSING_TIMEOUT)}으로 닫는다. Booking은 이것을
 * 「결제 실패」로 보고 좌석을 풀면 된다 — 승인되지 않은 결제라 돈이 빠져나간 적이 없다.
 *
 * <p>{@code created_at}이 30초 이전인 행만 본다. <b>방금 들어와 판정 중인(지연 카드 5초) 요청을
 * 건드리지 않기 위한 여유</b>다. 롤링 배포라 새 파드가 뜰 때 이전 파드가 아직 판정 중일 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleProcessingCloser {

    /** 한 번에 읽는 건수 — Hikari 5개를 오래 붙잡지 않는다 */
    private static final int BATCH_SIZE = 100;

    /** 안전 상한 (100 × 50 = 5,000건) */
    private static final int MAX_ROUNDS = 50;

    private final Clock clock;
    private final PaymentStateService paymentState;

    /**
     * 기동 때 {@link StaleProcessingStartupRunner}가 한 번 부른다. 테스트는 이 메서드를 직접 불러
     * 「몇 건을 닫았는지」를 센다.
     *
     * @return 닫은 건수
     */
    public int closeStale() {
        Instant now = clock.instant();
        Instant threshold = now.minus(PaymentPolicy.PROCESSING_TIMEOUT);
        int closed = 0;
        int found = 0;

        // 한 번에 100건씩, 더 없을 때까지 돈다. 조회 한 번으로 끝내면 101건째부터 남는다.
        // 상한을 둬서 (조건부 UPDATE가 계속 0행을 내는) 예상 못 한 상황에 무한 반복하지 않는다
        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<String> stale = paymentState.findStaleProcessingIds(threshold,
                PageRequest.ofSize(BATCH_SIZE));
            if (stale.isEmpty()) {
                break;
            }
            found += stale.size();
            for (String paymentId : stale) {
                // 조건부 UPDATE라 다른 파드가 먼저 닫았으면 0행이다
                if (paymentState.settle(paymentId, PaymentStatus.FAILED,
                    PaymentFailureCode.PROCESSING_TIMEOUT, now)) {
                    closed++;
                }
            }
            if (stale.size() < BATCH_SIZE) {
                break;
            }
        }

        if (found > 0) {
            log.warn("PROCESSING으로 남아 있던 결제를 FAILED(PROCESSING_TIMEOUT)로 닫았다 found={} closed={}",
                found, closed);
        }
        return closed;
    }
}

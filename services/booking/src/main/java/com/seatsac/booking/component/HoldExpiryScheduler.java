package com.seatsac.booking.component;

import com.seatsac.booking.common.BookingPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 만료 스캔 주기 실행 — 안전망 1은 1초, 안전망 2는 10초 (`redis-usage.md` 5장).
 *
 * <p>스캔 로직({@link HoldExpirySweeper})과 떼어 둔다. 테스트는 스캔을 직접 부르고 이 트리거는
 * {@code app.expiry.enabled=false}로 끈다 — 주기 실행이 테스트 중간에 끼어들면
 * 「한 주기에 몇 건 처리했는지」를 셀 수 없다.
 *
 * <p>{@code fixedDelay}라서 한 주기가 길어지면 다음 주기가 밀린다(겹쳐 돌지 않는다).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.expiry", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HoldExpiryScheduler {

    private final HoldExpirySweeper sweeper;

    @Scheduled(fixedDelayString =
        "#{T(com.seatsac.booking.common.BookingPolicy).EXPIRY_LEDGER_INTERVAL.toMillis()}")
    public void sweepLedger() {
        sweeper.sweepLedger();
    }

    @Scheduled(fixedDelayString =
        "#{T(com.seatsac.booking.common.BookingPolicy).EXPIRY_DB_INTERVAL.toMillis()}")
    public void sweepDatabase() {
        sweeper.sweepDatabase();
    }
}

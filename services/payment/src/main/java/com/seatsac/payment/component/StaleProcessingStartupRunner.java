package com.seatsac.payment.component;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@link StaleProcessingCloser}를 <b>기동 때 한 번, 그 뒤 1분마다</b> 부른다 (명세 3·7장).
 *
 * <p>기동 때만 부르면 「생긴 지 30초 안 된 행」을 남긴 채 끝난다 — 그 사이 파드가 죽으면 그 행은
 * 다음 기동까지 {@code PROCESSING}으로 남고, Booking은 결과 미확인으로 보고 예매를 계속
 * 보류한다(좌석이 묶인다). 같은 30초 기준과 조건부 UPDATE를 쓰므로 반복 호출해도 중복으로
 * 닫히지 않는다.
 *
 * <p>정리 로직과 떼어 둔 이유: 테스트는 {@code app.payment.close-stale-on-startup=false}로 이
 * 트리거만 끄고 정리를 직접 부른다 — 주기 실행이 끼어들면 몇 건을 닫았는지 셀 수 없다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.payment", name = "close-stale-on-startup",
    havingValue = "true", matchIfMissing = true)
public class StaleProcessingStartupRunner implements ApplicationRunner {

    private final StaleProcessingCloser closer;

    @Override
    public void run(ApplicationArguments args) {
        closer.closeStale();
    }

    /** 기동 뒤에도 주기적으로 — 30초 안에 죽어 남은 행을 다음 기동까지 끌지 않는다 */
    @Scheduled(fixedDelay = 60, timeUnit = TimeUnit.SECONDS)
    public void sweep() {
        closer.closeStale();
    }
}

package com.seatsac.payment.component;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 기동 때 {@link StaleProcessingCloser}를 한 번 부른다 (명세 3·7장).
 *
 * <p>정리 로직과 떼어 둔 이유: 테스트는 {@code app.payment.close-stale-on-startup=false}로 이
 * 트리거만 끄고 정리를 직접 부른다 — 컨텍스트 기동 때 돌아 버리면 몇 건을 닫았는지 셀 수 없다.
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
}

package com.seatsac.booking.checkout;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 체크아웃 스윕 주기 — 1초. API 플래그({@code APP_CHECKOUT_SAGA_ENABLED})와 <b>상관없이</b> 돈다: 플래그를 꺼서 새 체크아웃을
 * 막은 뒤에도 진행 중이던 체크아웃은 끝까지 닫혀야 한다(만료 → 해제 명령, 런북 6-D-6 ② 대기가 끝나려면). 체크아웃이 없으면
 * 인덱스 조회 두 번이 전부다. 만료 스캔과 같은 스위치({@code app.expiry.enabled}, 테스트만 끈다)를 따른다
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.expiry", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CheckoutSweepScheduler {

    private final CheckoutSweeper sweeper;

    @Scheduled(fixedDelayString = "1s")
    public void sweep() {
        sweeper.sweepOnce();
    }
}

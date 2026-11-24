package com.seatsac.booking.checkout;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 체크아웃 스윕 주기 — 1초. 체크아웃이 꺼져 있으면({@code APP_CHECKOUT_SAGA_ENABLED=false}) 빈이 없다 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.checkout", name = "saga-enabled", havingValue = "true")
public class CheckoutSweepScheduler {

    private final CheckoutSweeper sweeper;

    @Scheduled(fixedDelayString = "1s")
    public void sweep() {
        sweeper.sweepOnce();
    }
}

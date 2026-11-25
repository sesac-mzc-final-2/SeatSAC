package com.seatsac.payment.saga;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 결과 릴레이 주기 — 파드마다 1초({@code APP_OUTBOX_RELAY_INTERVAL}, booking과 같은 이름·뜻).
 * {@code APP_OUTBOX_RELAY_ENABLED=false}(기본)면 빈이 없다 — {@code booking-replies} 큐·Pod Identity 전에 돌면 10회 재시도 끝에
 * FAILED가 된다. 꺼 두면 PENDING으로 쌓였다가 켜는 순간 흘러간다({@code payment_outbox_pending}).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.outbox", name = "relay-enabled", havingValue = "true")
public class ReplyOutboxRelayScheduler {

    private final ReplyOutboxRelay relay;

    @Scheduled(fixedDelayString = "${app.outbox.relay-interval:1s}")
    public void relay() {
        relay.relayOnce();
    }
}

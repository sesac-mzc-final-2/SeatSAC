package com.seatsac.catalog.event;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 변경 이벤트 릴레이 주기 — 파드마다 1초({@code APP_OUTBOX_RELAY_INTERVAL}, booking·coupon과 같은 이름·뜻).
 * {@code APP_OUTBOX_RELAY_ENABLED=false}(기본)면 빈이 없다 — SNS {@code catalog-events}·구독 큐(6.1.3)·Pod Identity 전에 돌면
 * 10회 재시도 끝에 FAILED가 된다. 꺼 두면 PENDING으로 쌓였다가 켜는 순간 흘러간다({@code catalog_outbox_pending}).
 */
@Component
@ConditionalOnProperty(prefix = "app.outbox", name = "relay-enabled", havingValue = "true")
public class CatalogOutboxRelayScheduler {

    private final CatalogOutboxRelay relay;

    public CatalogOutboxRelayScheduler(CatalogOutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${app.outbox.relay-interval:1s}")
    public void relay() {
        relay.relayOnce();
    }
}

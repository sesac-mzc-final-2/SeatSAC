package com.seatsac.booking.component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 릴레이 주기 실행 — 파드마다 1초(런타임 계약 3장 {@code APP_OUTBOX_RELAY_INTERVAL}).
 *
 * <p>{@code APP_OUTBOX_RELAY_ENABLED=false}면 이 빈이 아예 만들어지지 않는다. 알림 큐(3.1.8)가
 * 생기기 전에 돌면 확정마다 10회 재시도 끝에 {@code FAILED}가 되어 <b>사람이 손으로 되살려야</b>
 * 한다. 꺼 두면 아웃박스에 {@code PENDING}으로 쌓여 있다가 켜는 순간 그대로 흘러간다
 * (쌓인 양은 {@code booking_outbox_pending}으로 보인다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.outbox", name = "relay-enabled", havingValue = "true")
public class OutboxRelayScheduler {

    private final OutboxRelay relay;

    @Scheduled(fixedDelayString = "${app.outbox.relay-interval:1s}")
    public void relay() {
        try {
            relay.relayOnce();
        } catch (DataAccessException e) {
            // 다음 주기가 이어받는다. 스케줄러 스레드에서 예외가 나가면 이후 주기가 멈춘다
            log.warn("아웃박스 릴레이 주기 실패 — 다음 주기에 다시 본다: {}", e.getMessage());
        }
    }
}

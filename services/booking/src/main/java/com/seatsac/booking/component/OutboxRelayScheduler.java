package com.seatsac.booking.component;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.outbox", name = "relay-enabled", havingValue = "true")
public class OutboxRelayScheduler {

    private final OutboxRelay relay;

    /**
     * {@link OutboxRelay#relayOnce()}는 예외를 밖으로 내지 않는다(실패를 자기 traceId 안에서
     * 로그로 남기고 0을 돌려준다). 그래서 여기서 다시 감싸지 않는다 — 감싸면 그 로그에
     * traceId가 빠지고, 스케줄러 스레드로 예외가 나가 주기가 멈추는 일도 없다.
     */
    @Scheduled(fixedDelayString = "${app.outbox.relay-interval:1s}")
    public void relay() {
        relay.relayOnce();
    }
}

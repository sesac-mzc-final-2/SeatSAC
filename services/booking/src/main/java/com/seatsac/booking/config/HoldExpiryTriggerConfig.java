package com.seatsac.booking.config;

import com.seatsac.booking.component.SeatKeyExpiredListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * BKG-04 빠른 경로의 구독 — keyspace 만료 알림을 {@link SeatKeyExpiredListener}에 연결한다.
 *
 * <p>스캔 주기({@code HoldExpiryScheduler})와 같은 스위치({@code app.expiry.enabled})를 쓴다.
 * 끄면 만료를 <b>스스로 돌리지 않을</b> 뿐이고, 스캔 메서드는 그대로 부를 수 있다 —
 * DB만 띄우는 테스트가 Valkey 연결을 필요로 하지 않게 하려는 목적이다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.expiry", name = "enabled", havingValue = "true",
    matchIfMissing = true)
public class HoldExpiryTriggerConfig {

    public HoldExpiryTriggerConfig(RedisMessageListenerContainer listenerContainer,
                                   SeatKeyExpiredListener listener) {
        listenerContainer.addMessageListener(listener, SeatKeyExpiredListener.EXPIRED_KEYS);
    }
}

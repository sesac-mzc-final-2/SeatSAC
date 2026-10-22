package com.seatsac.booking.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * BKG-05 SSE에 필요한 두 가지 — Pub/Sub 구독 컨테이너와 스케줄링.
 *
 * <p>Spring Boot는 {@link RedisMessageListenerContainer}를 자동 설정하지 않는다. 구독은
 * 보는 사람이 있는 회차만 걸고(파드마다), 마지막 연결이 끊기면 해제한다 — {@code SeatStreamService}.
 *
 * <p>{@code @EnableScheduling}은 하트비트(15초)가 쓰고, 3.2.5 만료 스캔(1초·10초)도 함께 쓴다.
 */
@EnableScheduling
@Configuration(proxyBeanMethods = false)
public class SeatStreamConfig {

    @Bean
    public RedisMessageListenerContainer seatEventListenerContainer(RedisConnectionFactory factory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        return container;
    }
}

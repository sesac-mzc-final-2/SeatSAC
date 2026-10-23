package com.seatsac.booking.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * BKG-05 SSE에 필요한 두 가지 — Pub/Sub 구독 컨테이너와 스케줄링.
 *
 * <p>Spring Boot는 {@link RedisMessageListenerContainer}를 자동 설정하지 않는다. 구독은
 * 보는 사람이 있는 회차만 걸고, 마지막 연결이 끊기면 해제한다 — {@code SeatStreamService}.
 *
 * <p>{@code @EnableScheduling}은 하트비트(15초)가 쓰고, 3.2.5 만료 스캔(1초·10초)도 함께 쓴다.
 */
@EnableScheduling
@Configuration(proxyBeanMethods = false)
public class SeatStreamConfig {

    @Bean
    public RedisMessageListenerContainer seatEventListenerContainer(RedisConnectionFactory factory,
                                                                    ThreadPoolTaskExecutor seatEventExecutor) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.setTaskExecutor(seatEventExecutor);
        return container;
    }

    /**
     * 좌석 이벤트를 연결로 내보내는 스레드. <b>개수와 큐를 묶는 것</b>이 목적이다 — 기본 실행기는
     * 메시지마다 스레드를 만들어(제한 없음) 발행이 몰리거나 느린 연결이 있으면 파드가
     * 메모리·스레드로 터진다. 큐가 차면 호출 스레드(Valkey 구독 스레드)가 직접 처리해
     * 발행 쪽에 역압을 준다.
     *
     * <p>회차별 순서는 보장하지 않는다. 여러 파드가 {@code INCR → PUBLISH}를 하므로 <b>어차피
     * 순서가 뒤바뀔 수 있고</b>, 화면은 그래서 {@code seq == lastSeq + 1}일 때만 적용하고 빈틈이면
     * 200ms 뒤 BKG-01을 다시 조회한다(API 명세서 v1.1). 한 파드 안에서만 직렬화해도 이 규칙은
     * 그대로 필요하다.
     */
    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor seatEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("seat-event-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1_000);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 종료 때 남은 이벤트를 기다리지 않는다 — 어차피 연결을 닫는다(@PreDestroy)
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}

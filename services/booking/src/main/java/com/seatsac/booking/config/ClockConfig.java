package com.seatsac.booking.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시각은 주입받은 Clock 하나로만 구한다 — 지침 4장.
 * 흐름마다 {@code clock.instant()}으로 now를 한 번 구해 조건·저장 값에 같은 값을 쓰고,
 * 쿼리에는 {@code :now} 파라미터로 넘긴다. {@code Instant.now()}·SQL {@code NOW()}는 쓰지 않는다.
 * 테스트는 {@code Clock.fixed(...)}로 바꿔 끼운다.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

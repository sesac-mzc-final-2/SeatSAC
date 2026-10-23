package com.seatsac.catalog.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시각은 주입받은 Clock 하나로만 구한다(규약 6장 — 앱 {@code Clock.systemUTC()}).
 * CAT-03 "시작한 회차 제외"는 요청마다 {@code clock.instant()}로 now를 한 번 구해 비교한다.
 * 테스트는 {@code Clock.fixed(...)}로 바꿔 끼운다.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

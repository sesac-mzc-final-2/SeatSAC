package com.seatsac.user.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 시각은 주입받은 Clock 하나로만 구한다(규약 6장 — 앱 {@code Clock.systemUTC()}). 테스트는 {@code Clock.fixed(...)}로 바꿔 끼운다. */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

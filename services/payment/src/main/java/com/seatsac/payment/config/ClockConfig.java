package com.seatsac.payment.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 모든 시각은 이 빈에서 얻는다 — DB는 UTC로 저장하고 응답에서 {@code +09:00}으로 바꾼다.
 * 상태 전이 조건에 {@code NOW()}를 쓰지 않고 앱이 구한 {@code :now}를 넘기는 것도 같은 이유다.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

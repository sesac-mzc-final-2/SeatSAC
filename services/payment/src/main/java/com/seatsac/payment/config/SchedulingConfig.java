package com.seatsac.payment.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code PROCESSING} 정리({@code StaleProcessingStartupRunner})가 주기 실행을 쓴다 (명세 3·7장).
 * 결제 판정은 요청 스레드에서 끝나므로 다른 스케줄 작업은 없다.
 */
@EnableScheduling
@Configuration(proxyBeanMethods = false)
public class SchedulingConfig {
}

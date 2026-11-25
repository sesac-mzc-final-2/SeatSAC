package com.seatsac.catalog.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 주기 작업 — 변경 찾기(1분)·outbox 릴레이(1초, 플래그). 각 작업은 자기 플래그로 빈을 만들지 않을 수 있다 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {
}

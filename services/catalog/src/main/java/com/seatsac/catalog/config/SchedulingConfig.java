package com.seatsac.catalog.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 상영 일정 이어 붙이기({@code ScheduleExtender})가 주기 실행을 쓴다 */
@EnableScheduling
@Configuration(proxyBeanMethods = false)
public class SchedulingConfig {
}

package com.seatsac.coupon.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 종료 스윕({@code CouponExpirySweeper})·결과 릴레이({@code ReplyOutboxRelayScheduler})가 주기 실행을 쓴다 */
@EnableScheduling
@Configuration(proxyBeanMethods = false)
public class SchedulingConfig {
}

package com.seatsac.booking.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 선점 설정({@link SeatHoldProperties})·결제 설정({@link PaymentProperties})을 빈으로 올린다 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SeatHoldProperties.class, PaymentProperties.class})
public class SeatHoldConfig {
}

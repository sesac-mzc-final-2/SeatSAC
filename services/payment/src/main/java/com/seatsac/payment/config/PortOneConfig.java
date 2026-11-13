package com.seatsac.payment.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** {@link PortOneProperties} 바인딩. 포트원 클라이언트·서명 검증·스윕이 이 설정을 읽는다 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PortOneProperties.class)
public class PortOneConfig {
}

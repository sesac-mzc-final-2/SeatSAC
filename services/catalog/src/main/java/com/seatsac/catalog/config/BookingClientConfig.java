package com.seatsac.catalog.config;

import com.seatsac.common.web.client.InternalRestClientFactory;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * booking 내부 API 클라이언트. 공통 {@link InternalRestClientFactory}로 만들어 traceId 전파·5xx→503 변환을 그대로 받고,
 * 연결·응답 타임아웃을 모두 CAT-03 예산({@code app.services.booking-read-timeout}, 기본 300ms)에 맞춘다.
 * 공통 연결 기본값(1초)을 그대로 두면 booking 연결이 느릴 때 잔여 좌석 조회가 "300ms 전체" 계약을 넘기므로
 * 연결 타임아웃도 같은 값으로 준다(설정 이름은 새로 만들지 않는다 — 서비스 실행 계약 3장).
 * 서킷은 {@code BookingSeatCountClient}가 이름 {@code booking}으로 건다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CatalogProperties.class)
public class BookingClientConfig {

    @Bean
    public RestClient bookingRestClient(InternalRestClientFactory factory, CatalogProperties properties) {
        Duration budget = properties.services().bookingReadTimeout();
        return factory.create(properties.services().bookingUrl(), budget, budget);
    }
}

package com.seatsac.catalog.config;

import com.seatsac.common.web.client.InternalRestClientFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * booking 내부 API 클라이언트. 공통 {@link InternalRestClientFactory}로 만들어 traceId 전파·5xx→503 변환을 그대로 받고,
 * 응답 타임아웃만 CAT-03 예산(300ms 전체)에 맞춘다. 서킷은 {@code BookingSeatCountClient}가 이름 {@code booking}으로 건다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CatalogProperties.class)
public class BookingClientConfig {

    @Bean
    public RestClient bookingRestClient(InternalRestClientFactory factory, CatalogProperties properties) {
        return factory.create(properties.services().bookingUrl(), properties.services().bookingReadTimeout());
    }
}

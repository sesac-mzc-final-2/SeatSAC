package com.seatsac.user.config;

import com.seatsac.common.web.client.InternalRestClientFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 밖으로 나가는 호출 셋.
 * <ul>
 *   <li>booking·catalog 내부 API — 공통 {@link InternalRestClientFactory}(연결 1초·응답 3초, traceId·userId 전파,
 *       타임아웃·5xx → 503). 서킷은 각 클라이언트가 이름 {@code booking}·{@code catalog}로 건다</li>
 *   <li>Cognito {@code GetUser} — AWS 바깥 호출이라 내부 헤더를 붙이지 않고 타임아웃만 같은 값으로 둔다.
 *       실패 변환(503 {@code IDENTITY_UNAVAILABLE})은 {@code CognitoUserInfoClient}가 한다</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UserProperties.class)
public class ClientConfig {

    @Bean
    public RestClient bookingRestClient(InternalRestClientFactory factory, UserProperties properties) {
        return factory.create(properties.services().bookingUrl());
    }

    @Bean
    public RestClient catalogRestClient(InternalRestClientFactory factory, UserProperties properties) {
        return factory.create(properties.services().catalogUrl());
    }

    @Bean
    public RestClient cognitoRestClient(UserProperties properties) {
        var settings = ClientHttpRequestFactorySettings.defaults()
            .withConnectTimeout(InternalRestClientFactory.CONNECT)
            .withReadTimeout(InternalRestClientFactory.READ);
        return RestClient.builder()
            .baseUrl(properties.cognito().endpoint())
            .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
            .build();
    }
}

package com.seatsac.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * user가 읽는 설정 — 서비스 실행 계약 3장의 이름 그대로.
 * <ul>
 *   <li>{@code APP_SERVICES_BOOKING_URL} → {@code app.services.booking-url} (USR-04 예매 내역)</li>
 *   <li>{@code APP_SERVICES_CATALOG_URL} → {@code app.services.catalog-url} (USR-04 회차 요약 · USR-05 극장 검증)</li>
 *   <li>{@code APP_COGNITO_ENDPOINT} → {@code app.cognito.endpoint} (선택. 기본값은 {@code AWS_REGION}으로 만든
 *       {@code https://cognito-idp.{region}.amazonaws.com} — 프로필 첫 생성 때 {@code GetUser})</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app")
public record UserProperties(Services services, Cognito cognito) {

    public record Services(
        @DefaultValue("http://booking:8080") String bookingUrl,
        @DefaultValue("http://catalog:8080") String catalogUrl
    ) {
    }

    public record Cognito(
        @DefaultValue("https://cognito-idp.ap-northeast-2.amazonaws.com") String endpoint
    ) {
    }
}

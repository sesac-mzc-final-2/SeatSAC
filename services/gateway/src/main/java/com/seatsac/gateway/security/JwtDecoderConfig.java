package com.seatsac.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/**
 * Cognito Access Token 디코더.
 * <ul>
 *   <li>서명: {issuer}/.well-known/jwks.json 의 RS256 공개키 (첫 요청 때 받아 캐시, 키 교체 시 다시 받음)</li>
 *   <li>검증: exp·nbf(허용 오차 60초) + iss + token_use=access + client_id (Cognito Access Token에는 aud가 없다)</li>
 * </ul>
 * 기동할 때 Cognito를 호출하지 않으므로(지연 로딩) Cognito 장애가 Gateway 기동을 막지 않는다.
 */
@Configuration
public class JwtDecoderConfig {

    @Bean
    ReactiveJwtDecoder jwtDecoder(@Value("${app.cognito.issuer-uri}") String issuerUri,
                                  @Value("${app.cognito.client-id}") String clientId) {
        String issuer = issuerUri.endsWith("/") ? issuerUri.substring(0, issuerUri.length() - 1) : issuerUri;
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
            .withJwkSetUri(issuer + "/.well-known/jwks.json")
            .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer),
            new CognitoAccessTokenValidator(clientId)));
        return decoder;
    }
}

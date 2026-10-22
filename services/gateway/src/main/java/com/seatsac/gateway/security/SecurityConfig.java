package com.seatsac.gateway.security;

import com.seatsac.common.core.error.CommonResponseCode;
import com.seatsac.common.core.error.ErrorDetail;
import com.seatsac.gateway.web.ErrorResponseWriter;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * GW-02 인증·인가 (ADR-37). Cognito Access Token을 JWKS(RS256)로 검증한다 — 디코더·검증기는 {@link JwtDecoderConfig}.
 * <pre>
 * 공개(토큰 안 읽음)  GET /api/movies/**, /api/theaters/**, /api/screenings/**(좌석 현황·SSE 포함), /api/auditoriums/**
 * ADMIN             /api/admin/**
 * 로그인            그 밖의 /api/**
 * 인증 없음          /actuator/health/**(ALB·kubelet), /actuator/prometheus(클러스터 안 수집. 외부는 CloudFront가 /api/*만 ALB로 보냄)
 * </pre>
 * 오류는 공통 형식으로 401 UNAUTHORIZED, 403 FORBIDDEN.
 */
@Configuration
public class SecurityConfig {

    static final String[] PUBLIC_GET_PATHS = {
        "/api/movies/**", "/api/theaters/**", "/api/screenings/**", "/api/auditoriums/**",
    };

    @Bean
    SecurityWebFilterChain api(ServerHttpSecurity http, ReactiveJwtDecoder jwtDecoder, ErrorResponseWriter errorWriter) {
        ServerWebExchangeMatcher publicPaths = ServerWebExchangeMatchers.pathMatchers(HttpMethod.GET, PUBLIC_GET_PATHS);

        return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)       // 쿠키 세션 없음, Bearer 토큰만
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
            .logout(ServerHttpSecurity.LogoutSpec::disable)
            .requestCache(c -> c.requestCache(NoOpServerRequestCache.getInstance()))
            .authorizeExchange(ex -> ex
                .pathMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                .matchers(publicPaths).permitAll()
                .pathMatchers("/api/admin/**").hasRole("ADMIN")
                .pathMatchers("/api/**").authenticated()
                .anyExchange().permitAll())                    // 라우트가 없으므로 404
            .oauth2ResourceServer(o -> o
                .bearerTokenConverter(new PublicPathBearerTokenConverter(publicPaths))
                .jwt(j -> j.jwtDecoder(jwtDecoder).jwtAuthenticationConverter(new CognitoGroupsConverter()))
                .authenticationEntryPoint((exchange, e) -> unauthorized(errorWriter, exchange, e))
                .accessDeniedHandler((exchange, e) -> forbidden(errorWriter, exchange)))
            .exceptionHandling(e -> e
                .authenticationEntryPoint((exchange, ae) -> unauthorized(errorWriter, exchange, ae))
                .accessDeniedHandler((exchange, ade) -> forbidden(errorWriter, exchange)))
            .build();
    }

    private static Mono<Void> unauthorized(ErrorResponseWriter writer, ServerWebExchange exchange, AuthenticationException e) {
        boolean tokenRejected = e instanceof InvalidBearerTokenException
            || exchange.getRequest().getHeaders().containsKey(HttpHeaders.AUTHORIZATION);
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE,
            tokenRejected ? "Bearer error=\"invalid_token\"" : "Bearer");
        return tokenRejected
            ? writer.write(exchange, CommonResponseCode.UNAUTHORIZED, "토큰이 유효하지 않거나 만료되었습니다.",
                List.of(ErrorDetail.of("Authorization", null, "INVALID_TOKEN")))
            : writer.write(exchange, CommonResponseCode.UNAUTHORIZED, "로그인이 필요합니다.",
                List.of(ErrorDetail.of("Authorization", null, "TOKEN_REQUIRED")));
    }

    private static Mono<Void> forbidden(ErrorResponseWriter writer, ServerWebExchange exchange) {
        return writer.write(exchange, CommonResponseCode.FORBIDDEN, "권한이 없습니다.",
            List.of(ErrorDetail.of("role", null, "ADMIN_REQUIRED")));
    }
}

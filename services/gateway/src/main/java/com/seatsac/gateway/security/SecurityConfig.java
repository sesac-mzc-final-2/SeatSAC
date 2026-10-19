package com.seatsac.gateway.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Cognito Access Token 검증 (ADR-37). 검증 코드는 쓰지 않고 Resource Server 설정만 한다.
 * 통과한 요청에 X-User-Id(sub)·X-User-Role 을 붙이는 것은 UserHeaderFilter.
 * TODO: token_use=access, client_id 검증용 JwtValidator 추가
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain api(ServerHttpSecurity http) {
        return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .authorizeExchange(ex -> ex
                .pathMatchers("/actuator/health/**").permitAll()
                .pathMatchers(HttpMethod.GET, "/api/movies/**", "/api/theaters/**", "/api/screenings/**", "/api/auditoriums/**").permitAll()
                .pathMatchers("/api/admin/**").hasAuthority("ROLE_ADMIN")
                .anyExchange().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(new CognitoGroupsConverter())))
            .build();
    }
}

package com.seatsac.gateway.filter;

import com.seatsac.common.core.http.Headers;
import com.seatsac.gateway.web.TraceWebFilter;
import java.security.Principal;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * GW-02 사용자 헤더. 토큰 검증을 통과한 요청에 X-User-Id(= Cognito sub)·X-User-Role(항상 USER — 관리자 역할 없음)·
 * X-User-Grade(6.3.1a, {@link UserGrade} — NORMAL·VIP)를 붙인다.
 * 외부에서 온 X-User-*(X-User-Grade 포함)는 TraceWebFilter가 모든 라우트에서 이미 지웠고, X-Trace-Id도 거기서 붙였다.
 * 공개 경로(GET 조회·좌석 현황·SSE)는 토큰을 읽지 않으므로 사용자 헤더가 없다.
 */
@Component
public class UserHeaderFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.<Principal>getPrincipal()
            .filter(JwtAuthenticationToken.class::isInstance)
            .cast(JwtAuthenticationToken.class)
            .map(auth -> {
                String userId = auth.getToken().getSubject();
                exchange.getAttributes().put(TraceWebFilter.USER_ID_ATTR, userId);
                return exchange.mutate().request(r -> r.headers(h -> {
                    h.set(Headers.USER_ID, userId);
                    h.set(Headers.USER_ROLE, Headers.ROLE_USER);
                    h.set(UserGrade.HEADER, UserGrade.fromToken(auth.getToken()));
                })).build();
            })
            .defaultIfEmpty(exchange)
            .flatMap(chain::filter);
    }

    @Override
    public int getOrder() {
        return -1;
    }
}

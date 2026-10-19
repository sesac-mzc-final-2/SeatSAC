package com.seatsac.gateway.filter;

import com.seatsac.common.core.http.Headers;
import java.util.UUID;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 인증된 요청에 X-User-Id(sub)·X-User-Role, 모든 요청에 X-Trace-Id 를 붙여 하위 서비스로 보낸다 (GW-02·03). */
@Component
public class UserHeaderFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst(Headers.TRACE_ID);
        String finalTraceId = traceId == null || traceId.isBlank()
            ? UUID.randomUUID().toString().replace("-", "").substring(0, 16) : traceId;
        exchange.getResponse().getHeaders().set(Headers.TRACE_ID, finalTraceId);

        return ReactiveSecurityContextHolder.getContext()
            .map(ctx -> ctx.getAuthentication())
            .filter(JwtAuthenticationToken.class::isInstance)
            .cast(JwtAuthenticationToken.class)
            .map(auth -> {
                String role = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))
                    ? Headers.ROLE_ADMIN : Headers.ROLE_USER;
                return exchange.mutate().request(r -> r.headers(h -> {
                    h.set(Headers.USER_ID, auth.getToken().getSubject());
                    h.set(Headers.USER_ROLE, role);
                    h.set(Headers.TRACE_ID, finalTraceId);
                })).build();
            })
            .defaultIfEmpty(exchange.mutate().request(r -> r.headers(h -> h.set(Headers.TRACE_ID, finalTraceId))).build())
            .flatMap(chain::filter);
    }

    @Override
    public int getOrder() {
        return -1;
    }
}

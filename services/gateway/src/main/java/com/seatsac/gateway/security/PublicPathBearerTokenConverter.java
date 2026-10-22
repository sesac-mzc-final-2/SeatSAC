package com.seatsac.gateway.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 공개 경로에서는 Authorization 헤더를 읽지 않는다.
 * 화면이 만료된 토큰을 붙인 채 영화 목록·좌석 현황을 불러도 401이 아니라 그대로 조회되게 하기 위해서다.
 */
class PublicPathBearerTokenConverter implements ServerAuthenticationConverter {

    private final ServerWebExchangeMatcher publicPaths;
    private final ServerBearerTokenAuthenticationConverter delegate = new ServerBearerTokenAuthenticationConverter();

    PublicPathBearerTokenConverter(ServerWebExchangeMatcher publicPaths) {
        this.publicPaths = publicPaths;
    }

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        return publicPaths.matches(exchange)
            .flatMap(result -> result.isMatch() ? Mono.<Authentication>empty() : delegate.convert(exchange));
    }
}

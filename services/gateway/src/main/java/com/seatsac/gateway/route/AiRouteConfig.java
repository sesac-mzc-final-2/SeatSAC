package com.seatsac.gateway.route;

import static org.springframework.cloud.gateway.support.RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 챗봇 라우트 {@code /api/ai/**} → ai 서비스 (3.3.8, ADR-86). 플래그 {@code app.ai.enabled}(env {@code APP_AI_ENABLED})가
 * {@code true}일 때만 이 설정이 올라와 라우트가 생긴다. 꺼져 있으면 라우트 자체가 없어 다른 미등록 경로와 똑같이 404 {@code NOT_FOUND}
 * (화면은 첫 {@code GET /ai/presets} 404로 챗봇을 숨긴다).
 * <ul>
 *   <li>id는 반드시 {@code ai} — gateway 블루/그린 분석(3.1.21)이 {@code spring_cloud_gateway_requests_seconds_count{routeId="ai"}}를
 *       5xx 분자·분모에서 빼서 Claude API 지연·장애가 자동 롤백을 일으키지 않게 한다. 바꾸면 AI 요청이 빠지지 않는다</li>
 *   <li>응답 타임아웃은 이 라우트만 28초({@code response-timeout: 28000}, docs/service-runtime-contract.md 9-1 시간 예산). 바깥이 항상 안쪽보다
 *       길다: CloudFront 원본 대기 30초 &gt; gateway 28초 &gt; ai 요청 전체 기한 25초. 30초로 두면 CloudFront와 같은 값이라 누가 먼저 끊을지
 *       경주가 되어 화면이 traceId 없는 CloudFront 504를 받을 수 있다. 정상 경로는 ai가 25초에 먼저 503으로 끝내고, 28초는 ai 자체가
 *       멈췄을 때의 안전장치다. 다른 라우트는 기본 15초 그대로</li>
 *   <li>인증은 {@link com.seatsac.gateway.security.SecurityConfig}의 {@code /api/**} 로그인 규칙(USER)을 그대로 쓴다.
 *       {@code Authorization} 헤더는 지우지 않고 전달한다 — ai가 "내 예매" 질문에 공개 API {@code /users/me/bookings}를 사용자 토큰으로 부른다</li>
 *   <li>YAML 라우트의 default-filters(StripPrefix=1)는 Java 라우트에 적용되지 않으므로 여기서 직접 {@code /api}를 뗀다</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true")
public class AiRouteConfig {

    public static final String ROUTE_ID = "ai";
    static final int RESPONSE_TIMEOUT_MS = 28_000;

    @Bean
    RouteLocator aiRoute(RouteLocatorBuilder builder, @Value("${app.services.ai-url}") String aiUrl) {
        return builder.routes()
            .route(ROUTE_ID, r -> r.path("/api/ai/**")
                .filters(f -> f.stripPrefix(1))
                .metadata(RESPONSE_TIMEOUT_ATTR, RESPONSE_TIMEOUT_MS)
                .uri(aiUrl))
            .build();
    }
}

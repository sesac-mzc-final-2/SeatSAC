package com.seatsac.gateway.web;

import static net.logstash.logback.argument.StructuredArguments.kv;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.web.server.ServerWebExchange;

/**
 * 보안 로그 한 줄 (6.3.3, ADR-121·124). gateway가 막거나 고친 요청마다 {@code errorCategory=SECURITY}로 남긴다 —
 * 일자별 오류·보안 리포트(Loki → S3 → Athena)가 이 값으로 나눈다.
 * <pre>
 * reason             status  언제
 * TOKEN_REQUIRED     401     로그인 경로에 토큰 없음
 * INVALID_TOKEN      401     서명·만료·발급자·client_id·token_use 불일치 (응답 details[].reason 과 같은 이름)
 * FORBIDDEN          403     인가 거부 (지금 역할로 막는 경로는 없다 — 공통 형식 유지용)
 * MAX_BODY_SIZE      413     요청 본문이 app.gateway.max-body-size(64KB) 초과
 * BLOCKED_PATH       404     /api 아래 internal·actuator 세그먼트
 * INVALID_PATH       400     . · .. 세그먼트·역슬래시·세미콜론·이중 인코딩 (차단 경로 우회 시도)
 * FORGED_HEADER      -       외부에서 X-User-* 헤더가 들어옴 (지우고 요청은 그대로 진행)
 * </pre>
 * 필드: {@code errorCategory, reason, status, method, path, route} + MDC {@code traceId}·{@code userId}(있으면).
 * <b>토큰·IP·헤더 값·본문은 남기지 않는다</b> — path는 쿼리 없이 경로만(접근 로그와 같다). FORGED_HEADER는 헤더 이름만.
 */
public final class SecurityEventLog {

    public static final String CATEGORY = "SECURITY";

    private static final Logger log = LoggerFactory.getLogger("security");

    private SecurityEventLog() {
    }

    public static void record(ServerWebExchange exchange, Integer status, String reason) {
        record(exchange, status, reason, null);
    }

    /** {@code headerNames}는 FORGED_HEADER 때만 — 이름만, 값은 넣지 않는다 */
    public static void record(ServerWebExchange exchange, Integer status, String reason, String headerNames) {
        String traceId = TraceWebFilter.traceId(exchange);
        String userId = exchange.getAttribute(TraceWebFilter.USER_ID_ATTR);
        if (traceId != null) {
            MDC.put("traceId", traceId);
        }
        if (userId != null) {
            MDC.put("userId", userId);
        }
        try {
            Object[] args = {
                kv("errorCategory", CATEGORY),
                kv("reason", reason),
                kv("status", status),
                kv("method", exchange.getRequest().getMethod().name()),
                kv("path", exchange.getRequest().getPath().value()),
                kv("route", routeId(exchange)),
                kv("headers", headerNames),
            };
            log.warn("security {} {} {} {} {} {} {}", args);
        } finally {
            MDC.remove("traceId");
            MDC.remove("userId");
        }
    }

    private static String routeId(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return route == null ? null : route.getId();
    }
}

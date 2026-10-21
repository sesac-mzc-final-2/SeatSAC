package com.seatsac.gateway.web;

import com.seatsac.common.core.error.CommonResponseCode;
import com.seatsac.common.core.error.ErrorDetail;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;

/**
 * 라우팅 중 생긴 예외를 공통 오류 형식으로 바꾼다. Boot 기본 핸들러(@Order(-1))보다 먼저 실행한다.
 * <ul>
 *   <li>라우트 없음 404 → NOT_FOUND</li>
 *   <li>하위 서비스 연결 실패·응답 시간 초과·연결 끊김 → 503 SERVICE_UNAVAILABLE (GW-01 「서비스 응답 없음은 503」)</li>
 *   <li>그 밖의 4xx는 상태를 유지, 나머지는 500 INTERNAL_ERROR</li>
 * </ul>
 * 하위 서비스가 직접 돌려준 4xx·5xx 응답은 예외가 아니므로 여기를 지나지 않고 그대로 전달된다.
 */
@Component
@Order(-2)
public class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    private final ErrorResponseWriter errorWriter;

    public GatewayErrorHandler(ErrorResponseWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex); // SSE 스트림 도중 끊김 등: 이미 보낸 응답은 바꿀 수 없다
        }

        String upstreamReason = upstreamFailure(ex);
        if (upstreamReason != null) {
            String routeId = routeId(exchange);
            withTrace(exchange, () -> log.warn("upstream unavailable route={} reason={} error={}",
                routeId, upstreamReason, ex.toString()));
            return errorWriter.write(exchange, CommonResponseCode.SERVICE_UNAVAILABLE, "서비스가 잠시 응답하지 않습니다.",
                List.of(ErrorDetail.of("service", routeId, upstreamReason)));
        }

        if (ex instanceof ResponseStatusException rse && rse.getStatusCode().is4xxClientError()) {
            HttpStatusCode status = rse.getStatusCode();
            if (status.value() == 404 || status.value() == 405) {
                return errorWriter.write(exchange, HttpStatus.valueOf(status.value()), CommonResponseCode.NOT_FOUND,
                    "요청한 경로가 없습니다.", List.of());
            }
            return errorWriter.write(exchange, HttpStatus.valueOf(status.value()), CommonResponseCode.VALIDATION_FAILED,
                "요청을 처리할 수 없습니다.", List.of());
        }

        withTrace(exchange, () -> log.error("gateway error path={}", exchange.getRequest().getPath(), ex));
        return errorWriter.write(exchange, CommonResponseCode.INTERNAL_ERROR, "서버 오류입니다.", List.of());
    }

    /** 하위 서비스 문제면 사유(details[].reason), 아니면 null */
    static String upstreamFailure(Throwable ex) {
        if (ex instanceof ResponseStatusException rse) {
            int status = rse.getStatusCode().value();
            if (status == 504) {
                return "UPSTREAM_TIMEOUT"; // spring.cloud.gateway...httpclient.response-timeout 초과
            }
            if (status == 502 || status == 503) {
                return "UPSTREAM_UNAVAILABLE";
            }
        }
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException) {
                return "UPSTREAM_TIMEOUT";
            }
            if (t instanceof ConnectException || t instanceof UnknownHostException
                || t instanceof PrematureCloseException) {
                return "UPSTREAM_UNAVAILABLE";
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }

    private static String routeId(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return route == null ? null : route.getId();
    }

    private static void withTrace(ServerWebExchange exchange, Runnable logging) {
        MDC.put("traceId", TraceWebFilter.traceId(exchange));
        try {
            logging.run();
        } finally {
            MDC.remove("traceId");
        }
    }
}

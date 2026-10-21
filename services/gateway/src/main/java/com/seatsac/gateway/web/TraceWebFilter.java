package com.seatsac.gateway.web;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.seatsac.common.core.http.Headers;
import com.seatsac.common.core.trace.TraceIds;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * GW-03 요청 로깅·추적 ID. 가장 먼저 실행되는 필터(보안 필터보다 앞)라 401·403·404도 같은 traceId로 남는다.
 * <ul>
 *   <li>요청마다 X-Trace-Id(소문자 16진수 32자리)를 새로 만든다. 외부에서 온 값은 덮어쓴다</li>
 *   <li>외부에서 온 X-User-* 헤더는 모두 지운다(GW-02). 값은 인증 후 UserHeaderFilter가 다시 넣는다</li>
 *   <li>응답 헤더에도 X-Trace-Id를 넣는다</li>
 *   <li>응답이 끝나면 접근 로그 한 줄(JSON): method, path, status, latencyMs, clientIp + MDC traceId·userId</li>
 * </ul>
 */
@Component
public class TraceWebFilter implements WebFilter, Ordered {

    public static final String TRACE_ID_ATTR = TraceWebFilter.class.getName() + ".traceId";
    public static final String USER_ID_ATTR = TraceWebFilter.class.getName() + ".userId";
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE;

    private static final Logger accessLog = LoggerFactory.getLogger("access");
    private static final String USER_HEADER_PREFIX = "x-user-";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long startNanos = System.nanoTime();
        String traceId = TraceIds.newId();

        ServerHttpRequest request = exchange.getRequest().mutate()
            .headers(h -> {
                List<String> userHeaders = new ArrayList<>();
                for (String name : h.keySet()) {
                    if (name.toLowerCase(Locale.ROOT).startsWith(USER_HEADER_PREFIX)) {
                        userHeaders.add(name);
                    }
                }
                userHeaders.forEach(h::remove);
                h.set(Headers.TRACE_ID, traceId);
            })
            .build();
        ServerWebExchange traced = exchange.mutate().request(request).build();
        traced.getAttributes().put(TRACE_ID_ATTR, traceId);

        traced.getResponse().getHeaders().set(Headers.TRACE_ID, traceId);
        traced.getResponse().beforeCommit(() -> {
            // 하위 서비스 응답 헤더가 복사된 뒤에도 Gateway 값으로 맞춘다
            traced.getResponse().getHeaders().set(Headers.TRACE_ID, traceId);
            return Mono.empty();
        });

        return chain.filter(traced).doFinally(signal -> logAccess(traced, startNanos));
    }

    /** 다른 필터·핸들러가 traceId를 읽을 때 */
    public static String traceId(ServerWebExchange exchange) {
        return exchange.getAttribute(TRACE_ID_ATTR);
    }

    private void logAccess(ServerWebExchange exchange, long startNanos) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        if (path.startsWith("/actuator/")) {
            return; // 헬스 체크·Prometheus 수집은 남기지 않는다
        }
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        int statusValue = status == null ? 0 : status.value(); // 0 = 클라이언트가 먼저 끊음(SSE 종료 등)
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        String userId = exchange.getAttribute(USER_ID_ATTR);

        MDC.put("traceId", traceId(exchange));
        if (userId != null) {
            MDC.put("userId", userId);
        }
        try {
            Object[] args = {
                kv("method", request.getMethod().name()),
                kv("path", path),
                kv("status", statusValue),
                kv("latencyMs", latencyMs),
                kv("clientIp", clientIp(request)),
            };
            if (statusValue >= 500) {
                accessLog.warn("{} {} {} {} {}", args);
            } else {
                accessLog.info("{} {} {} {} {}", args);
            }
        } finally {
            MDC.remove("traceId");
            MDC.remove("userId");
        }
    }

    /**
     * 사용자 IP는 CloudFront가 넣는 CloudFront-Viewer-Address("IP:포트")에서 읽는다.
     * 헤더가 없으면(로컬·클러스터 내부 호출) 연결 주소를 쓴다.
     */
    static String clientIp(ServerHttpRequest request) {
        String viewer = request.getHeaders().getFirst(Headers.VIEWER_ADDRESS);
        if (viewer != null && !viewer.isBlank()) {
            return stripPort(viewer.trim());
        }
        InetSocketAddress remote = request.getRemoteAddress();
        return remote == null || remote.getAddress() == null ? null : remote.getAddress().getHostAddress();
    }

    static String stripPort(String address) {
        if (address.startsWith("[")) { // [IPv6]:port
            int end = address.indexOf(']');
            return end > 0 ? address.substring(1, end) : address;
        }
        int lastColon = address.lastIndexOf(':');
        return lastColon > 0 ? address.substring(0, lastColon) : address;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}

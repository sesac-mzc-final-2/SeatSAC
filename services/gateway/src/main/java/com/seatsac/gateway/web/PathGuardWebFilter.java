package com.seatsac.gateway.web;

import com.seatsac.common.core.error.CommonResponseCode;
import com.seatsac.common.core.error.ErrorDetail;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;

/**
 * GW-01 외부 차단 경로. 라우트가 없으면 어차피 404지만, 경로 조작으로 하위 서비스의 내부 경로에 닿는 것을 먼저 막는다.
 * <ul>
 *   <li>/api 아래에 internal·actuator 세그먼트가 있으면 404 (예: /api/internal/**, /api/movies/actuator/health)</li>
 *   <li>디코딩한 경로에 . · .. 세그먼트, 역슬래시, 세미콜론(경로 파라미터), 이중 인코딩이 있으면 400.
 *       예: /api/movies/%2e%2e/internal/x 를 하위 Tomcat이 /internal/x 로 정규화하는 것을 막는다</li>
 * </ul>
 * 보안 필터보다 먼저 실행해 토큰이 있어도 같은 결과가 나오게 한다.
 */
@Component
public class PathGuardWebFilter implements WebFilter, Ordered {

    private final ErrorResponseWriter errorWriter;

    public PathGuardWebFilter(ErrorResponseWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String rawPath = exchange.getRequest().getURI().getRawPath();
        String decoded;
        try {
            decoded = UriUtils.decode(rawPath, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return invalidPath(exchange, rawPath);
        }
        if (isUnsafe(decoded)) {
            return invalidPath(exchange, rawPath);
        }
        if (isBlocked(decoded)) {
            return errorWriter.write(exchange, CommonResponseCode.NOT_FOUND, "요청한 경로가 없습니다.", List.of());
        }
        return chain.filter(exchange);
    }

    static boolean isUnsafe(String decodedPath) {
        if (decodedPath.indexOf('%') >= 0 || decodedPath.indexOf('\\') >= 0 || decodedPath.indexOf(';') >= 0) {
            return true;
        }
        for (String segment : decodedPath.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    static boolean isBlocked(String decodedPath) {
        if (!decodedPath.startsWith("/api/")) {
            return false;
        }
        for (String segment : decodedPath.split("/")) {
            if (segment.equals("internal") || segment.equals("actuator")) {
                return true;
            }
        }
        return false;
    }

    private Mono<Void> invalidPath(ServerWebExchange exchange, String rawPath) {
        return errorWriter.write(exchange, CommonResponseCode.VALIDATION_FAILED, "허용되지 않는 경로 형식입니다.",
            List.of(ErrorDetail.of("path", rawPath, "INVALID_PATH")));
    }

    @Override
    public int getOrder() {
        return TraceWebFilter.ORDER + 1;
    }
}

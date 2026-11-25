package com.seatsac.gateway.web;

import com.seatsac.common.core.error.ErrorDetail;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 요청 본문 크기 제한 (6.3.3, ADR-124). {@code app.gateway.max-body-size}(env {@code APP_GATEWAY_MAX_BODY_SIZE}, 기본 64KB)를 넘으면
 * 413 {@code PAYLOAD_TOO_LARGE}. 우리 API 본문은 가장 큰 것도 1KB 안팎(선점 좌석 4개·포트원 웹훅)이라 64KB는 여유가 크다.
 * <ul>
 *   <li>{@code Content-Length}가 있으면 본문을 읽기 전에 바로 413 — 하위 서비스로 한 바이트도 보내지 않는다</li>
 *   <li>없으면(chunked) 하위로 흘려보내며 센다. 한도를 넘는 순간 {@link PayloadTooLargeException}으로 전송을 끊고
 *       {@link GatewayErrorHandler}가 413을 쓴다 (하위 서비스가 아직 응답하지 않았으므로 응답을 바꿀 수 있다)</li>
 * </ul>
 * 경로 차단({@link PathGuardWebFilter}) 다음, 인증보다 먼저 실행한다 — 토큰 검증(JWKS) 전에 큰 본문을 끊는다.
 * 그래서 토큰이 없어도 큰 본문은 401이 아니라 413이다.
 */
@Component
public class RequestBodyLimitWebFilter implements WebFilter, Ordered {

    private final long maxBytes;
    private final ErrorResponseWriter errorWriter;

    public RequestBodyLimitWebFilter(@Value("${app.gateway.max-body-size:64KB}") DataSize maxBodySize,
                                     ErrorResponseWriter errorWriter) {
        this.maxBytes = maxBodySize.toBytes();
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        long contentLength = request.getHeaders().getContentLength();
        if (contentLength > maxBytes) {
            return reject(exchange, errorWriter, maxBytes);
        }
        if (contentLength >= 0) {
            return chain.filter(exchange); // 길이를 믿을 수 있다 — Netty는 Content-Length보다 많이 읽지 않는다
        }
        ServerHttpRequest counted = new ServerHttpRequestDecorator(request) {
            @Override
            public Flux<DataBuffer> getBody() {
                AtomicLong total = new AtomicLong();
                return super.getBody().handle((buffer, sink) -> {
                    if (total.addAndGet(buffer.readableByteCount()) > maxBytes) {
                        DataBufferUtils.release(buffer);
                        sink.error(new PayloadTooLargeException(maxBytes));
                    } else {
                        sink.next(buffer);
                    }
                });
            }
        };
        return chain.filter(exchange.mutate().request(counted).build());
    }

    static Mono<Void> reject(ServerWebExchange exchange, ErrorResponseWriter errorWriter, long maxBytes) {
        SecurityEventLog.record(exchange, 413, "MAX_BODY_SIZE");
        return errorWriter.write(exchange, GatewayErrorCode.PAYLOAD_TOO_LARGE, "요청 본문이 너무 큽니다.",
            List.of(ErrorDetail.of("body", String.valueOf(maxBytes), "MAX_BODY_SIZE")));
    }

    @Override
    public int getOrder() {
        return TraceWebFilter.ORDER + 2; // PathGuardWebFilter(+1) 다음, Spring Security(-100)보다 앞
    }

    /** chunked 본문이 한도를 넘었을 때. {@link GatewayErrorHandler}가 413으로 바꾼다 */
    public static class PayloadTooLargeException extends RuntimeException {

        private final long maxBytes;

        public PayloadTooLargeException(long maxBytes) {
            super("request body exceeds " + maxBytes + " bytes", null, false, false);
            this.maxBytes = maxBytes;
        }

        public long maxBytes() {
            return maxBytes;
        }
    }
}

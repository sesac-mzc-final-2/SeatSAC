package com.seatsac.gateway.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.common.core.error.ErrorCode;
import com.seatsac.common.core.error.ErrorDetail;
import com.seatsac.common.core.error.ErrorResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Gateway가 직접 만드는 오류(400·401·403·404·503)를 공통 형식 { code, message, traceId, details[] }로 쓴다. */
@Component
public class ErrorResponseWriter {

    private final ObjectMapper objectMapper;

    public ErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Mono<Void> write(ServerWebExchange exchange, ErrorCode code, String message, List<ErrorDetail> details) {
        return write(exchange, HttpStatus.valueOf(code.status()), code, message, details);
    }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, ErrorCode code, String message,
                            List<ErrorDetail> details) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(
                ErrorResponse.of(code, message, TraceWebFilter.traceId(exchange), details));
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().setContentLength(body.length);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}

package com.seatsac.booking.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.booking.idempotency.IdempotencyRequestAdvice.Pending;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * BKG-11 멱등 처리의 출구 — 처리권을 잡았던 요청의 <b>첫 응답</b>을 키에 저장한다.
 *
 * <p>오류 응답도 {@code GlobalExceptionHandler}를 지나 여기로 오기 때문에 4xx까지 그대로 저장된다
 * (같은 키로 다시 오면 같은 거절을 받는다). 5xx는 저장하지 않고 키를 지워 재시도를 허용한다 (C11).
 *
 * <p>{@code supports}가 항상 참인 이유: 오류 응답을 쓰는 시점의 반환 타입은 핸들러가 아니라
 * 예외 처리기의 것이라 {@link Idempotent}를 볼 수 없다. 대신 요청 속성이 있을 때만 움직인다.
 */
@Slf4j
@ControllerAdvice
@RequiredArgsConstructor
public class IdempotencyResponseAdvice implements ResponseBodyAdvice<Object> {

    private final IdempotencyStore store;
    private final ObjectMapper mapper;

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)
            || !(response instanceof ServletServerHttpResponse servletResponse)) {
            return body;
        }

        HttpServletRequest raw = servletRequest.getServletRequest();
        if (!(raw.getAttribute(IdempotencyRequestAdvice.PENDING_ATTRIBUTE) instanceof Pending pending)) {
            return body;
        }
        raw.removeAttribute(IdempotencyRequestAdvice.PENDING_ATTRIBUTE);

        int status = servletResponse.getServletResponse().getStatus();
        if (status >= 500) {
            store.discard(pending.userId(), pending.key(), pending.owner());
            return body;
        }

        try {
            store.complete(pending.userId(), pending.key(), pending.owner(),
                pending.requestHash(), status, mapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            // 저장만 못 했다. 응답은 그대로 내보내고 키를 지워 재시도 때 다시 실행되게 한다
            log.warn("멱등 응답을 직렬화할 수 없다 — 키를 지운다 status={}", status, e);
            store.discard(pending.userId(), pending.key(), pending.owner());
        }
        return body;
    }
}

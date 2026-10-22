package com.seatsac.booking.idempotency;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 저장해 둔 첫 응답을 그대로 내보낸다 — 본문은 이미 JSON 문자열이라 다시 만들지 않는다.
 * 공통 {@code GlobalExceptionHandler}의 {@code Exception} 처리기에 잡히지 않도록 가장 앞에 둔다.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class IdempotencyReplayHandler {

    @ExceptionHandler(IdempotentReplayException.class)
    public ResponseEntity<String> replay(IdempotentReplayException e) {
        log.info("멱등 재요청 — 저장된 응답을 돌려준다 status={}", e.httpStatus());
        return ResponseEntity.status(e.httpStatus())
            .contentType(MediaType.APPLICATION_JSON)
            .body(e.body());
    }
}

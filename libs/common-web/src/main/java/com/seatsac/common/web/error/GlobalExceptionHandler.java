package com.seatsac.common.web.error;

import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.ErrorCode;
import com.seatsac.common.core.error.ErrorResponse;
import com.seatsac.common.web.trace.TraceIdFilter;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 모든 예외를 { code, message, traceId, details } 로 바꾼다 (API 명세서 1-4). */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> business(BusinessException e) {
        return ResponseEntity.status(e.code().status())
            .body(ErrorResponse.of(e.code(), e.getMessage(), TraceIdFilter.current(), e.details()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException e) {
        List<Map<String, String>> details = e.getBindingResult().getFieldErrors().stream()
            .map(f -> Map.of("field", f.getField(), "reason", String.valueOf(f.getDefaultMessage())))
            .toList();
        return ResponseEntity.badRequest()
            .body(ErrorResponse.of(ErrorCode.VALIDATION_FAILED, "요청 값이 올바르지 않습니다.", TraceIdFilter.current(), details));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse.of(ErrorCode.NOT_FOUND, "리소스가 없습니다.", TraceIdFilter.current()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unknown(Exception e) {
        log.error("unhandled traceId={}", TraceIdFilter.current(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR, "서버 오류입니다.", TraceIdFilter.current()));
    }
}

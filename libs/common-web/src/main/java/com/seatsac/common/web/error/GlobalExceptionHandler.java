package com.seatsac.common.web.error;

import com.seatsac.common.core.error.BusinessException;
<<<<<<< HEAD
import com.seatsac.common.core.error.ErrorCode;
=======
import com.seatsac.common.core.error.CommonResponseCode;
>>>>>>> origin/main
import com.seatsac.common.core.error.ErrorDetail;
import com.seatsac.common.core.error.ErrorResponse;
import com.seatsac.common.web.trace.TraceIdFilter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

<<<<<<< HEAD
/** 모든 예외를 { code, message, traceId, details[] } 로 바꾼다 (API 명세서 1-4, error.v1.json). */
=======
/**
 * 모든 예외를 {@code {code, message, traceId, details[]}}로 바꾼다 (API 명세서 1-4).
 * 응답 {@code code}는 공통 코드, 세분 이름은 {@code details[].reason}·MDC {@code errorCode}에 간다.
 * 로그·MDC 규칙은 {@link ErrorResponder}가 담당한다.
 */
>>>>>>> origin/main
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ErrorResponder responder;

    public GlobalExceptionHandler(ErrorResponder responder) {
        this.responder = responder;
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> business(BusinessException e) {
        return responder.respond(e.code(), e.getMessage(), e.details(), e, false);
    }

    /** 본문 검증 실패. {@code value}에는 사용자 입력을 담지 않는다 (카드 번호 등 민감값) */
    @ExceptionHandler(MethodArgumentNotValidException.class)
<<<<<<< HEAD
    public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException e) {
        // value(rejectedValue)는 넣지 않는다: 카드 번호 등 민감 값이 응답·로그에 남을 수 있음
        List<ErrorDetail> details = e.getBindingResult().getFieldErrors().stream()
            .map(f -> ErrorDetail.of(f.getField(), String.valueOf(f.getDefaultMessage())))
            .toList();
        return ResponseEntity.badRequest()
            .body(ErrorResponse.of(ErrorCode.VALIDATION_FAILED, "요청 값이 올바르지 않습니다.", TraceIdFilter.current(), details));
=======
    public ResponseEntity<ErrorResponse> bodyValidation(MethodArgumentNotValidException e) {
        return badRequest(e.getBindingResult().getFieldErrors().stream()
            .map(f -> ErrorDetail.of(f.getField(), null, f.getCode()))
            .toList(), e);
    }

    /** 파라미터·경로 변수 검증 실패 (Spring 6.1 메서드 검증) */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> methodValidation(HandlerMethodValidationException e) {
        return badRequest(e.getParameterValidationResults().stream()
            .map(r -> ErrorDetail.of(r.getMethodParameter().getParameterName(), null, "INVALID_VALUE"))
            .toList(), e);
    }

    /** 필수 헤더 누락 — 예: Idempotency-Key. 핸들러가 없으면 500으로 나간다 */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> missingHeader(MissingRequestHeaderException e) {
        return badRequest(List.of(ErrorDetail.required(e.getHeaderName())), e);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> missingParameter(MissingServletRequestParameterException e) {
        return badRequest(List.of(ErrorDetail.required(e.getParameterName())), e);
    }

    /** 경로·쿼리 값의 타입이 틀림 (예: 예매 ID에 문자) */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> typeMismatch(MethodArgumentTypeMismatchException e) {
        return badRequest(List.of(ErrorDetail.of(e.getName(), null, "INVALID_TYPE")), e);
    }

    /** 본문이 비었거나 JSON이 깨짐 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> notReadable(HttpMessageNotReadableException e) {
        return badRequest(List.of(ErrorDetail.reason("MALFORMED_BODY")), e);
>>>>>>> origin/main
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(NoResourceFoundException e) {
        return responder.respond(CommonResponseCode.NOT_FOUND, List.of(), e);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return responder.respond(CommonResponseCode.METHOD_NOT_ALLOWED,
            List.of(ErrorDetail.of("method", e.getMethod(), "METHOD_NOT_ALLOWED")), e);
    }

    /**
     * SSE·비동기 요청이 타임아웃으로 끊긴 것 — 오류가 아니다. 본문 없이 DEBUG로만 남긴다
     * (끊길 때마다 ERROR 500이 쌓이는 것을 막는다). 상태는 Spring 기본값과 같은 503.
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<Void> asyncTimeout(AsyncRequestTimeoutException e) {
        log.debug("async request timed out traceId={}", TraceIdFilter.current());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unknown(Exception e) {
        return responder.respond(CommonResponseCode.INTERNAL_ERROR,
            CommonResponseCode.INTERNAL_ERROR.message(), List.of(), e, true);
    }

    private ResponseEntity<ErrorResponse> badRequest(List<ErrorDetail> details, Exception cause) {
        return responder.respond(CommonResponseCode.VALIDATION_FAILED, details, cause);
    }
}

package com.seatsac.booking.controller;

import com.seatsac.common.web.trace.TraceIdFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * 화면이 떠나 끊긴 좌석 SSE 연결(BKG-05) — 오류가 아니다.
 *
 * <p>결제 화면으로 넘어가거나 탭을 닫으면 다음 하트비트·이벤트 전송이 실패하고, 서블릿 컨테이너의
 * 오류 알림을 Spring이 {@code AsyncRequestNotUsableException}으로 바꿔 예외 처리기에 넘긴다.
 * 공통 {@code GlobalExceptionHandler}의 {@code Exception} 처리기가 받으면 「status=500」 ERROR로 남아
 * 진짜 장애 로그를 가리므로 그보다 먼저 받는다. 응답은 이미 쓸 수 없어 본문 없이 끝내고
 * DEBUG로만 남긴다(공통 처리기의 {@code AsyncRequestTimeoutException}과 같은 방식). 끊긴 연결은
 * 지표 {@code booking_sse_connections}가 줄어드는 것으로 보인다.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class SseDisconnectHandler {

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void disconnected(AsyncRequestNotUsableException e) {
        log.debug("SSE 클라이언트 연결 끊김 traceId={} cause={}", TraceIdFilter.current(), e.getMessage());
    }
}

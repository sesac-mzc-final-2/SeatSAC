package com.seatsac.common.web.trace;

import com.seatsac.common.core.http.Headers;
import com.seatsac.common.core.trace.TraceIds;
import com.seatsac.common.core.trace.TraceKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code X-Trace-Id}가 <b>소문자 hex 32자리</b>면 그대로 쓰고, 없거나 형식이 다르면 새로 만든다
 * (API 명세서 12.2). MDC {@code traceId}에 넣어 JSON 로그에 찍히고, 응답 헤더로 같은 값을 돌려준다.
 */
public class TraceIdFilter extends OncePerRequestFilter {

    /** @deprecated {@link TraceKeys#TRACE_ID}를 쓴다 */
    @Deprecated(since = "2026-10-21")
    public static final String MDC_KEY = TraceKeys.MDC_TRACE_ID;

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /** 요청 밖(만료 스캔·릴레이)이나 필터 전 오류에서도 null을 돌려주지 않는다. */
    public static String current() {
        String id = CURRENT.get();
        return id != null ? id : MDC.get(TraceKeys.MDC_TRACE_ID);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String traceId = req.getHeader(Headers.TRACE_ID);
        if (!TraceIds.isValid(traceId)) {
            // 빈 값·대문자·하이픈 붙은 UUID(36자)·외부에서 주입한 문자열은 버린다
            traceId = TraceIds.newId();
        }
        CURRENT.set(traceId);
        MDC.put(TraceKeys.MDC_TRACE_ID, traceId);
        String userId = req.getHeader(Headers.USER_ID);
        if (userId != null) {
            MDC.put(TraceKeys.MDC_USER_ID, userId);
        }
        res.setHeader(Headers.TRACE_ID, traceId);
        try {
            chain.doFilter(req, res);
        } finally {
            // 스레드 풀에서 재사용되므로 남은 키(errorCode·job·bookingId 등)까지 모두 지운다
            MDC.clear();
            CURRENT.remove();
        }
    }
}

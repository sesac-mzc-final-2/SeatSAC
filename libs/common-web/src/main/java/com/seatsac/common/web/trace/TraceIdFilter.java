package com.seatsac.common.web.trace;

import com.seatsac.common.core.http.Headers;
import com.seatsac.common.core.trace.TraceIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * X-Trace-Id 가 규약 형식(소문자 16진수 32자리)이면 쓰고, 없거나 형식이 틀리면 새로 만든다.
 * MDC("traceId")에 넣어 JSON 로그에 찍히고, 응답 헤더로 돌려준다. 형식은 TraceIds(ADR-55).
 */
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "traceId";
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /** 요청 밖(만료 스캔·릴레이)이나 필터 전 오류에서도 null을 돌려주지 않는다. */
    public static String current() {
        String id = CURRENT.get();
        return TraceIds.orNew(id != null ? id : MDC.get(MDC_KEY));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String traceId = TraceIds.orNew(req.getHeader(Headers.TRACE_ID));
        CURRENT.set(traceId);
        MDC.put(MDC_KEY, traceId);
        String userId = req.getHeader(Headers.USER_ID);
        if (userId != null) {
            MDC.put("userId", userId);
        }
        res.setHeader(Headers.TRACE_ID, traceId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KEY);
            MDC.remove("userId");
            CURRENT.remove();
        }
    }
}

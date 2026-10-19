package com.seatsac.common.web.trace;

import com.seatsac.common.core.http.Headers;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** X-Trace-Id 가 있으면 쓰고 없으면 만든다. MDC("traceId")에 넣어 JSON 로그에 찍히고, 응답 헤더로 돌려준다. */
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "traceId";
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    public static String current() {
        String id = CURRENT.get();
        return id != null ? id : MDC.get(MDC_KEY);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String traceId = req.getHeader(Headers.TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
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

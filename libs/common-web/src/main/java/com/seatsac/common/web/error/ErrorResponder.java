package com.seatsac.common.web.error;

import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.ErrorCode;
import com.seatsac.common.core.error.ErrorDetail;
import com.seatsac.common.core.error.ErrorResponse;
import com.seatsac.common.core.trace.TraceKeys;
import com.seatsac.common.web.trace.TraceIdFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;

/**
 * 오류 응답을 만드는 한 곳. MDC 설정 → 레벨별 로그 → 응답 → MDC 정리를 묶는다.
 * 모든 예외 핸들러가 이 클래스를 지나므로 로그 규칙(명세 12.2)이 한 곳에서 지켜진다.
 *
 * <table>
 *   <tr><td>4xx</td><td>INFO, 스택 없음. 검증 실패는 <b>필드 이름만</b></td></tr>
 *   <tr><td>BusinessException 5xx (의도한 503)</td><td>WARN, 스택 없음. 원인은 클래스명·메시지 한 줄</td></tr>
 *   <tr><td>처리되지 않은 예외 (500)</td><td>ERROR + 스택</td></tr>
 * </table>
 */
public class ErrorResponder {

    private static final Logger log = LoggerFactory.getLogger(ErrorResponder.class);

    public ResponseEntity<ErrorResponse> respond(ErrorCode code, List<ErrorDetail> details, Throwable cause) {
        return respond(code, code.message(), details, cause, false);
    }

    public ResponseEntity<ErrorResponse> respond(ErrorCode code, String message, List<ErrorDetail> details,
                                                 Throwable cause, boolean unhandled) {
        int status = code.status();
        MDC.put(TraceKeys.MDC_ERROR_CODE, code.name());
        try {
            if (unhandled) {
                log.error("status={} code={} responseCode={} message={}",
                    status, code.name(), code.responseCode(), message, cause);
            } else if (status >= 500) {
                log.warn("status={} code={} responseCode={} message={} cause={}",
                    status, code.name(), code.responseCode(), message, summarize(cause));
            } else {
                log.info("status={} code={} responseCode={} message={} fields={}",
                    status, code.name(), code.responseCode(), message, fieldsOf(details));
            }
            return ResponseEntity.status(status)
                .body(ErrorResponse.of(code, message, TraceIdFilter.current(), withReason(code, details)));
        } finally {
            MDC.remove(TraceKeys.MDC_ERROR_CODE);
        }
    }

    /** 세분 이름을 담은 원소가 없으면 하나 넣는다 — details는 항상 비지 않은 배열 (문서 2장) */
    private static List<ErrorDetail> withReason(ErrorCode code, List<ErrorDetail> details) {
        if (details.stream().anyMatch(d -> code.name().equals(d.reason()))) {
            return details;
        }
        List<ErrorDetail> body = new ArrayList<>(details);
        body.add(ErrorDetail.reason(code.name()));
        return body;
    }

    /** 검증 실패 로그에는 필드 이름만 남긴다 (값은 민감할 수 있다) */
    private static List<String> fieldsOf(List<ErrorDetail> details) {
        return details.stream().map(ErrorDetail::field).filter(Objects::nonNull).toList();
    }

    /** 스택 없이 원인 한 줄 */
    private static String summarize(Throwable cause) {
        if (cause == null || cause instanceof BusinessException) {
            return "-";
        }
        Throwable root = cause.getCause() != null ? cause.getCause() : cause;
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}

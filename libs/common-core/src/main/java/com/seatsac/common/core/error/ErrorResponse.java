package com.seatsac.common.core.error;

import com.seatsac.common.core.trace.TraceIds;
import java.util.List;

/**
 * 오류 응답 본문 { code, message, traceId, details[] } — contracts/schemas/error.v1.json 과 같아야 한다.
 * details는 항상 배열(없으면 []), traceId는 항상 32자리 16진수(형식이 틀리거나 없으면 새로 만든다).
 */
public record ErrorResponse(String code, String message, String traceId, List<ErrorDetail> details) {

    public ErrorResponse {
        traceId = TraceIds.orNew(traceId);
        details = details == null ? List.of() : List.copyOf(details);
    }

    public static ErrorResponse of(ErrorCode code, String message, String traceId) {
        return new ErrorResponse(code.name(), message, traceId, List.of());
    }

    public static ErrorResponse of(ErrorCode code, String message, String traceId, List<ErrorDetail> details) {
        return new ErrorResponse(code.name(), message, traceId, details);
    }
}

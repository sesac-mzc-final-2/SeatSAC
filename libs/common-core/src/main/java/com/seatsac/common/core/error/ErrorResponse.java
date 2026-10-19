package com.seatsac.common.core.error;

/** 오류 응답 본문 { code, message, traceId, details? } — contracts/schemas/error.v1.json 과 같아야 한다. */
public record ErrorResponse(String code, String message, String traceId, Object details) {

    public static ErrorResponse of(ErrorCode code, String message, String traceId) {
        return new ErrorResponse(code.name(), message, traceId, null);
    }

    public static ErrorResponse of(ErrorCode code, String message, String traceId, Object details) {
        return new ErrorResponse(code.name(), message, traceId, details);
    }
}

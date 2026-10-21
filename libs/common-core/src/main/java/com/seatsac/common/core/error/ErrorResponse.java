package com.seatsac.common.core.error;

import java.util.List;

/**
 * 오류 응답 본문 {@code {code, message, traceId, details[]}} — contracts/schemas/error.v1.json 과
 * 같아야 한다. {@code code}는 <b>공통 코드</b>({@link ErrorCode#responseCode()})이고 세분 이름은
 * {@code details[].reason}에 담는다. {@code details}는 항상 배열이며 없으면 빈 배열이다.
 */
public record ErrorResponse(String code, String message, String traceId, List<ErrorDetail> details) {

    public ErrorResponse {
        details = details == null ? List.of() : List.copyOf(details);
    }

    public static ErrorResponse of(ErrorCode code, String traceId) {
        return of(code, code.message(), traceId, List.of());
    }

    public static ErrorResponse of(ErrorCode code, String message, String traceId) {
        return of(code, message, traceId, List.of());
    }

    public static ErrorResponse of(ErrorCode code, String message, String traceId, List<ErrorDetail> details) {
        return new ErrorResponse(code.responseCode(), message, traceId, details);
    }
}

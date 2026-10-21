package com.seatsac.common.core.error;

import java.util.List;

/**
 * 서비스 코드가 던지는 예외. common-web의 {@code GlobalExceptionHandler}가 {@code ErrorResponse}로 바꾼다.
 * 메시지를 따로 주지 않으면 {@link ErrorCode#message()}를 쓴다. {@code details}는 항상 배열이다.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode code;
    private final transient List<ErrorDetail> details;

    public BusinessException(ErrorCode code) {
        this(code, code.message(), List.of());
    }

    public BusinessException(ErrorCode code, List<ErrorDetail> details) {
        this(code, code.message(), details);
    }

    public BusinessException(ErrorCode code, String message) {
        this(code, message, List.of());
    }

    public BusinessException(ErrorCode code, String message, List<ErrorDetail> details) {
        super(message);
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public ErrorCode code() {
        return code;
    }

    public List<ErrorDetail> details() {
        return details;
    }
}

package com.seatsac.common.core.error;

import java.util.List;

/** 서비스 코드가 던지는 예외. common-web의 GlobalExceptionHandler가 ErrorResponse로 바꾼다. */
public class BusinessException extends RuntimeException {

    private final ErrorCode code;
    private final transient List<ErrorDetail> details;

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

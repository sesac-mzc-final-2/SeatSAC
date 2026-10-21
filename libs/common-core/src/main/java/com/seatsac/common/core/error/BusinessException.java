package com.seatsac.common.core.error;

import java.util.List;

/**
 * 서비스 코드가 던지는 예외. common-web의 {@code GlobalExceptionHandler}가 {@code ErrorResponse}로 바꾼다.
 * 메시지를 따로 주지 않으면 {@link ErrorCode#message()}를 쓴다. {@code details}는 항상 배열이다.
 *
 * <p>다른 예외를 감싸서 던질 때(예: {@code InternalRestClientFactory}가 타임아웃을 503으로 바꿀 때)는
 * {@code cause}를 받는 생성자를 쓴다. 의도한 5xx는 WARN 한 줄에 원인의 클래스명·메시지를 남기므로
 * 원인이 빠지면 로그에서 이유를 알 수 없다.
 */
public class BusinessException extends RuntimeException {

    private final transient ErrorCode code;
    private final transient List<ErrorDetail> details;

    public BusinessException(ErrorCode code) {
        this(code, code.message(), List.of(), null);
    }

    public BusinessException(ErrorCode code, List<ErrorDetail> details) {
        this(code, code.message(), details, null);
    }

    public BusinessException(ErrorCode code, String message) {
        this(code, message, List.of(), null);
    }

    public BusinessException(ErrorCode code, String message, List<ErrorDetail> details) {
        this(code, message, details, null);
    }

    /** 다른 예외를 감싼다. 메시지는 {@link ErrorCode#message()} */
    public BusinessException(ErrorCode code, Throwable cause) {
        this(code, code.message(), List.of(), cause);
    }

    /** 다른 예외를 감싼다. 메시지는 {@link ErrorCode#message()} */
    public BusinessException(ErrorCode code, List<ErrorDetail> details, Throwable cause) {
        this(code, code.message(), details, cause);
    }

    public BusinessException(ErrorCode code, String message, List<ErrorDetail> details, Throwable cause) {
        super(message, cause);
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

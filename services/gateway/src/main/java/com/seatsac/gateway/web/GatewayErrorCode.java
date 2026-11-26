package com.seatsac.gateway.web;

import com.seatsac.common.core.error.CommonResponseCode;
import com.seatsac.common.core.error.ErrorCode;

/**
 * gateway만 내는 응답 코드. 공통 코드({@link CommonResponseCode})에 같은 HTTP 상태가 없어 응답 {@code code}·상태를 직접 정한다
 * (ErrorCode 의 「문자열 responseCode() 직접 구현」 방식). {@code libs/}의 공통 enum은 바꾸지 않는다 — 413은 gateway에서만 나온다.
 * 규약 6장 공통 오류 코드 표의 413 {@code PAYLOAD_TOO_LARGE}(ADR-124).
 */
public enum GatewayErrorCode implements ErrorCode {

    PAYLOAD_TOO_LARGE(413, "요청 본문이 너무 큽니다.");

    private final int status;
    private final String message;

    GatewayErrorCode(int status, String message) {
        this.status = status;
        this.message = message;
    }

    /** 같은 상태의 공통 코드가 없다 — 응답은 {@link #responseCode()}·{@link #status()}만 쓴다 */
    @Override
    public CommonResponseCode commonCode() {
        throw new UnsupportedOperationException(name() + " 는 gateway 전용 코드라 공통 코드가 없다");
    }

    @Override
    public String responseCode() {
        return name();
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String message() {
        return message;
    }
}

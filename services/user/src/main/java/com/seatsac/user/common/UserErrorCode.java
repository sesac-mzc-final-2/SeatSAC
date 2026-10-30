package com.seatsac.user.common;

import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.CommonResponseCode;
import com.seatsac.common.core.error.ErrorCode;
import com.seatsac.common.core.error.ErrorDetail;
import java.util.Arrays;
import java.util.List;

/**
 * user의 세분 오류 이름. enum 이름은 {@code details[].reason}·MDC {@code errorCode}에, 응답 {@code code}는
 * {@link #commonCode()}에서 나온다(공통 모듈 규칙). 던질 때는 {@code UserErrorCode.IDENTITY_UNAVAILABLE.exception()}.
 */
public enum UserErrorCode implements ErrorCode {

    // 401 UNAUTHORIZED — 프로필을 처음 만들 때 Access Token이 필요하다 (Cognito GetUser)
    TOKEN_REQUIRED(CommonResponseCode.UNAUTHORIZED, "인증 정보가 없습니다."),
    IDENTITY_MISMATCH(CommonResponseCode.UNAUTHORIZED, "인증 정보가 사용자와 맞지 않습니다."),

    // 403 FORBIDDEN — Cognito GetUser가 토큰을 거절(scope 누락·폐기). 401이면 화면이 갱신·로그인을 자동 반복한다 (규약 6장)
    TOKEN_REJECTED(CommonResponseCode.FORBIDDEN, "인증 정보로 사용자 정보를 가져올 수 없습니다. 다시 로그인해 주세요."),

    // 400 VALIDATION_FAILED — USR-05 선호 극장
    DUPLICATE_RANK(CommonResponseCode.VALIDATION_FAILED, "선호 극장 순위가 중복됩니다."),
    DUPLICATE_THEATER(CommonResponseCode.VALIDATION_FAILED, "같은 극장을 두 번 고를 수 없습니다."),
    THEATER_NOT_FOUND(CommonResponseCode.VALIDATION_FAILED, "없는 극장입니다."),

    // 503 — 규약 6장 공통 오류 코드 표의 user 503 두 가지. 세분 이름 = 공통 코드
    IDENTITY_UNAVAILABLE(CommonResponseCode.IDENTITY_UNAVAILABLE, CommonResponseCode.IDENTITY_UNAVAILABLE.message()),
    BOOKING_UNAVAILABLE(CommonResponseCode.BOOKING_UNAVAILABLE, CommonResponseCode.BOOKING_UNAVAILABLE.message());

    private final CommonResponseCode commonCode;
    private final String message;

    UserErrorCode(CommonResponseCode commonCode, String message) {
        this.commonCode = commonCode;
        this.message = message;
    }

    @Override
    public CommonResponseCode commonCode() {
        return commonCode;
    }

    @Override
    public String message() {
        return message;
    }

    public BusinessException exception() {
        return new BusinessException(this, message, List.of());
    }

    public BusinessException exception(ErrorDetail... details) {
        return new BusinessException(this, message, Arrays.asList(details));
    }

    public BusinessException exception(List<ErrorDetail> details) {
        return new BusinessException(this, message, details);
    }

    /** 다른 예외를 감싼다 — 의도한 5xx의 WARN 로그에 원인 한 줄이 남는다 */
    public BusinessException exception(Throwable cause) {
        return new BusinessException(this, message, List.of(), cause);
    }
}

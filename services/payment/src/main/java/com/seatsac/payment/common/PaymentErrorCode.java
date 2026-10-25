package com.seatsac.payment.common;

import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.CommonResponseCode;
import com.seatsac.common.core.error.ErrorCode;
import com.seatsac.common.core.error.ErrorDetail;
import java.util.Arrays;
import java.util.List;

/**
 * payment 오류 코드 — 응답 {@code code}는 공통 코드, 세분 이름은 {@code details[].reason}이다
 * (명세 1·4장). 화면은 공통 코드로 분기하고 운영·로그는 세분 이름을 본다.
 */
public enum PaymentErrorCode implements ErrorCode {

    // 400 — 형식·금액 규칙
    INVALID_PAYMENT_ID(CommonResponseCode.VALIDATION_FAILED, "결제 ID 형식이 올바르지 않습니다."),
    INVALID_AMOUNT(CommonResponseCode.VALIDATION_FAILED, "결제 금액이 올바르지 않습니다."),
    INVALID_CARD(CommonResponseCode.VALIDATION_FAILED, "카드 정보가 올바르지 않습니다."),
    INVALID_METHOD(CommonResponseCode.VALIDATION_FAILED, "지원하지 않는 결제 수단입니다."),
    INVALID_CANCEL_REASON(CommonResponseCode.VALIDATION_FAILED, "취소 사유가 올바르지 않습니다."),

    // 404
    PAYMENT_NOT_FOUND(CommonResponseCode.NOT_FOUND, "결제를 찾을 수 없습니다."),

    // 409 — 같은 결제 ID에 다른 내용 / 같은 예매에 다른 결제
    PAYMENT_ID_CONFLICT(CommonResponseCode.PAYMENT_CONFLICT, "같은 결제 ID로 다른 요청이 왔습니다."),
    PAYMENT_ALREADY_EXISTS(CommonResponseCode.PAYMENT_CONFLICT, "이미 결제를 요청한 예매입니다."),

    // 409 — 승인 상태가 아니라 취소할 수 없다
    PAYMENT_PROCESSING(CommonResponseCode.PAYMENT_NOT_APPROVED, "결제 결과를 아직 알 수 없습니다."),
    PAYMENT_FAILED(CommonResponseCode.PAYMENT_NOT_APPROVED, "거절된 결제는 취소할 수 없습니다.");

    private final CommonResponseCode responseCode;
    private final String message;

    PaymentErrorCode(CommonResponseCode responseCode, String message) {
        this.responseCode = responseCode;
        this.message = message;
    }

    @Override
    public int status() {
        return responseCode.status();
    }

    @Override
    public String responseCode() {
        return responseCode.responseCode();
    }

    @Override
    public String message() {
        return message;
    }

    public BusinessException exception() {
        return new BusinessException(this, message, List.of(ErrorDetail.reason(name())));
    }

    public BusinessException exception(ErrorDetail... details) {
        return new BusinessException(this, message, Arrays.asList(details));
    }
}

package com.seatsac.payment.common;

import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.CommonResponseCode;
import com.seatsac.common.core.error.ErrorCode;
import com.seatsac.common.core.error.ErrorDetail;
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
    INVALID_ORDER_NAME(CommonResponseCode.VALIDATION_FAILED, "주문명이 올바르지 않습니다."),
    INVALID_CANCEL_REASON(CommonResponseCode.VALIDATION_FAILED, "취소 사유가 올바르지 않습니다."),
    INVALID_WEBHOOK(CommonResponseCode.VALIDATION_FAILED, "웹훅 본문이 올바르지 않습니다."),

    // 401 — 포트원 웹훅 서명
    WEBHOOK_SIGNATURE_INVALID(CommonResponseCode.UNAUTHORIZED, "웹훅 서명이 올바르지 않습니다."),

    // 404
    PAYMENT_NOT_FOUND(CommonResponseCode.NOT_FOUND, "결제를 찾을 수 없습니다."),

    // 409 — 같은 결제 ID에 다른 내용 / 같은 예매에 다른 결제 / MOCK 행은 검증 대상이 아님
    PAYMENT_ID_CONFLICT(CommonResponseCode.PAYMENT_CONFLICT, "같은 결제 ID로 다른 요청이 왔습니다."),
    PAYMENT_ALREADY_EXISTS(CommonResponseCode.PAYMENT_CONFLICT, "이미 결제를 요청한 예매입니다."),
    NOT_VERIFIABLE(CommonResponseCode.PAYMENT_CONFLICT, "포트원 결제가 아니라 확인할 수 없습니다."),

    // 409 — 승인 상태가 아니라 취소할 수 없다
    PAYMENT_PROCESSING(CommonResponseCode.PAYMENT_NOT_APPROVED, "결제 결과를 아직 알 수 없습니다."),
    PAYMENT_FAILED(CommonResponseCode.PAYMENT_NOT_APPROVED, "거절된 결제는 취소할 수 없습니다."),

    // 503 — 포트원 (ADR-112)
    PORTONE_DISABLED(CommonResponseCode.SERVICE_UNAVAILABLE, "포트원 결제가 꺼져 있습니다."),
    PORTONE_UNAVAILABLE(CommonResponseCode.SERVICE_UNAVAILABLE, "포트원 응답을 받지 못했습니다."),
    CANCEL_PENDING(CommonResponseCode.SERVICE_UNAVAILABLE, "포트원이 취소를 처리하는 중입니다. 잠시 후 다시 확인해 주세요.");

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
        return new BusinessException(this, message, List.of(details));
    }
}

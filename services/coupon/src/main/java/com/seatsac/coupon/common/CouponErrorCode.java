package com.seatsac.coupon.common;

import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.CommonResponseCode;
import com.seatsac.common.core.error.ErrorCode;
import com.seatsac.common.core.error.ErrorDetail;
import java.util.List;

/**
 * coupon 오류 코드. 응답 {@code code}는 계약(coupon.openapi.yaml · conventions 6장) 그대로다 —
 * coupon 전용 코드(COUPON_*)는 공통 {@link CommonResponseCode}에 없어서 여기서 상태·문자열을 직접 정한다
 * ({@code libs/}는 바꾸지 않는다). 공통 코드로 나가는 것은 {@code commonCode}에서 나온다.
 */
public enum CouponErrorCode implements ErrorCode {
    // 400
    INVALID_IDEMPOTENCY_KEY(CommonResponseCode.VALIDATION_FAILED, "Idempotency-Key 형식이 올바르지 않습니다."),
    STARTS_IN_PAST(CommonResponseCode.VALIDATION_FAILED, "시작 시각이 이미 지났습니다."),
    EVENT_OVERLAP(CommonResponseCode.VALIDATION_FAILED, "다른 이벤트와 기간이 겹칩니다."),

    // 403 — VIP 아님
    COUPON_NOT_ELIGIBLE(403, "COUPON_NOT_ELIGIBLE", CommonResponseCode.FORBIDDEN, "VIP 등급만 받을 수 있습니다"),

    // 404
    EVENT_NOT_FOUND(CommonResponseCode.NOT_FOUND, "쿠폰 이벤트를 찾을 수 없습니다."),
    NO_CURRENT_EVENT(CommonResponseCode.NOT_FOUND, "진행 중이거나 진행 예정인 쿠폰 이벤트가 없습니다."),

    // 409 — 발급
    COUPON_EVENT_NOT_OPEN(409, "COUPON_EVENT_NOT_OPEN", CommonResponseCode.BOOKING_STATE_CONFLICT, "이벤트 시간이 아닙니다"),
    COUPON_SOLD_OUT(409, "COUPON_SOLD_OUT", CommonResponseCode.BOOKING_STATE_CONFLICT, "쿠폰이 모두 소진됐습니다"),
    COUPON_ALREADY_CLAIMED(409, "COUPON_ALREADY_CLAIMED", CommonResponseCode.BOOKING_STATE_CONFLICT, "이미 받은 쿠폰입니다"),
    IDEMPOTENCY_KEY_CONFLICT(CommonResponseCode.IDEMPOTENCY_KEY_CONFLICT, "같은 키로 다른 요청을 보냈습니다."),
    IDEMPOTENCY_IN_PROGRESS(CommonResponseCode.IDEMPOTENCY_IN_PROGRESS, "같은 요청이 처리 중입니다. 잠시 후 다시 확인해 주세요."),

    // 503 — DB·Valkey 응답 없음
    STOCK_UNAVAILABLE(CommonResponseCode.SERVICE_UNAVAILABLE, "쿠폰 재고를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.");

    private final int status;
    private final String responseCode;
    private final CommonResponseCode commonCode;
    private final String message;

    CouponErrorCode(CommonResponseCode commonCode, String message) {
        this(commonCode.status(), commonCode.name(), commonCode, message);
    }

    CouponErrorCode(int status, String responseCode, CommonResponseCode commonCode, String message) {
        this.status = status;
        this.responseCode = responseCode;
        this.commonCode = commonCode;
        this.message = message;
    }

    @Override
    public int status() {
        return status;
    }

    /** 응답 {@code code}. COUPON_* 는 자기 이름, 나머지는 공통 코드 */
    @Override
    public String responseCode() {
        return responseCode;
    }

    /** 공통 코드가 필요한 곳(기본 구현 호환)을 위한 값 — COUPON_* 는 가까운 공통 코드 */
    @Override
    public CommonResponseCode commonCode() {
        return commonCode;
    }

    @Override
    public String message() {
        return message;
    }

    /** 세분 이름({@code details[].reason})은 공통 ErrorResponder가 넣는다 — details는 비지 않는 배열 */
    public BusinessException exception() {
        return new BusinessException(this, message, List.of());
    }

    public BusinessException exception(ErrorDetail... details) {
        return new BusinessException(this, message, List.of(details));
    }
}

package com.seatsac.booking.checkout;

import java.util.EnumSet;
import java.util.Set;

/**
 * 체크아웃 saga 상태(계약 {@code CheckoutState}, 설계 01 2장). 열 값은 enum 이름.
 * 진행 중 목록을 바꾸면 Flyway의 생성 열 {@code active_booking_id} 식도 새 V 파일로 같이 바꾼다.
 */
public enum CheckoutState {
    STARTED, COUPON_RESERVING, PAYMENT_PREPARING, READY_TO_PAY, CHARGING, VERIFYING, COMPENSATING,
    CONFIRMED, FAILED, EXPIRED, COUPON_REJECTED, PAYMENT_RETRYABLE;

    /** 진행 중 — {@code uk_checkout_active_booking}이 예매당 1건만 허용하는 상태 */
    public static final Set<CheckoutState> ACTIVE = EnumSet.of(STARTED, COUPON_RESERVING, PAYMENT_PREPARING,
        READY_TO_PAY, CHARGING, VERIFYING, COMPENSATING);

    /** 돈이 움직였을 수 있는 상태 — 결과를 모르면 보상하지 않고 재확인한다(D4) */
    public static final Set<CheckoutState> MONEY_MAY_MOVE = EnumSet.of(CHARGING, VERIFYING);

    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    /** 같은 예매로 다시 시작할 수 있는 끝(쿠폰 거절·재결제 대기) */
    public boolean isRetryable() {
        return this == COUPON_REJECTED || this == PAYMENT_RETRYABLE;
    }
}

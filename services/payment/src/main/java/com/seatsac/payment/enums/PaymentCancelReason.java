package com.seatsac.payment.enums;

/** 취소 사유 — Booking이 어느 경로로 취소를 불렀는지 (명세 3장) */
public enum PaymentCancelReason {

    /** 승인을 받았는데 그 사이 선점이 만료 (BKG-10) */
    HOLD_EXPIRED,

    /** 확정된 예매를 사용자가 취소 (BKG-07) */
    BOOKING_CANCELED,

    /** 결과를 확인하지 못한 결제를 만료 처리가 정리 (BKG-12) */
    STALE_PAYMENT
}

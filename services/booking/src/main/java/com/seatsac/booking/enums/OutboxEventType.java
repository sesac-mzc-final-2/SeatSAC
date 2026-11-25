package com.seatsac.booking.enums;

/** 아웃박스 이벤트 종류 — ERD 3.3. 컬럼 값은 enum 이름, SQS 메시지의 {@code type}은 {@link #messageType()}. */
public enum OutboxEventType {
    BOOKING_CONFIRMED("booking.confirmed"),
    /** 6.2.1 saga 명령(계약 contracts/events/payment.*-requested.v1.json, #176) */
    PAYMENT_PREPARATION_REQUESTED("payment.preparation-requested"),
    PAYMENT_CHARGE_REQUESTED("payment.charge-requested"),
    PAYMENT_VERIFICATION_REQUESTED("payment.verification-requested"),
    PAYMENT_RELEASE_REQUESTED("payment.release-requested");

    private final String messageType;

    OutboxEventType(String messageType) {
        this.messageType = messageType;
    }

    public String messageType() {
        return messageType;
    }
}

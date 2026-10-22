package com.seatsac.booking.enums;

/** 아웃박스 이벤트 종류 — ERD 3.3. 컬럼 값은 enum 이름, SQS 메시지의 {@code type}은 {@link #messageType()}. */
public enum OutboxEventType {
    BOOKING_CONFIRMED("booking.confirmed");

    private final String messageType;

    OutboxEventType(String messageType) {
        this.messageType = messageType;
    }

    public String messageType() {
        return messageType;
    }
}

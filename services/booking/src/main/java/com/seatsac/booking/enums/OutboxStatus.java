package com.seatsac.booking.enums;

/** 아웃박스 전송 상태 — ERD 3.3. SENDING은 점유 중(next_attempt_at = 점유 만료). */
public enum OutboxStatus {
    PENDING,
    SENDING,
    SENT,
    FAILED
}

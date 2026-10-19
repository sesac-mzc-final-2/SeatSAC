package com.seatsac.common.core.error;

/** 오류 코드 — API 명세서 1-4. 새 코드는 추가만 한다. */
public enum ErrorCode {
    VALIDATION_FAILED(400),
    SCREENING_STARTED(400),
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    SEAT_UNAVAILABLE(409),
    ACTIVE_HOLD_EXISTS(409),
    BOOKING_STATE_CONFLICT(409),
    PAYMENT_ALREADY_REQUESTED(409),
    IDEMPOTENCY_KEY_CONFLICT(409),
    SCHEDULE_CONFLICT(409),
    SEAT_SOLD(409),
    PAYMENT_CONFLICT(409),
    PAYMENT_NOT_APPROVED(409),
    RATE_LIMITED(429),
    INTERNAL_ERROR(500),
    SERVICE_UNAVAILABLE(503),
    PAYMENT_RESULT_UNKNOWN(503),
    BOOKING_UNAVAILABLE(503);

    private final int status;

    ErrorCode(int status) {
        this.status = status;
    }

    public int status() {
        return status;
    }
}

package com.seatsac.user.dto;

/** USR-04 내역 상태 — 계약 {@code /users/me/bookings?status}. PENDING은 내역이 아니라 받지 않는다(생략 시 PENDING 제외 전부) */
public enum BookingHistoryStatus {
    CONFIRMED, PAYMENT_FAILED, EXPIRED, CANCELED
}

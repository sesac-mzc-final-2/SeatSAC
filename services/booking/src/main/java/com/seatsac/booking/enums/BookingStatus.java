package com.seatsac.booking.enums;

/**
 * 예매 상태 — 명세 3장. DB는 VARCHAR(20), 매핑은 {@code @Enumerated(EnumType.STRING)}.
 * 전이는 모두 조건부 UPDATE(WHERE status = '현재 상태')로만 한다.
 */
public enum BookingStatus {
    /** 선점 중, 결제 대기. booking_seat 있음 */
    PENDING,
    /** 예매 확정. booking_seat 유지 */
    CONFIRMED,
    /** 결제 1회 실패로 자동 취소. booking_seat 삭제 */
    PAYMENT_FAILED,
    /** 선점 만료(5분). booking_seat 삭제 */
    EXPIRED,
    /** 사용자가 직접 취소(선점 해제·확정 후 취소). booking_seat 삭제 */
    CANCELED;

    /** 좌석을 점유하는 상태 — 좌석 현황·회차별 좌석 수 집계 대상 */
    public boolean occupiesSeat() {
        return this == PENDING || this == CONFIRMED;
    }

    /** 더 이상 전이하지 않는 상태 */
    public boolean isTerminal() {
        return this != PENDING;
    }
}

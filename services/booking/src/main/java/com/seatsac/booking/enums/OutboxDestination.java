package com.seatsac.booking.enums;

/**
 * 아웃박스 행의 보낼 곳(설계 04 3장). 릴레이가 이 값으로 발송 클라이언트를 고른다. 열 값은 enum 이름.
 * 기존 행·이전 버전 파드가 넣는 행은 {@link #NOTIFICATION}(열 기본값)이다.
 */
public enum OutboxDestination {
    /** 기존 알림 큐 {@code cinema-staging-notification-events} */
    NOTIFICATION,
    /** saga 명령 → {@code cinema-staging-payment-commands} */
    PAYMENT_COMMANDS,
    /** 6.2.4 SNS {@code cinema-staging-booking-events}(user 예매 내역 복제본, D28·D29) */
    BOOKING_EVENTS
}

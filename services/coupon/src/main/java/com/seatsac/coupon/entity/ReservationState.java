package com.seatsac.coupon.entity;

/**
 * 체크아웃 1건의 쿠폰 처리 결과(22-4). RESERVED 예약함 · USED 사용함 · RELEASED 반환함(또는 반환이 먼저 와서 기록함) ·
 * REJECTED 예약을 거절함(같은 checkoutId 재시도에 같은 거절을 다시 보낸다)
 */
public enum ReservationState {
    RESERVED, USED, RELEASED, REJECTED
}

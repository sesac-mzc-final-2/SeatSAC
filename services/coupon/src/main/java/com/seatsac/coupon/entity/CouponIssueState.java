package com.seatsac.coupon.entity;

/** ISSUED 받음 · RESERVED 체크아웃이 예약함 · USED 결제 승인으로 사용됨 · EXPIRED 이벤트 종료 */
public enum CouponIssueState {
    ISSUED, RESERVED, USED, EXPIRED
}

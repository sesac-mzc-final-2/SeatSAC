package com.seatsac.coupon.entity;

import java.time.Instant;

/** 서버 시각으로 계산한다 — 저장하지 않는다. UPCOMING now < startsAt · OPEN startsAt ≤ now < endsAt · CLOSED endsAt ≤ now */
public enum CouponEventState {
    UPCOMING, OPEN, CLOSED;

    public static CouponEventState of(Instant startsAt, Instant endsAt, Instant now) {
        if (now.isBefore(startsAt)) {
            return UPCOMING;
        }
        return now.isBefore(endsAt) ? OPEN : CLOSED;
    }
}

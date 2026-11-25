package com.seatsac.coupon.dto;

import com.seatsac.common.core.time.Times;
import com.seatsac.coupon.entity.CouponEvent;
import com.seatsac.coupon.entity.CouponIssue;
import com.seatsac.coupon.entity.CouponIssueState;
import java.time.Instant;
import java.time.OffsetDateTime;

/** 계약 {@code CouponIssue} — claim 201 응답·내 쿠폰 목록 원소 */
public record CouponIssueResponse(String couponIssueId, Long eventId, String title, int discountRate,
                                  CouponIssueState state, OffsetDateTime expiresAt) {

    public static CouponIssueResponse of(CouponIssue issue, CouponEvent event, Instant now) {
        return new CouponIssueResponse(issue.getCouponIssueId(), issue.getEventId(), event.getTitle(),
            event.getDiscountRate(), issue.visibleState(now), Times.toApi(issue.getExpiresAt()));
    }
}

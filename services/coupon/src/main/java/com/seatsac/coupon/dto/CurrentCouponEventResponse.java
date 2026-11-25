package com.seatsac.coupon.dto;

import com.seatsac.common.core.time.Times;
import com.seatsac.coupon.entity.CouponEvent;
import com.seatsac.coupon.entity.CouponEventState;
import com.seatsac.coupon.entity.CouponIssue;
import com.seatsac.coupon.entity.CouponIssueState;
import java.time.Instant;
import java.time.OffsetDateTime;

/** 계약 {@code CurrentCouponEvent}. {@code myIssue}는 항상 있고 안 받았으면 null */
public record CurrentCouponEventResponse(Long eventId, String title, int discountRate, OffsetDateTime startsAt,
                                         OffsetDateTime endsAt, int totalQuantity, int remainingQuantity,
                                         String eligibleGrade, CouponEventState state, MyIssue myIssue) {

    public record MyIssue(String couponIssueId, CouponIssueState state) {
    }

    public static CurrentCouponEventResponse of(CouponEvent event, int remaining, CouponIssue mine, Instant now) {
        return new CurrentCouponEventResponse(event.getEventId(), event.getTitle(), event.getDiscountRate(),
            Times.toApi(event.getStartsAt()), Times.toApi(event.getEndsAt()), event.getTotalQuantity(), remaining,
            event.getEligibleGrade(), event.stateAt(now),
            mine == null ? null : new MyIssue(mine.getCouponIssueId(), mine.visibleState(now)));
    }
}

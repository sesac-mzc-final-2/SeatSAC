package com.seatsac.coupon.dto;

import com.seatsac.common.core.time.Times;
import com.seatsac.coupon.entity.CouponEvent;
import com.seatsac.coupon.entity.CouponEventState;
import java.time.Instant;
import java.time.OffsetDateTime;

/** 계약 {@code CouponEvent}(internal) — ops-api가 그대로 돌려준다 */
public record CouponEventResponse(Long eventId, String title, int discountRate, OffsetDateTime startsAt,
                                  OffsetDateTime endsAt, int totalQuantity, int issuedQuantity, int remainingQuantity,
                                  String eligibleGrade, CouponEventState state, OffsetDateTime createdAt) {

    public static CouponEventResponse of(CouponEvent event, int remaining, Instant now) {
        return new CouponEventResponse(event.getEventId(), event.getTitle(), event.getDiscountRate(),
            Times.toApi(event.getStartsAt()), Times.toApi(event.getEndsAt()), event.getTotalQuantity(),
            event.getIssuedQuantity(), remaining, event.getEligibleGrade(), event.stateAt(now),
            Times.toApi(event.getCreatedAt()));
    }
}

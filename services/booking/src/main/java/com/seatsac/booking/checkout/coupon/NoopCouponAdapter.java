package com.seatsac.booking.checkout.coupon;

import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@code APP_CHECKOUT_COUPON_ADAPTER=none}(기본) — coupon 없이 saga·측정이 끝까지 돈다(설계 03 3-1).
 * 쿠폰을 요구하면 <b>거절</b>한다(어댑터가 꺼져 있는데 할인해 주면 돈을 덜 받는 쪽으로 틀린다).
 */
@Component
@ConditionalOnProperty(prefix = "app.checkout", name = "coupon-adapter", havingValue = "none", matchIfMissing = true)
public class NoopCouponAdapter implements CouponPort {

    @Override
    public Optional<CouponReply> reserve(String checkoutId, Long bookingId, String userId, String couponIssueId,
                                         int originalAmount) {
        return Optional.of(new Rejected(checkoutId, "COUPON_DISABLED"));
    }

    @Override
    public void use(String checkoutId, Long bookingId, String couponIssueId) {
        // coupon 없음
    }

    @Override
    public void release(String checkoutId, Long bookingId, String couponIssueId) {
        // coupon 없음
    }
}

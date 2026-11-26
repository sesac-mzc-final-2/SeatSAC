package com.seatsac.booking.checkout.coupon;

import com.seatsac.booking.checkout.CheckoutCommands;
import com.seatsac.booking.checkout.CheckoutSaga;
import com.seatsac.booking.enums.OutboxDestination;
import com.seatsac.booking.enums.OutboxEventType;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@code APP_CHECKOUT_COUPON_ADAPTER=sqs} — 명령 3종을 {@code cinema-staging-coupon-commands}로(설계 03 3-2, 계약
 * {@code contracts/events/coupon.*-requested.v1.json}, #187). 결과 2종은 {@code booking-replies}로 와서
 * {@link CouponReplyTranslator}가 번역한다.
 *
 * <p>{@code aggregate_key}는 {@code {checkoutId}:coupon} — 종류마다 유일 키가 따로라 체크아웃당 명령마다 한 번만 들어간다
 * (saga는 끝날 때 한 번만 전이하므로 사용·반환도 한 번).
 */
@Component
@ConditionalOnProperty(prefix = "app.checkout", name = "coupon-adapter", havingValue = "sqs")
@RequiredArgsConstructor
public class SqsCouponAdapter implements CouponPort {

    private final CheckoutCommands commands;

    @Override
    public Optional<CouponReply> reserve(CheckoutSaga saga, Instant now) {
        save(saga, OutboxEventType.COUPON_RESERVATION_REQUESTED, now, null, true);
        return Optional.empty();
    }

    @Override
    public void use(CheckoutSaga saga, Instant now) {
        save(saga, OutboxEventType.COUPON_USE_REQUESTED, now, null, false);
    }

    @Override
    public void release(CheckoutSaga saga, String reason, Instant now) {
        save(saga, OutboxEventType.COUPON_RELEASE_REQUESTED, now, reason, false);
    }

    private void save(CheckoutSaga saga, OutboxEventType type, Instant now, String reason, boolean withUser) {
        commands.save(OutboxDestination.COUPON_COMMANDS, saga, type, saga.getCheckoutId() + ":coupon", now, p -> {
            p.put("couponIssueId", saga.getCouponIssueId());
            if (withUser) {
                p.put("userId", saga.getUserId());   // coupon이 쿠폰 주인과 비교(NOT_OWNER)
            }
            if (reason != null) {
                p.put("reason", reason);
            }
        });
    }
}

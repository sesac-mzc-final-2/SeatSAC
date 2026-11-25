package com.seatsac.coupon.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.coupon.*} — 이름은 docs/service-runtime-contract.md 3장 그대로.
 *
 * @param idempotencyTtl     {@code coupon:idem} 키 TTL ({@code APP_COUPON_IDEMPOTENCY_TTL}, 기본 24h)
 * @param reservedAlarmAfter 이벤트 종료 뒤 이만큼 RESERVED로 남으면 경보 ({@code APP_COUPON_RESERVED_ALARM_AFTER}, 기본 1h).
 *                           자동 반환하지 않는다 — 결제 결과를 모르는 동안 돈이 움직였을 수 있다(22-4 규칙)
 */
@ConfigurationProperties(prefix = "app.coupon")
public record CouponProperties(Duration idempotencyTtl, Duration reservedAlarmAfter) {

    public CouponProperties {
        idempotencyTtl = idempotencyTtl == null ? Duration.ofHours(24) : idempotencyTtl;
        reservedAlarmAfter = reservedAlarmAfter == null ? Duration.ofHours(1) : reservedAlarmAfter;
    }
}

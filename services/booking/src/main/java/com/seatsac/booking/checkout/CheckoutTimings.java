package com.seatsac.booking.checkout;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 체크아웃 시간 지표(6.2.4, 런타임 계약 5장) — 동기/비동기 비교 측정(명세 21-3)에 쓴다. 기준은 saga 행이 생긴 시각
 * ({@code created_at}, 202를 만든 순간)이다.
 * <ul>
 *   <li>{@code booking_checkout_seconds} — 끝날 때까지(CONFIRMED·FAILED·EXPIRED·PAYMENT_RETRYABLE·COUPON_REJECTED)</li>
 *   <li>{@code booking_checkout_step_seconds{step}} — {@code accepted}(202 응답까지)·{@code coupon_reserved}·
 *       {@code payment_prepared}·{@code verified}(승인 결과 도착)·{@code applied}(예매 확정)</li>
 * </ul>
 * 히스토그램을 켜 p95를 기록 규칙으로 계산할 수 있게 한다.
 */
@Component
@RequiredArgsConstructor
public class CheckoutTimings {

    private final MeterRegistry meterRegistry;

    public void step(String step, Instant createdAt, Instant now) {
        record(Timer.builder("booking_checkout_step").tag("step", step), createdAt, now);
    }

    public void total(Instant createdAt, Instant now) {
        record(Timer.builder("booking_checkout"), createdAt, now);
    }

    private void record(Timer.Builder builder, Instant createdAt, Instant now) {
        if (createdAt == null || now.isBefore(createdAt)) {
            return;
        }
        builder.publishPercentileHistogram().register(meterRegistry).record(Duration.between(createdAt, now));
    }
}

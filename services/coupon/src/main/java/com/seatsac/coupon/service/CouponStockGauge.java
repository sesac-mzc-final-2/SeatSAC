package com.seatsac.coupon.service;

import com.seatsac.coupon.entity.CouponEventState;
import com.seatsac.coupon.repository.CouponEventRepository;
import com.seatsac.coupon.stock.CouponStock;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@code coupon_stock_remaining}(gauge, 라벨 없음) — 진행 중 이벤트 1개 기준 남은 수량(22-2). 수집할 때 센다.
 * 진행 중 이벤트가 없으면 값이 없다(NaN) — 0으로 두면 「매진」으로 읽힌다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponStockGauge {

    private final CouponEventRepository events;
    private final CouponStock stock;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @PostConstruct
    void register() {
        Gauge.builder("coupon_stock_remaining", this, CouponStockGauge::remaining)
            .description("진행 중 쿠폰 이벤트의 남은 수량(표시용, Valkey 재고 키)")
            .register(meterRegistry);
    }

    private double remaining() {
        try {
            Instant now = clock.instant();
            return events.findCurrent(now)
                .filter(event -> event.stateAt(now) == CouponEventState.OPEN)
                .map(event -> (double) stock.remaining(event))
                .orElse(Double.NaN);
        } catch (RuntimeException e) {
            log.warn("쿠폰 재고 지표 조회 실패 — 값을 비운다");
            return Double.NaN;
        }
    }
}

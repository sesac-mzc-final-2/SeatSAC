package com.seatsac.booking.checkout;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 체크아웃 saga 설정(런타임 계약 3장 booking).
 *
 * @param sagaEnabled         {@code APP_CHECKOUT_SAGA_ENABLED} — false(기본)면 체크아웃 API가 없다(404)
 * @param stepTimeout         {@code APP_CHECKOUT_STEP_TIMEOUT} — 돈이 움직이기 전 단계(쿠폰 예약·결제 준비) 기한, 10초
 * @param paymentRetryEnabled {@code APP_CHECKOUT_PAYMENT_RETRY_ENABLED} — 결제창 취소 뒤 재결제(D25), 기본 false
 * @param maxAttempts         예매당 체크아웃 시작 상한(쿠폰 거절·재결제 포함), 3
 * @param couponAdapter       {@code APP_CHECKOUT_COUPON_ADAPTER} — {@code none}(기본)·{@code sqs}
 */
@ConfigurationProperties("app.checkout")
public record CheckoutProperties(boolean sagaEnabled, Duration stepTimeout, boolean paymentRetryEnabled,
                                 Integer maxAttempts, String couponAdapter) {

    /** 결제 확인 첫 기한·결제창 미완료(NOT_YET) 재확인 간격(D21) */
    public static final Duration VERIFY_INTERVAL = Duration.ofSeconds(5);

    public CheckoutProperties {
        if (stepTimeout == null) {
            stepTimeout = Duration.ofSeconds(10);
        }
        if (maxAttempts == null || maxAttempts < 1) {
            maxAttempts = 3;
        }
        if (couponAdapter == null || couponAdapter.isBlank()) {
            couponAdapter = "none";
        }
    }
}

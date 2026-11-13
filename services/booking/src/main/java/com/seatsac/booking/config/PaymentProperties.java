package com.seatsac.booking.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 결제 설정 {@code app.payment.*} (ADR-112).
 *
 * <ul>
 *   <li>{@code mockKey} — 환경변수 {@code APP_PAYMENT_MOCK_KEY}(Secret {@code booking-secrets}). 모의 결제
 *       ({@code CARD})를 부를 수 있는 기계 경로(k6·분석 Job)의 키다. <b>비어 있으면 기계 경로가 꺼진다</b> —
 *       그때 모의 결제는 ADMIN만 부를 수 있다</li>
 *   <li>{@code pendingGrace} — 환경변수 {@code APP_PAYMENT_PENDING_GRACE}(기본 2분). 결제 요청이 붙은
 *       PENDING 예매의 만료 보류를 선점 만료 뒤 이만큼만 한다. 결제창을 띄운 채 떠난 사용자의 좌석이
 *       영원히 묶이지 않게 그 뒤에는 결제 상태를 조회해 끝낸다</li>
 * </ul>
 */
@Validated
@ConfigurationProperties("app.payment")
public record PaymentProperties(String mockKey, Duration pendingGrace) {

    public static final Duration DEFAULT_PENDING_GRACE = Duration.ofMinutes(2);

    public PaymentProperties {
        if (pendingGrace == null) {
            pendingGrace = DEFAULT_PENDING_GRACE;
        }
    }

    /** 헤더로 받은 키가 맞는지. 비교 시간으로 키가 새지 않게 상수 시간 비교를 쓴다 */
    public boolean mockKeyMatches(String given) {
        if (mockKey == null || mockKey.isBlank() || given == null) {
            return false;
        }
        return MessageDigest.isEqual(mockKey.getBytes(StandardCharsets.UTF_8),
            given.getBytes(StandardCharsets.UTF_8));
    }

    /** 키 값이 로그·액추에이터에 찍히지 않게 가린다 */
    @Override
    public String toString() {
        return "PaymentProperties(mockKey=****, pendingGrace=" + pendingGrace + ")";
    }
}

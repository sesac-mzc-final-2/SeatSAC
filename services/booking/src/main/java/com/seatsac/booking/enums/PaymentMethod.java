package com.seatsac.booking.enums;

/**
 * 결제 수단 — Payment 명세. 화면은 {@code TOSSPAY}(포트원 간편결제)만 쓴다.
 * {@code CARD}는 모의 결제(MOCK)로 테스트·분석 전용이다 (ADR-112).
 */
public enum PaymentMethod {
    CARD,
    TOSSPAY
}

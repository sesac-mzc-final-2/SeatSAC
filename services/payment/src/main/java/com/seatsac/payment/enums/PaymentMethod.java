package com.seatsac.payment.enums;

/** 결제 수단 — 계약 {@code PaymentMethod}. 수단이 제공자를 정한다 (ADR-112) */
public enum PaymentMethod {
    /** 모의 카드 → {@link PgProvider#MOCK} */
    CARD,
    /** 토스페이(간편결제) → {@link PgProvider#PORTONE}, 포트원 {@code payMethod=EASY_PAY} */
    TOSSPAY;

    public PgProvider provider() {
        return this == TOSSPAY ? PgProvider.PORTONE : PgProvider.MOCK;
    }
}

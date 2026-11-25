package com.seatsac.payment.common;

import java.time.Duration;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 결제 규칙 상수 — 명세 4·5·7장. 값은 한 곳에서만 고친다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PaymentPolicy {

    /** 1석 15,000원 */
    public static final int SEAT_PRICE = 15_000;

    /**
     * 금액 범위 — 1원 ~ 4석 원가. 11/24(D20): 쿠폰 할인 뒤 금액(예: 50% → 7,500원)을 받으므로 15,000 배수 검사를 뺐다.
     * 상한은 할인이 금액을 늘리지 않으므로 4석 원가 그대로다(계약 {@code PaymentRequest.amount} 1~60,000)
     */
    public static final int MIN_AMOUNT = 1;
    public static final int MAX_AMOUNT = SEAT_PRICE * 4;

    /** 카드 끝 4자리로 결과를 정한다 (ADR-13) */
    public static final String DECLINED_CARD = "0000";
    public static final String INSUFFICIENT_CARD = "1111";
    public static final String DELAYED_CARD = "9999";

    /** 지연 카드의 승인 지연. Booking 응답 타임아웃(3초)보다 길어야 PROCESSING 시연이 된다 */
    public static final Duration MOCK_DELAY = Duration.ofSeconds(5);

    /** 기동 시 이보다 오래 PROCESSING으로 남은 행을 FAILED로 닫는다 (명세 3장) */
    public static final Duration PROCESSING_TIMEOUT = Duration.ofSeconds(30);

    public static final int CARD_LAST4_LENGTH = 4;
}

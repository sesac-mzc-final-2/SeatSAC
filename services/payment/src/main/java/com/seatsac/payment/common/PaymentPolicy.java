package com.seatsac.payment.common;

import java.time.Duration;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 결제 규칙 상수 — 명세 4·5·7장. 값은 한 곳에서만 고친다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PaymentPolicy {

    /** 1석 15,000원. 금액은 이 값의 배수여야 한다 */
    public static final int SEAT_PRICE = 15_000;

    /** 1~4석 (Booking의 선점 상한과 같다) */
    public static final int MIN_AMOUNT = SEAT_PRICE;
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

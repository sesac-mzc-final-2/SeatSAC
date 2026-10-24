package com.seatsac.payment.enums;

/**
 * 거절·마감 사유 — 명세 3장·5장.
 *
 * <p>{@link #PROCESSING_TIMEOUT}은 모의 판정 중 파드가 죽어 {@code PROCESSING}으로 남은 행을
 * 기동 시 닫을 때만 쓴다(명세 3장·7장). 외부 PG가 없어 결과를 다시 물을 곳이 없다.
 */
public enum PaymentFailureCode {
    CARD_DECLINED,
    INSUFFICIENT_BALANCE,
    PROCESSING_TIMEOUT
}

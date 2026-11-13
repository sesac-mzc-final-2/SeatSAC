package com.seatsac.payment.enums;

/**
 * 거절·마감 사유 — 명세 3장·5장, ADR-112.
 *
 * <p>{@link #PROCESSING_TIMEOUT}은 {@code PROCESSING}으로 남은 행을 닫을 때 쓴다. MOCK은 판정 중 파드가 죽은
 * 경우(30초), PORTONE은 결제창을 끝내지 않고 떠난 경우(7분, 포트원 조회로 PAID가 아님을 확인한 뒤).
 */
public enum PaymentFailureCode {
    /** MOCK — 카드 끝 0000 */
    CARD_DECLINED,
    /** MOCK — 카드 끝 1111 */
    INSUFFICIENT_BALANCE,
    /** PROCESSING으로 오래 남아 닫음. 승인된 적이 없다 */
    PROCESSING_TIMEOUT,
    /** PORTONE — 포트원 상태 FAILED */
    PG_DECLINED,
    /** PORTONE — 결제창에서 사용자가 취소 */
    USER_CANCELED,
    /**
     * PORTONE — 포트원에는 PAID인데 금액이 예매 금액과 다르다(브라우저 위변조 의심). 돈이 잡혔으므로
     * {@code pg_transaction_id}를 함께 기록하고 환불은 스윕이 끝까지 처리한다(환불 미완료 표식)
     */
    AMOUNT_MISMATCH
}

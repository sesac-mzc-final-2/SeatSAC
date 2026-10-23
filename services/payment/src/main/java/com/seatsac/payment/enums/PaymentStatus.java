package com.seatsac.payment.enums;

/**
 * 결제 상태 — 명세 3장.
 *
 * <pre>
 * 요청 ─▶ PROCESSING ─┬─ 승인 ─▶ APPROVED ─ 취소 ─▶ CANCELED
 *                     └─ 거절 ─▶ FAILED
 * </pre>
 *
 * <p>{@code PROCESSING}은 「행은 만들었고 판정 전」이다(지연 카드, 또는 판정 중 파드 종료).
 * Booking은 이것을 「결과 미확인」으로 보고 503 {@code PAYMENT_RESULT_UNKNOWN}을 돌려준다.
 */
public enum PaymentStatus {
    PROCESSING,
    APPROVED,
    FAILED,
    CANCELED
}

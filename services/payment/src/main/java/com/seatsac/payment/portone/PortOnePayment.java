package com.seatsac.payment.portone;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 포트원 V2 {@code GET /payments/{paymentId}} 응답 중 우리가 읽는 필드만. 모르는 필드는 무시한다.
 *
 * <p>{@code status}: READY · PENDING · PAY_PENDING · VIRTUAL_ACCOUNT_ISSUED · PAID · FAILED · CANCELLED · PARTIAL_CANCELLED.
 * 「실패만 아니면 성공」으로 보지 않는다 — PAID만 승인이다(다른 팀 장애 기록: REQUESTED를 완료로 기록한 사고).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PortOnePayment(
    String status,
    String id,
    String transactionId,
    Amount amount,
    String currency,
    Failure failure,
    List<Cancellation> cancellations,
    /** PAID 이후에만 있다. ISO-8601 문자열 그대로 받고 {@link #paidAtOr}에서 읽는다(형식이 달라도 결제 처리를 막지 않게) */
    String paidAt
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Amount(Long total, Long paid, Long cancelled) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Failure(String reason, String pgCode, String pgMessage) {
    }

    /** 취소 내역 — {@code status}: SUCCEEDED · REQUESTED · FAILED */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cancellation(String status, String id, Long totalAmount, String reason) {
    }

    public boolean isPaid() {
        return "PAID".equals(status);
    }

    public boolean isFailed() {
        return "FAILED".equals(status);
    }

    /** 전액·부분 취소 모두 — 우리는 전액만 취소하므로 둘 다 「취소됨」으로 본다 */
    public boolean isCancelled() {
        return "CANCELLED".equals(status) || "PARTIAL_CANCELLED".equals(status);
    }

    /** 앞서 보낸 취소를 PG가 아직 처리 중이다(취소 내역에 REQUESTED) — 다시 보내지 않는다 */
    public boolean hasRequestedCancellation() {
        return cancellations != null && cancellations.stream().anyMatch(c -> "REQUESTED".equals(c.status()));
    }

    /** 결제창이 아직 끝나지 않았다(READY·PENDING·PAY_PENDING·VIRTUAL_ACCOUNT_ISSUED 등) */
    public boolean isUnsettled() {
        return !isPaid() && !isFailed() && !isCancelled();
    }

    /**
     * 포트원이 결제를 승인한 시각. 없거나 읽을 수 없으면 {@code fallback}(우리가 확인한 시각) — 승인 시각이 비면
     * 응답 계약({@code approvedAt})이 깨지므로 비워 두지 않는다.
     */
    public Instant paidAtOr(Instant fallback) {
        if (paidAt == null || paidAt.isBlank()) {
            return fallback;
        }
        try {
            return OffsetDateTime.parse(paidAt).toInstant();
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    public boolean matches(int expectedAmount, String expectedCurrency) {
        return amount != null && amount.total() != null && amount.total() == expectedAmount
            && expectedCurrency.equals(currency);
    }

    /**
     * 결제창에서 사용자가 닫은 경우를 PG 거절과 구분한다. 포트원 실패 사유 문구에 기대는 추정이라
     * 못 가리면 {@code false}(= PG 거절)다. 화면 문구만 달라지고 처리는 같다.
     */
    public boolean looksUserCancelled() {
        if (failure == null) {
            return false;
        }
        String text = ((failure.reason() == null ? "" : failure.reason()) + " "
            + (failure.pgMessage() == null ? "" : failure.pgMessage())).toLowerCase();
        return text.contains("cancel") || text.contains("취소");
    }
}

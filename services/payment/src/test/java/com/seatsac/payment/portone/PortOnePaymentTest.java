package com.seatsac.payment.portone;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 포트원 응답의 승인 시각 읽기 — 없거나 형식이 달라도 결제 처리를 막지 않는다 */
class PortOnePaymentTest {

    private static final Instant NOW = Instant.parse("2026-11-16T05:10:00Z");

    private static PortOnePayment paidAt(String paidAt) {
        return new PortOnePayment("PAID", "p", "tx", null, "KRW", null, null, paidAt);
    }

    @Test
    void paidAtIsReadFromPortOne() {
        assertThat(paidAt("2026-11-16T05:04:10.123Z").paidAtOr(NOW))
            .isEqualTo(Instant.parse("2026-11-16T05:04:10.123Z"));
        assertThat(paidAt("2026-11-16T14:04:10+09:00").paidAtOr(NOW))
            .isEqualTo(Instant.parse("2026-11-16T05:04:10Z"));
    }

    @Test
    void missingOrUnreadablePaidAtFallsBackToNow() {
        assertThat(paidAt(null).paidAtOr(NOW)).isEqualTo(NOW);
        assertThat(paidAt("").paidAtOr(NOW)).isEqualTo(NOW);
        assertThat(paidAt("어제").paidAtOr(NOW)).isEqualTo(NOW);
    }
}

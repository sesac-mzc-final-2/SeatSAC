package com.seatsac.booking.component;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatsac.booking.component.CatalogClient.Screening;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 회차 시각 판정 — 취소는 상영 시작, 선점·결제는 시작 5분 전 마감. 경계는 막는 쪽이다 */
class ScreeningTest {

    private static final Instant STARTS_AT = Instant.parse("2026-11-16T05:00:00Z");
    private static final Screening SCREENING = new Screening(42L, 10_004L, 1, STARTS_AT,
        STARTS_AT.plusSeconds(7200), "승부", "건대입구", "1관");

    @Test
    void startedFromTheStartTime() {
        assertThat(SCREENING.hasStarted(STARTS_AT.minusMillis(1))).isFalse();
        assertThat(SCREENING.hasStarted(STARTS_AT)).isTrue();
    }

    @Test
    void closedForBookingFromFiveMinutesBeforeStart() {
        assertThat(SCREENING.isClosedForBooking(STARTS_AT.minusSeconds(300).minusMillis(1))).isFalse();
        assertThat(SCREENING.isClosedForBooking(STARTS_AT.minusSeconds(300))).isTrue();
        assertThat(SCREENING.isClosedForBooking(STARTS_AT)).isTrue();
    }
}

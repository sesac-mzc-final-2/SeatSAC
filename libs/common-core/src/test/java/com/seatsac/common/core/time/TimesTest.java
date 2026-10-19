package com.seatsac.common.core.time;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class TimesTest {

    @Test
    void businessDateIsSeoulDate() {
        // UTC 2026-11-15 16:00 = KST 2026-11-16 01:00
        Instant at = Instant.parse("2026-11-15T16:00:00Z");
        assertEquals(LocalDate.of(2026, 11, 16), Times.businessDate(at));
    }

    @Test
    void apiOffsetIsPlusNine() {
        assertEquals("+09:00", Times.toApi(Instant.parse("2026-11-16T05:00:00Z")).getOffset().getId());
    }
}

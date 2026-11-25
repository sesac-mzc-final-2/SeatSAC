package com.seatsac.payment.component;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** D18 — 포트원 재확인 간격: 1 → 2 → 4 → 8 → 15분, 그 뒤로 계속 15분(설계 02 6-2) */
class PortOneSweeperBackoffTest {

    @Test
    void recheckDelayDoublesUpToFifteenMinutes() {
        assertThat(IntStream.rangeClosed(1, 8).mapToObj(PortOneSweeper::recheckDelay).map(Duration::toMinutes))
            .containsExactly(1L, 2L, 4L, 8L, 15L, 15L, 15L, 15L);
    }
}

package com.seatsac.booking.component;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatsac.booking.common.RedisKeys;
import com.seatsac.booking.enums.SeatEventType;
import com.seatsac.common.test.MySqlContainerSupport;
import com.seatsac.common.test.ValkeyContainerSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 좌석 이벤트 번호(seq)가 <b>뒤로 가지 않는지</b> — {@code seq-next.lua}.
 *
 * <p>Valkey는 복제본·스냅샷이 없어 재시작·노드 교체 때 {@code seat-seq:{회차}}가 사라진다. 그때 1부터 다시
 * 세면 이미 열린 화면이 새 이벤트를 「지난 번호」({@code seq <= lastSeq})로 보고 버려 실시간 반영이 멈춘다.
 * 키가 없을 때는 「현재 시각 ms × 1000」에서 시작해, 사라지기 전 마지막 번호보다 크게 만든다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({MySqlContainerSupport.class, ValkeyContainerSupport.class, SeatEventPublisherSeqTest.MovableClockConfig.class})
class SeatEventPublisherSeqTest {

    private static final Long SCREENING = 77L;
    private static final Instant T0 = Instant.parse("2026-11-03T03:00:00Z");

    @Autowired
    SeatEventPublisher publisher;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    MovableClock clock;

    @BeforeEach
    void reset() {
        clock.set(T0);
        redis.delete(RedisKeys.seatSeq(SCREENING));
    }

    @Test
    void firstEventStartsFromTheClock() {
        publish();

        assertThat(seq()).isEqualTo(T0.toEpochMilli() * 1000 + 1);
    }

    @Test
    void laterEventsOnlyCountUpEvenIfTimePasses() {
        publish();
        clock.advanceMillis(20 * 60 * 60 * 1000L);   // 20시간 뒤 — 시각은 시작값에만 쓴다

        publish();

        assertThat(seq()).isEqualTo(T0.toEpochMilli() * 1000 + 2);
    }

    /** 배포 전부터 있던 작은 번호(예: 418)는 그대로 이어서 센다 */
    @Test
    void existingKeyKeepsCounting() {
        redis.opsForValue().set(RedisKeys.seatSeq(SCREENING), "418");

        publish();

        assertThat(seq()).isEqualTo(419);
    }

    /** Valkey가 비워진 뒤(재시작)의 첫 번호가 비워지기 전 마지막 번호보다 크다 → 화면은 빈틈으로 보고 다시 받는다 */
    @Test
    void numbersNeverGoBackAfterTheKeyIsLost() {
        for (int i = 0; i < 3; i++) {
            publish();
        }
        long beforeLoss = seq();

        redis.delete(RedisKeys.seatSeq(SCREENING));   // Valkey 재시작으로 데이터가 사라짐
        clock.advanceMillis(1);                       // 가장 가까운 경우: 1ms 뒤

        publish();

        assertThat(seq()).isGreaterThan(beforeLoss + 1);   // +1이 아니라 건너뛴다 = 화면이 빈틈으로 판정
    }

    /** 전에 작은 번호였던 키가 사라져도 새 시작값(16자리)이 크다 */
    @Test
    void lostSmallKeyRestartsAboveIt() {
        redis.opsForValue().set(RedisKeys.seatSeq(SCREENING), "418");
        publish();
        redis.delete(RedisKeys.seatSeq(SCREENING));

        publish();

        assertThat(seq()).isGreaterThan(419);
    }

    /** 화면(JavaScript Number)이 정확히 비교할 수 있는 범위 안이다 — 2^53-1 */
    @Test
    void startValueStaysWithinJavaScriptSafeInteger() {
        long jsMaxSafeInteger = (1L << 53) - 1;

        assertThat(SeatEventPublisher.startValue(Instant.parse("2100-01-01T00:00:00Z"))).isLessThan(jsMaxSafeInteger);
    }

    /** 두 파드가 동시에 회차의 첫 이벤트를 내도 서로의 번호를 덮지 않는다(확인·설정·증가가 한 스크립트) */
    @Test
    void concurrentFirstEventsDoNotOverwriteEachOther() throws Exception {
        int events = 20;
        ExecutorService pool = Executors.newFixedThreadPool(events);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int i = 0; i < events; i++) {
                pool.submit(() -> {
                    start.await();
                    publish();
                    return null;
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(seq()).isEqualTo(T0.toEpochMilli() * 1000 + events);
    }

    private void publish() {
        publisher.publish(SeatEventType.HELD, SCREENING, List.of(101L), clock.instant());
    }

    private long seq() {
        return Long.parseLong(redis.opsForValue().get(RedisKeys.seatSeq(SCREENING)));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MovableClockConfig {

        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock(T0);
        }
    }

    static final class MovableClock extends Clock {

        private volatile Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        void advanceMillis(long millis) {
            this.now = now.plusMillis(millis);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}

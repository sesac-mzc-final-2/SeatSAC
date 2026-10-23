package com.seatsac.booking.component;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.common.RedisKeys;
import com.seatsac.booking.component.SeatHoldStrategy.HoldOutcome.Status;
import com.seatsac.booking.config.RedisConfig;
import com.seatsac.common.test.ValkeyContainerSupport;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * BKG-02 Valkey 선점 — `redis-usage.md` 5·6장.
 * 데모 조건(같은 좌석 100명 동시 요청 → 성공 정확히 1명)을 여기서 확인한다.
 */
@DataRedisTest   // JPA·Flyway를 띄우지 않는다 — 이 테스트는 Valkey만 쓴다
@ActiveProfiles("test")
@Import({ValkeyContainerSupport.class, RedisConfig.class, SeatHoldRedisRepository.class})
class SeatHoldRedisRepositoryTest {

    private static final Long SCREENING = 42L;
    private static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");

    @Autowired
    SeatHoldRedisRepository repository;
    @Autowired
    StringRedisTemplate redis;

    private Instant expiresAt;

    @BeforeEach
    void clear() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        expiresAt = NOW.plus(BookingPolicy.HOLD_TTL);
    }

    @Test
    void holdWritesSeatKeysUserHoldAndExpiryLedgerAtOnce() {
        String holdId = UUID.randomUUID().toString();

        assertThat(repository.hold(SCREENING, user(1), holdId, List.of(101L, 102L), expiresAt).isOk()).isTrue();

        assertThat(redis.opsForValue().get(RedisKeys.seat(SCREENING, 101L))).isEqualTo(holdId);
        assertThat(redis.opsForValue().get(RedisKeys.seat(SCREENING, 102L))).isEqualTo(holdId);
        assertThat(redis.opsForValue().get(RedisKeys.userHold(SCREENING, user(1)))).isEqualTo(holdId);
        assertThat(redis.getExpire(RedisKeys.seat(SCREENING, 101L)))
            .isBetween(BookingPolicy.HOLD_TTL.toSeconds() - 5, BookingPolicy.HOLD_TTL.toSeconds());
        // 장부 등록도 같은 스크립트에서 (A2)
        assertThat(redis.opsForZSet().score(RedisKeys.holdExpiry(),
            RedisKeys.holdExpiryMember(SCREENING, holdId))).isEqualTo((double) expiresAt.toEpochMilli());
        // hold-expiry는 TTL 없음 (명세의 예외 2개)
        assertThat(redis.getExpire(RedisKeys.holdExpiry())).isEqualTo(-1);
    }

    @Test
    void holdIsAllOrNothing() {
        repository.hold(SCREENING, user(1), UUID.randomUUID().toString(), List.of(102L), expiresAt);

        // 102가 이미 잡혔으므로 101·102 요청은 전부 실패하고 101에 키가 남지 않는다
        var outcome = repository.hold(SCREENING, user(2), UUID.randomUUID().toString(),
            List.of(101L, 102L), expiresAt);

        assertThat(outcome.status()).isEqualTo(Status.SEAT_TAKEN);
        assertThat(outcome.takenSeatIds()).containsExactly(102L);
        assertThat(redis.hasKey(RedisKeys.seat(SCREENING, 101L))).isFalse();
    }

    @Test
    void secondHoldBySameUserInSameScreeningIsRejected() {
        String first = UUID.randomUUID().toString();
        repository.hold(SCREENING, user(1), first, List.of(101L), expiresAt);

        var outcome = repository.hold(SCREENING, user(1), UUID.randomUUID().toString(),
            List.of(201L), expiresAt);

        assertThat(outcome.status()).isEqualTo(Status.ACTIVE_HOLD);
        assertThat(outcome.activeHoldId()).isEqualTo(first);   // 흐름 서비스가 이 값으로 RDS를 확인한다
        assertThat(redis.hasKey(RedisKeys.seat(SCREENING, 201L))).isFalse();
    }

    @Test
    void releaseOnlyDeletesOwnHold() {
        String mine = UUID.randomUUID().toString();
        repository.hold(SCREENING, user(1), mine, List.of(101L), expiresAt);

        // 남의 holdId로는 지워지지 않는다 (만료 직후 새 선점을 지우는 사고 방지)
        assertThat(repository.release(SCREENING, user(1), UUID.randomUUID().toString(), List.of(101L)))
            .isZero();
        assertThat(redis.hasKey(RedisKeys.seat(SCREENING, 101L))).isTrue();

        assertThat(repository.release(SCREENING, user(1), mine, List.of(101L))).isEqualTo(1L);
        assertThat(redis.hasKey(RedisKeys.seat(SCREENING, 101L))).isFalse();
        assertThat(redis.hasKey(RedisKeys.userHold(SCREENING, user(1)))).isFalse();
        assertThat(redis.opsForZSet().score(RedisKeys.holdExpiry(),
            RedisKeys.holdExpiryMember(SCREENING, mine))).isNull();
    }

    @Test
    void releaseWithoutSeatIdsCleansOrphanKeys() {
        // 고아 키 정리(B3): user-hold는 있는데 RDS에 예매가 없다. 좌석은 알 수 없다
        String orphan = UUID.randomUUID().toString();
        repository.hold(SCREENING, user(1), orphan, List.of(101L), expiresAt);

        repository.release(SCREENING, user(1), orphan, List.of());

        assertThat(redis.hasKey(RedisKeys.userHold(SCREENING, user(1)))).isFalse();   // 재선점 가능
        assertThat(redis.opsForZSet().score(RedisKeys.holdExpiry(),
            RedisKeys.holdExpiryMember(SCREENING, orphan))).isNull();
        assertThat(redis.hasKey(RedisKeys.seat(SCREENING, 101L))).isTrue();           // TTL로 사라진다
    }

    @Test
    void findHeldSeatsReadsManySeatsAtOnce() {
        String holdId = UUID.randomUUID().toString();
        repository.hold(SCREENING, user(1), holdId, List.of(101L, 102L), expiresAt);

        assertThat(repository.findHeldSeats(SCREENING, List.of(101L, 102L, 103L)))
            .containsOnlyKeys(101L, 102L)
            .containsValue(holdId);
    }

    /** 데모 조건: 같은 좌석에 100명이 동시에 요청하면 성공은 정확히 1명 */
    @Test
    void onlyOneOfHundredConcurrentHoldsWins() throws Exception {
        int people = 100;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(people);
        var success = new AtomicInteger();
        var seatTaken = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            for (int i = 0; i < people; i++) {
                int who = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        var outcome = repository.hold(SCREENING, user(who), UUID.randomUUID().toString(),
                            List.of(500L), expiresAt);
                        if (outcome.isOk()) {
                            success.incrementAndGet();
                        } else if (outcome.status() == Status.SEAT_TAKEN) {
                            seatTaken.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(seatTaken.get()).isEqualTo(people - 1);
        assertThat(redis.opsForZSet().size(RedisKeys.holdExpiry())).isEqualTo(1L);
    }

    private static String user(int n) {
        return "11111111-1111-4111-8111-%012d".formatted(n);
    }
}

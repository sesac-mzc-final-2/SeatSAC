package com.seatsac.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.common.RedisKeys;
import com.seatsac.booking.component.BookingRepositoryReaderForExpiry;
import com.seatsac.booking.component.CatalogClient;
import com.seatsac.booking.component.CatalogClient.AuditoriumLayout;
import com.seatsac.booking.component.CatalogClient.AuditoriumLayout.LayoutSeat;
import com.seatsac.booking.component.HoldExpirySweeper;
import com.seatsac.booking.component.ScreeningCache;
import com.seatsac.booking.component.SeatHoldStrategy;
import com.seatsac.booking.component.SeatKeyExpiredListener;
import com.seatsac.booking.dto.request.SeatHoldRequest;
import com.seatsac.booking.dto.response.SeatHoldResponse;
import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.booking.repository.BookingRepository;
import com.seatsac.booking.repository.BookingSeatRepository;
import com.seatsac.booking.service.HoldExpiryService.Outcome;
import com.seatsac.common.test.MySqlContainerSupport;
import com.seatsac.common.test.ValkeyContainerSupport;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BKG-04 선점 만료 — 세 경로(keyspace 빠른 경로 · 장부 1초 · RDS 10초)와 공통 순서를 본다.
 *
 * <p>시계를 고정해 두고 선점을 만든 뒤 <b>시계를 앞으로 돌려</b> 만료 상황을 만든다
 * (실제 300초를 기다리지 않는다).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({MySqlContainerSupport.class, ValkeyContainerSupport.class, HoldExpiryFlowTest.MovableClockConfig.class})
class HoldExpiryFlowTest {

    private static final String USER = "11111111-1111-4111-8111-111111111111";
    private static final Long SCREENING = 42L;
    private static final Instant HELD_AT = Instant.parse("2026-10-23T03:00:00Z");

    @MockitoBean
    CatalogClient catalogClient;

    @Autowired
    MovableClock clock;
    @Autowired
    ScreeningCache screeningCache;
    @Autowired
    SeatHoldService seatHoldService;
    @Autowired
    HoldExpiryService holdExpiryService;
    @Autowired
    HoldExpirySweeper sweeper;
    @Autowired
    SeatKeyExpiredListener keyExpiredListener;
    @Autowired
    SeatHoldStrategy seatHold;
    @Autowired
    BookingRepositoryReaderForExpiry expiredBookings;
    @Autowired
    BookingRepository bookingRepository;
    @Autowired
    BookingSeatRepository bookingSeatRepository;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    EntityManager entityManager;
    @Autowired
    TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        clock.set(HELD_AT);
        bookingSeatRepository.deleteAllInBatch();
        bookingRepository.deleteAllInBatch();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        screeningCache.clear();

        given(catalogClient.findScreening(SCREENING)).willReturn(new CatalogClient.Screening(
            SCREENING, 10_004L, 1, HELD_AT.plusSeconds(3600), HELD_AT.plusSeconds(10_000),
            "승부", "건대입구", "1관"));
        given(catalogClient.findLayout(10_004L, 1)).willReturn(new AuditoriumLayout(10_004L, 1,
            IntStream.rangeClosed(1, 400).mapToObj(i -> new LayoutSeat(100L + i, "J" + i, true)).toList()));
    }

    // --- 안전망 1: 만료 장부 스캔 ---

    @Test
    void ledgerSweepExpiresHoldAndCleansEverything() {
        SeatHoldResponse hold = hold(101L, 102L);
        String holdId = bookingRepository.findById(hold.bookingId()).orElseThrow().getHoldId();
        clock.advanceSeconds(BookingPolicy.HOLD_TTL.toSeconds() + 1);

        sweeper.sweepLedger();

        Booking expired = bookingRepository.findById(hold.bookingId()).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(bookingSeatRepository.findSeatIdsByBookingId(hold.bookingId())).isEmpty();
        // 장부 멤버는 처리 후에 지운다 (C13)
        assertThat(redis.opsForZSet().score(RedisKeys.holdExpiry(),
            RedisKeys.holdExpiryMember(SCREENING, holdId))).isNull();
        assertThat(redis.hasKey(RedisKeys.seat(SCREENING, 101L))).isFalse();
        assertThat(redis.hasKey(RedisKeys.userHold(SCREENING, USER))).isFalse();
        // 커밋 후 발행: 선점 HELD(1) + 만료 EXPIRED(2)
        assertThat(redis.opsForValue().get(RedisKeys.seatSeq(SCREENING))).isEqualTo("2");
    }

    @Test
    void ledgerSweepLeavesHoldsThatAreNotDueYet() {
        SeatHoldResponse hold = hold(101L);
        clock.advanceSeconds(10);   // 아직 만료 전

        sweeper.sweepLedger();

        assertThat(bookingRepository.findById(hold.bookingId()).orElseThrow().getStatus())
            .isEqualTo(BookingStatus.PENDING);
    }

    /** 고아 멤버(B3): 장부에만 남은 멤버는 장부에서만 지운다 — 이벤트도 발행하지 않는다 */
    @Test
    void orphanLedgerMemberIsOnlyRemovedFromTheLedger() {
        String orphanHoldId = UUID.randomUUID().toString();
        redis.opsForZSet().add(RedisKeys.holdExpiry(),
            RedisKeys.holdExpiryMember(SCREENING, orphanHoldId), HELD_AT.toEpochMilli());

        sweeper.sweepLedger();

        assertThat(redis.opsForZSet().score(RedisKeys.holdExpiry(),
            RedisKeys.holdExpiryMember(SCREENING, orphanHoldId))).isNull();
        assertThat(redis.opsForValue().get(RedisKeys.seatSeq(SCREENING))).isNull();   // 발행 없음
    }

    // --- 안전망 2: RDS 스캔 ---

    @Test
    void databaseSweepExpiresEvenWhenValkeyWasFlushed() {
        SeatHoldResponse hold = hold(101L);
        clock.advanceSeconds(BookingPolicy.HOLD_TTL.toSeconds() + 1);
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();   // 노드 교체

        sweeper.sweepDatabase();

        assertThat(bookingRepository.findById(hold.bookingId()).orElseThrow().getStatus())
            .isEqualTo(BookingStatus.EXPIRED);
        assertThat(bookingSeatRepository.findSeatIdsByBookingId(hold.bookingId())).isEmpty();
    }

    // --- 빠른 경로: keyspace 알림 ---

    @Test
    void expiredSeatKeyNotificationExpiresTheBooking() {
        SeatHoldResponse hold = hold(101L, 102L);
        clock.advanceSeconds(BookingPolicy.HOLD_TTL.toSeconds() + 1);

        notifyExpired(RedisKeys.seat(SCREENING, 101L));

        assertThat(bookingRepository.findById(hold.bookingId()).orElseThrow().getStatus())
            .isEqualTo(BookingStatus.EXPIRED);
    }

    /** 좌석 4개면 알림도 4개 온다 — 조건부 UPDATE가 두 번째부터 막는다(이벤트도 1번) */
    @Test
    void repeatedNotificationsExpireOnlyOnce() {
        SeatHoldResponse hold = hold(101L, 102L);
        clock.advanceSeconds(BookingPolicy.HOLD_TTL.toSeconds() + 1);

        notifyExpired(RedisKeys.seat(SCREENING, 101L));
        notifyExpired(RedisKeys.seat(SCREENING, 102L));

        assertThat(bookingRepository.findById(hold.bookingId()).orElseThrow().getStatus())
            .isEqualTo(BookingStatus.EXPIRED);
        assertThat(redis.opsForValue().get(RedisKeys.seatSeq(SCREENING))).isEqualTo("2");   // HELD + EXPIRED
    }

    @Test
    void otherKeyExpiryNotificationsAreIgnored() {
        SeatHoldResponse hold = hold(101L);
        clock.advanceSeconds(BookingPolicy.HOLD_TTL.toSeconds() + 1);

        notifyExpired(RedisKeys.userHold(SCREENING, USER));

        assertThat(bookingRepository.findById(hold.bookingId()).orElseThrow().getStatus())
            .isEqualTo(BookingStatus.PENDING);
    }

    // --- 결제 중 보류 ---

    @Test
    void holdWithPaymentInProgressIsDeferredAndStaysInTheLedger() {
        SeatHoldResponse hold = hold(101L);
        String holdId = bookingRepository.findById(hold.bookingId()).orElseThrow().getHoldId();
        startPayment(hold.bookingId());
        clock.advanceSeconds(BookingPolicy.HOLD_TTL.toSeconds() + 1);

        assertThat(holdExpiryService.expire(hold.bookingId())).isEqualTo(Outcome.DEFERRED);

        sweeper.sweepLedger();

        assertThat(bookingRepository.findById(hold.bookingId()).orElseThrow().getStatus())
            .isEqualTo(BookingStatus.PENDING);
        // 장부에 남아 있어야 결제가 끝난 뒤 다시 본다
        assertThat(redis.opsForZSet().score(RedisKeys.holdExpiry(),
            RedisKeys.holdExpiryMember(SCREENING, holdId))).isNotNull();
    }

    // --- 장기 중단 후 첫 스윕 (ADR-34 노드 0대) ---

    /**
     * 평일 23:07~08:23과 주말에 노드가 0대로 내려간다. 재기동 직후에는 만료 지난 PENDING이
     * 한꺼번에 밀려 있고 장부에는 고아 멤버가 섞여 있다. 한 주기에 묶어 처리하고(배치),
     * 남은 건은 다음 주기가 이어받아야 한다 — 그 사이 Hikari 연결 5개를 다 쓰지 않는다.
     */
    @Test
    void firstSweepAfterLongDowntimeDrainsInBatchesAndExpiresEachOnce() {
        int holds = BookingPolicy.EXPIRY_SCAN_BATCH + 20;
        List<Long> bookingIds = IntStream.range(0, holds)
            .mapToObj(i -> hold("user-" + i, 101L + i).bookingId())
            .toList();
        // 장부에 고아 멤버도 섞어 둔다 (좌석 키·예매가 이미 사라진 경우)
        IntStream.range(0, 5).forEach(i -> redis.opsForZSet().add(RedisKeys.holdExpiry(),
            RedisKeys.holdExpiryMember(SCREENING, UUID.randomUUID().toString()), HELD_AT.toEpochMilli()));
        clock.advanceSeconds(BookingPolicy.HOLD_TTL.toSeconds() + 36_000);   // 밤새 멈춰 있었다

        sweeper.sweepLedger();   // 첫 주기 — 배치 상한까지만

        long expiredAfterFirst = bookingIds.stream()
            .filter(id -> bookingRepository.findById(id).orElseThrow().getStatus() == BookingStatus.EXPIRED)
            .count();
        assertThat(expiredAfterFirst).isLessThanOrEqualTo(BookingPolicy.EXPIRY_SCAN_BATCH);
        assertThat(expiredAfterFirst).isPositive();

        sweeper.sweepLedger();   // 다음 주기가 이어받는다
        sweeper.sweepDatabase(); // 장부에서 빠진 것도 RDS 스캔이 치운다

        assertThat(bookingIds).allSatisfy(id ->
            assertThat(bookingRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.EXPIRED));
        assertThat(bookingIds).allSatisfy(id ->
            assertThat(bookingSeatRepository.findSeatIdsByBookingId(id)).isEmpty());
        // 장부가 비었다 — 고아 멤버까지 정리됐다
        assertThat(redis.opsForZSet().size(RedisKeys.holdExpiry())).isZero();
        // 선점 HELD × holds + 만료 EXPIRED × holds (각각 한 번씩만)
        assertThat(redis.opsForValue().get(RedisKeys.seatSeq(SCREENING)))
            .isEqualTo(String.valueOf(holds * 2L));
    }

    @Test
    void alreadySettledHoldIsReportedAsSettled() {
        SeatHoldResponse hold = hold(101L);
        clock.advanceSeconds(BookingPolicy.HOLD_TTL.toSeconds() + 1);

        assertThat(holdExpiryService.expire(hold.bookingId())).isEqualTo(Outcome.EXPIRED);
        assertThat(holdExpiryService.expire(hold.bookingId())).isEqualTo(Outcome.ALREADY_SETTLED);
    }

    // --- 거들기 ---

    private SeatHoldResponse hold(Long... seatIds) {
        return hold(USER, seatIds);
    }

    private SeatHoldResponse hold(String userId, Long... seatIds) {
        return seatHoldService.hold(userId, new SeatHoldRequest(SCREENING, List.of(seatIds)));
    }

    /** BKG-10(결제)은 3.2.6이라 current_payment_id만 직접 채워 「결제 중」 상태를 만든다 */
    private void startPayment(Long bookingId) {
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "update Booking b set b.currentPaymentId = :paymentId where b.bookingId = :bookingId")
            .setParameter("paymentId", UUID.randomUUID().toString())
            .setParameter("bookingId", bookingId)
            .executeUpdate());
    }

    private void notifyExpired(String key) {
        keyExpiredListener.onMessage(
            new DefaultMessage("__keyevent@0__:expired".getBytes(), key.getBytes()), null);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MovableClockConfig {

        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock(HELD_AT);
        }
    }

    /** 300초를 기다리지 않고 만료 상황을 만드는 시계 */
    static final class MovableClock extends Clock {

        private volatile Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        void advanceSeconds(long seconds) {
            this.now = now.plusSeconds(seconds);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}

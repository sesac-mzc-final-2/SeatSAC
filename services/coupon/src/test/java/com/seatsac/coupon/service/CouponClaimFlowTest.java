package com.seatsac.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.test.MySqlContainerSupport;
import com.seatsac.common.test.ValkeyContainerSupport;
import com.seatsac.coupon.common.CouponErrorCode;
import com.seatsac.coupon.common.RedisKeys;
import com.seatsac.coupon.dto.CouponEventCreateRequest;
import com.seatsac.coupon.dto.CouponEventResponse;
import com.seatsac.coupon.dto.CouponIssueResponse;
import com.seatsac.coupon.entity.CouponEvent;
import com.seatsac.coupon.entity.CouponEventState;
import com.seatsac.coupon.entity.CouponIssue;
import com.seatsac.coupon.entity.CouponIssueState;
import com.seatsac.coupon.repository.CouponEventRepository;
import com.seatsac.coupon.repository.CouponIssueRepository;
import com.seatsac.coupon.repository.CouponReservationRepository;
import com.seatsac.coupon.stock.CouponStock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CPN-01~03 흐름(22-1·22-2) — MySQL·Valkey를 실제로 띄운다. 핵심은 「총수량을 넘겨 발급하지 않는다」와 「1인 1매」:
 * 동시 요청, 재고 키가 사라지거나 틀린 값일 때도 DB가 최종 방어선인지 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({MySqlContainerSupport.class, ValkeyContainerSupport.class})
class CouponClaimFlowTest {

    private static final String VIP = "VIP";

    @Autowired
    CouponEventService eventService;
    @Autowired
    CouponClaimService claims;
    @Autowired
    CouponQueryService queries;
    @Autowired
    CouponExpirySweeper sweeper;
    @Autowired
    CouponEventRepository events;
    @Autowired
    CouponIssueRepository issues;
    @Autowired
    CouponReservationRepository reservations;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    CouponStock stock;

    @BeforeEach
    void setUp() {
        reservations.deleteAllInBatch();
        issues.deleteAllInBatch();
        events.deleteAllInBatch();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void vipClaimIssuesOneAndDecrementsStock() {
        long eventId = openEvent(3);
        String user = user();

        CouponIssueResponse issued = claims.claim(user, VIP, eventId, key());

        assertThat(issued.state()).isEqualTo(CouponIssueState.ISSUED);
        assertThat(issued.discountRate()).isEqualTo(50);
        assertThat(stockKey(eventId)).isEqualTo("2");
        assertThat(events.findById(eventId)).get().extracting(CouponEvent::getIssuedQuantity).isEqualTo(1);
        assertThat(queries.current(user).myIssue().couponIssueId()).isEqualTo(issued.couponIssueId());
        assertThat(queries.current(user).remainingQuantity()).isEqualTo(2);
        assertThat(queries.mine(user).items()).extracting(CouponIssueResponse::couponIssueId)
            .containsExactly(issued.couponIssueId());
    }

    @Test
    void normalGradeIs403AndTouchesNothing() {
        long eventId = openEvent(3);

        assertError(() -> claims.claim(user(), "NORMAL", eventId, key()), CouponErrorCode.COUPON_NOT_ELIGIBLE);
        assertError(() -> claims.claim(user(), null, eventId, key()), CouponErrorCode.COUPON_NOT_ELIGIBLE);

        assertThat(stockKey(eventId)).isEqualTo("3");
        assertThat(issues.count()).isZero();
    }

    @Test
    void sameKeyReplaysFirstResponseAndOtherEventIsKeyConflict() {
        long eventId = openEvent(3);
        String user = user();
        String key = key();

        CouponIssueResponse first = claims.claim(user, VIP, eventId, key);
        CouponIssueResponse again = claims.claim(user, VIP, eventId, key);

        assertThat(again).isEqualTo(first);
        assertThat(stockKey(eventId)).isEqualTo("2");   // 재생은 재고를 건드리지 않는다
        long other = eventService.create(request(OffsetDateTime.now(ZoneOffset.ofHours(9)).plusHours(2), 5)).eventId();
        assertError(() -> claims.claim(user, VIP, other, key), CouponErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }

    @Test
    void lostIdempotencyRecordStillReplays201ForTheSameKey() {
        long eventId = openEvent(3);
        String user = user();
        String key = key();
        CouponIssueResponse first = claims.claim(user, VIP, eventId, key);
        redis.delete(RedisKeys.idempotency(user, key));   // 발급은 커밋됐는데 멱등 결과 저장이 실패한 것과 같다

        CouponIssueResponse again = claims.claim(user, VIP, eventId, key);

        assertThat(again.couponIssueId()).isEqualTo(first.couponIssueId());
        assertThat(stockKey(eventId)).isEqualTo("2");
        assertError(() -> claims.claim(user, VIP, eventId, key()), CouponErrorCode.COUPON_ALREADY_CLAIMED);
    }

    @Test
    void giveBackDoesNotCreateAMissingStockKey() {
        long eventId = openEvent(3);
        redis.delete(RedisKeys.stock(eventId));

        stock.giveBack(eventId);

        assertThat(stockKey(eventId)).isNull();   // 없는 키를 1로 만들지 않는다 — 다음 발급이 DB로 다시 만든다
        claims.claim(user(), VIP, eventId, key());
        assertThat(stockKey(eventId)).isEqualTo("2");
    }

    @Test
    void concurrentOverlappingCreatesKeepOnlyOne() throws Exception {
        OffsetDateTime startsAt = OffsetDateTime.now(ZoneOffset.ofHours(9)).plusHours(3);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            int offset = i;
            results.add(pool.submit(() -> {
                start.await();
                try {
                    eventService.create(request(startsAt.plusMinutes(offset), 5));
                    return true;
                } catch (BusinessException e) {
                    assertThat(e.code()).isEqualTo(CouponErrorCode.EVENT_OVERLAP);
                    return false;
                }
            }));
        }
        start.countDown();
        int created = 0;
        for (Future<Boolean> result : results) {
            if (result.get(30, TimeUnit.SECONDS)) {
                created++;
            }
        }
        pool.shutdown();

        assertThat(created).isEqualTo(1);
        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void otherKeyAfterClaimIsAlreadyClaimedWithTheIssueId() {
        long eventId = openEvent(3);
        String user = user();
        CouponIssueResponse first = claims.claim(user, VIP, eventId, key());

        BusinessException e = assertError(() -> claims.claim(user, VIP, eventId, key()),
            CouponErrorCode.COUPON_ALREADY_CLAIMED);

        assertThat(e.details().get(0).value()).isEqualTo(first.couponIssueId());
        assertThat(stockKey(eventId)).isEqualTo("2");
    }

    @Test
    void soldOutForNewUserButAlreadyClaimedForTheLastWinner() {
        long eventId = openEvent(1);
        String winner = user();
        claims.claim(winner, VIP, eventId, key());

        assertError(() -> claims.claim(user(), VIP, eventId, key()), CouponErrorCode.COUPON_SOLD_OUT);
        assertError(() -> claims.claim(winner, VIP, eventId, key()), CouponErrorCode.COUPON_ALREADY_CLAIMED);
        assertThat(stockKey(eventId)).isEqualTo("0");   // 음수로 깎인 만큼 되돌렸다
    }

    @Test
    void soldOutReplayIsTheSame409() {
        long eventId = openEvent(1);
        claims.claim(user(), VIP, eventId, key());
        String loser = user();
        String key = key();

        assertError(() -> claims.claim(loser, VIP, eventId, key), CouponErrorCode.COUPON_SOLD_OUT);
        assertError(() -> claims.claim(loser, VIP, eventId, key), CouponErrorCode.COUPON_SOLD_OUT);
    }

    @Test
    void concurrentClaimsNeverExceedTotalQuantity() throws Exception {
        long eventId = openEvent(10);
        int users = 40;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < users; i++) {
            String user = user();
            results.add(pool.submit(() -> {
                start.await();
                try {
                    claims.claim(user, VIP, eventId, key());
                    return true;
                } catch (BusinessException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int issuedCount = 0;
        for (Future<Boolean> result : results) {
            if (result.get(30, TimeUnit.SECONDS)) {
                issuedCount++;
            }
        }
        pool.shutdown();

        assertThat(issuedCount).isEqualTo(10);
        assertThat(issues.countByEventId(eventId)).isEqualTo(10);
        assertThat(events.findById(eventId)).get().extracting(CouponEvent::getIssuedQuantity).isEqualTo(10);
        assertThat(stockKey(eventId)).isEqualTo("0");
    }

    @Test
    void missingStockKeyIsRebuiltFromDb() {
        long eventId = openEvent(3);
        claims.claim(user(), VIP, eventId, key());
        redis.delete(RedisKeys.stock(eventId));

        claims.claim(user(), VIP, eventId, key());

        assertThat(stockKey(eventId)).isEqualTo("1");   // 3 − 발급 1 로 다시 만들고 1장 깎음
        assertThat(issues.countByEventId(eventId)).isEqualTo(2);
    }

    @Test
    void wrongHighStockKeyStillCannotOverIssue() {
        long eventId = openEvent(2);
        redis.opsForValue().set(RedisKeys.stock(eventId), "100");

        int ok = 0;
        for (int i = 0; i < 5; i++) {
            try {
                claims.claim(user(), VIP, eventId, key());
                ok++;
            } catch (BusinessException e) {
                assertThat(e.code()).isEqualTo(CouponErrorCode.COUPON_SOLD_OUT);
            }
        }

        assertThat(ok).isEqualTo(2);
        assertThat(issues.countByEventId(eventId)).isEqualTo(2);
    }

    @Test
    void upcomingEventIsNotOpen() {
        CouponEventResponse upcoming = eventService.create(request(OffsetDateTime.now(ZoneOffset.ofHours(9)).plusHours(2), 5));

        BusinessException e = assertError(() -> claims.claim(user(), VIP, upcoming.eventId(), key()),
            CouponErrorCode.COUPON_EVENT_NOT_OPEN);

        assertThat(e.details().get(0).reason()).isEqualTo("UPCOMING");
        assertThat(upcoming.state()).isEqualTo(CouponEventState.UPCOMING);
    }

    @Test
    void createRejectsPastStartAndOverlap() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.ofHours(9));
        assertError(() -> eventService.create(request(now.minusMinutes(5), 5)), CouponErrorCode.STARTS_IN_PAST);

        eventService.create(request(now.plusHours(1), 5));
        assertError(() -> eventService.create(request(now.plusHours(1).plusMinutes(30), 5)), CouponErrorCode.EVENT_OVERLAP);
        assertThat(eventService.list(null).items()).hasSize(1);
    }

    @Test
    void sweepExpiresIssuedOfEndedEventButKeepsReserved() {
        Instant now = Instant.now();
        CouponEvent ended = events.saveAndFlush(CouponEvent.create("끝난 이벤트", 30, VIP, 10,
            now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)), now.minus(Duration.ofHours(3))));
        CouponIssue issued = issues.saveAndFlush(CouponIssue.issue(ended, user(), now.minus(Duration.ofMinutes(90))));
        CouponIssue reserved = issues.saveAndFlush(CouponIssue.issue(ended, user(), now.minus(Duration.ofMinutes(90))));
        tx.executeWithoutResult(s -> issues.reserve(reserved.getCouponIssueId(), reserved.getUserId(),
            now.minus(Duration.ofMinutes(80))));

        assertThat(sweeper.sweepOnce()).isEqualTo(1);

        assertThat(issues.findById(issued.getCouponIssueId())).get().extracting(CouponIssue::getState)
            .isEqualTo(CouponIssueState.EXPIRED);
        assertThat(issues.findById(reserved.getCouponIssueId())).get().extracting(CouponIssue::getState)
            .isEqualTo(CouponIssueState.RESERVED);
    }

    // --- helpers ---

    private long openEvent(int quantity) {
        return eventService.create(request(OffsetDateTime.now(ZoneOffset.ofHours(9)), quantity)).eventId();
    }

    private static CouponEventCreateRequest request(OffsetDateTime startsAt, int quantity) {
        return new CouponEventCreateRequest("VIP 선착순 50% 할인", 50, startsAt, 60, quantity, VIP);
    }

    private String stockKey(long eventId) {
        return redis.opsForValue().get(RedisKeys.stock(eventId));
    }

    private static String user() {
        return UUID.randomUUID().toString();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static BusinessException assertError(Runnable call, CouponErrorCode expected) {
        try {
            call.run();
        } catch (BusinessException e) {
            assertThat(e.code()).isEqualTo(expected);
            return e;
        }
        throw new AssertionError("오류가 나야 한다: " + expected);
    }
}

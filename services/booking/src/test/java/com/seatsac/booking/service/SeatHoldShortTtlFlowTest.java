package com.seatsac.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.common.RedisKeys;
import com.seatsac.booking.component.CatalogClient;
import com.seatsac.booking.component.CatalogClient.AuditoriumLayout;
import com.seatsac.booking.component.CatalogClient.AuditoriumLayout.LayoutSeat;
import com.seatsac.booking.component.ScreeningCache;
import com.seatsac.booking.component.SeatHoldStrategy;
import com.seatsac.booking.dto.request.SeatHoldRequest;
import com.seatsac.booking.dto.response.SeatHoldResponse;
import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.booking.idempotency.IdempotencyStore;
import com.seatsac.booking.repository.BookingRepository;
import com.seatsac.booking.repository.BookingSeatRepository;
import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.CommonResponseCode;
import com.seatsac.common.test.MySqlContainerSupport;
import com.seatsac.common.test.ValkeyContainerSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 선점 TTL을 기본값이 아닌 값(데모 60초)으로 설정했을 때 TTL을 쓰는 곳이 모두 그 값을 따르는지 본다 —
 * RDS {@code expires_at}, 처리 중·고아 판정({@code isCommitInFlight}), 멱등 처리 중 키 TTL.
 * (Valkey 좌석·user-hold 키의 EX는 {@code SeatHoldTtlSettingTest})
 */
@SpringBootTest(properties = "app.seat-hold.ttl-seconds=60")
@ActiveProfiles("test")
@Import({MySqlContainerSupport.class, ValkeyContainerSupport.class, SeatHoldShortTtlFlowTest.FixedClockConfig.class})
class SeatHoldShortTtlFlowTest {

    private static final String USER = "44444444-4444-4444-8444-444444444444";
    private static final Long SCREENING = 42L;
    private static final Instant NOW = Instant.parse("2026-10-23T03:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(60);
    /** 멱등 키로 보내는 UUID. 비밀 값이 아니라 요청 식별자다 */
    private static final String REQUEST_KEY = "44444444-4444-4444-8444-555555555555";

    @MockitoBean
    CatalogClient catalogClient;

    @Autowired
    ScreeningCache screeningCache;
    @Autowired
    SeatHoldService seatHoldService;
    @Autowired
    SeatHoldStrategy seatHold;
    @Autowired
    IdempotencyStore idempotencyStore;
    @Autowired
    BookingRepository bookingRepository;
    @Autowired
    BookingSeatRepository bookingSeatRepository;
    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        bookingSeatRepository.deleteAllInBatch();
        bookingRepository.deleteAllInBatch();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        screeningCache.clear();

        given(catalogClient.findScreening(SCREENING)).willReturn(new CatalogClient.Screening(
            SCREENING, 10_004L, 1, NOW.plusSeconds(3600), NOW.plusSeconds(10_000),
            "승부", "건대입구", "1관"));
        given(catalogClient.findLayout(10_004L, 1)).willReturn(new AuditoriumLayout(10_004L, 1, List.of(
            new LayoutSeat(101L, "J7", true),
            new LayoutSeat(103L, "J9", true))));
    }

    @Test
    void expiresAtIsHoldTimePlusConfiguredTtl() {
        SeatHoldResponse response = seatHoldService.hold(USER, new SeatHoldRequest(SCREENING, List.of(101L)));

        Booking saved = bookingRepository.findById(response.bookingId()).orElseThrow();
        assertThat(saved.getExpiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(redis.opsForZSet().score(RedisKeys.holdExpiry(),
            RedisKeys.holdExpiryMember(SCREENING, saved.getHoldId())))
            .isEqualTo((double) NOW.plus(TTL).toEpochMilli());
    }

    /** 방금(유예 시간 안에) 만든 선점은 RDS에 없어도 커밋 중으로 본다 — 생성 시각 = 장부 점수 − 60초 */
    @Test
    void holdCreatedWithinGraceIsTreatedAsCommitting() {
        String committing = UUID.randomUUID().toString();
        seatHold.hold(SCREENING, USER, committing, List.of(103L), NOW.plus(TTL));

        assertThatThrownBy(() -> seatHoldService.hold(USER, new SeatHoldRequest(SCREENING, List.of(101L))))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.ACTIVE_HOLD_EXISTS.name());
        assertThat(redis.opsForValue().get(RedisKeys.userHold(SCREENING, USER))).isEqualTo(committing);
    }

    /** 유예 시간보다 오래된 선점은 고아다 — 60초 TTL로 계산해야 고아로 판정된다(300초로 계산하면 처리 중) */
    @Test
    void holdOlderThanGraceIsOrphanAndCleaned() {
        String orphan = UUID.randomUUID().toString();
        seatHold.hold(SCREENING, USER, orphan, List.of(103L),
            NOW.plus(TTL).minus(BookingPolicy.HOLD_COMMIT_GRACE).minusSeconds(1));

        SeatHoldResponse response = seatHoldService.hold(USER, new SeatHoldRequest(SCREENING, List.of(101L)));

        assertThat(response.status()).isEqualTo(BookingStatus.PENDING);
        assertThat(redis.opsForZSet().score(RedisKeys.holdExpiry(),
            RedisKeys.holdExpiryMember(SCREENING, orphan))).isNull();
    }

    @Test
    void idempotencyInProgressKeyUsesConfiguredTtl() {
        idempotencyStore.begin(USER, REQUEST_KEY, "hash-only-for-ttl-check");

        assertThat(redis.getExpire(RedisKeys.idempotency(USER, REQUEST_KEY), TimeUnit.SECONDS))
            .isBetween(TTL.toSeconds() - 5, TTL.toSeconds());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}

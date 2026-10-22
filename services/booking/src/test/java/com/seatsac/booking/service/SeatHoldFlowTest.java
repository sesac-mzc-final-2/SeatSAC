package com.seatsac.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.common.RedisKeys;
import com.seatsac.booking.component.CatalogClient;
import com.seatsac.booking.component.CatalogClient.AuditoriumLayout;
import com.seatsac.booking.component.CatalogClient.AuditoriumLayout.LayoutSeat;
import com.seatsac.booking.component.SeatHoldStrategy;
import com.seatsac.booking.dto.request.SeatHoldRequest;
import com.seatsac.booking.dto.response.SeatHoldResponse;
import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.booking.enums.SeatStatus;
import com.seatsac.booking.repository.BookingRepository;
import com.seatsac.booking.repository.BookingSeatRepository;
import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.CommonResponseCode;
import com.seatsac.common.test.MySqlContainerSupport;
import com.seatsac.common.test.ValkeyContainerSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * BKG-02 선점 → BKG-03 해제 전 과정 (명세 5장, `redis-usage.md` 5장).
 * MySQL·Valkey를 모두 띄우고 Catalog만 가짜로 둔다 — 순서(Lua → tx → 커밋 후 발행)가 지켜지는지 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({MySqlContainerSupport.class, ValkeyContainerSupport.class, SeatHoldFlowTest.FixedClockConfig.class})
class SeatHoldFlowTest {

    private static final String USER = "11111111-1111-4111-8111-111111111111";
    private static final Long SCREENING = 42L;
    private static final Instant NOW = Instant.parse("2026-10-23T03:00:00Z");

    @MockitoBean
    CatalogClient catalogClient;

    @Autowired
    com.seatsac.booking.component.ScreeningCache screeningCache;
    @Autowired
    SeatHoldService seatHoldService;
    @Autowired
    SeatReleaseService seatReleaseService;
    @Autowired
    SeatSnapshotService seatSnapshotService;
    @Autowired
    SeatHoldStrategy seatHold;
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
        screeningCache.clear();   // 파드 캐시는 빈 하나를 공유한다

        given(catalogClient.findScreening(SCREENING)).willReturn(new CatalogClient.Screening(
            SCREENING, 10_004L, 1, NOW.plusSeconds(3600), NOW.plusSeconds(10_000),
            "승부", "건대입구", "1관"));
        given(catalogClient.findLayout(10_004L, 1)).willReturn(new AuditoriumLayout(10_004L, 1, List.of(
            new LayoutSeat(101L, "J7", true),
            new LayoutSeat(102L, "J8", true),
            new LayoutSeat(103L, "J9", true),
            new LayoutSeat(104L, "J10", false))));   // 비활성 좌석
    }

    @Test
    void holdCreatesPendingBookingAndValkeyKeysAndPublishesEvent() {
        SeatHoldResponse response = seatHoldService.hold(USER, new SeatHoldRequest(SCREENING, List.of(101L, 102L)));

        assertThat(response.status()).isEqualTo(BookingStatus.PENDING);
        assertThat(response.seatLabels()).containsExactly("J7", "J8");
        assertThat(response.amount()).isEqualTo(30_000);
        assertThat(response.expiresAt().toInstant()).isEqualTo(NOW.plus(BookingPolicy.HOLD_TTL));

        Booking saved = bookingRepository.findById(response.bookingId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(bookingSeatRepository.findSeatIdsByBookingId(saved.getBookingId()))
            .containsExactlyInAnyOrder(101L, 102L);

        // Valkey: 좌석 키·user-hold·장부가 holdId로 채워졌다
        assertThat(redis.opsForValue().get(RedisKeys.seat(SCREENING, 101L))).isEqualTo(saved.getHoldId());
        assertThat(redis.opsForValue().get(RedisKeys.userHold(SCREENING, USER))).isEqualTo(saved.getHoldId());
        assertThat(redis.opsForZSet().score(RedisKeys.holdExpiry(),
            RedisKeys.holdExpiryMember(SCREENING, saved.getHoldId()))).isNotNull();
        // 커밋 후 발행: seq가 올라갔다
        assertThat(redis.opsForValue().get(RedisKeys.seatSeq(SCREENING))).isEqualTo("1");
    }

    @Test
    void holdRejectsSeatNotInLayout() {
        assertThatThrownBy(() -> seatHoldService.hold(USER, new SeatHoldRequest(SCREENING, List.of(101L, 104L))))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.VALIDATION_FAILED.name());   // 비활성 좌석 → INVALID_SEATS
        assertThat(redis.hasKey(RedisKeys.seat(SCREENING, 101L))).isFalse();
    }

    @Test
    void holdRejectsStartedScreening() {
        given(catalogClient.findScreening(SCREENING)).willReturn(new CatalogClient.Screening(
            SCREENING, 10_004L, 1, NOW.minusSeconds(10), NOW.plusSeconds(100), "승부", "건대입구", "1관"));

        assertThatThrownBy(() -> seatHoldService.hold(USER, new SeatHoldRequest(SCREENING, List.of(101L))))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.SCREENING_STARTED.name());
    }

    @Test
    void secondHoldInSameScreeningReturnsActiveHoldWithBookingId() {
        SeatHoldResponse first = seatHoldService.hold(USER, new SeatHoldRequest(SCREENING, List.of(101L)));

        assertThatThrownBy(() -> seatHoldService.hold(USER, new SeatHoldRequest(SCREENING, List.of(102L))))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException ex = (BusinessException) e;
                assertThat(ex.code().responseCode()).isEqualTo(CommonResponseCode.ACTIVE_HOLD_EXISTS.name());
                assertThat(ex.details()).anySatisfy(detail -> {
                    assertThat(detail.field()).isEqualTo("bookingId");
                    assertThat(detail.value()).isEqualTo(first.bookingId());
                });
            });
    }

    /** 고아 키(B3): Lua는 성공했는데 RDS 예매가 없다 → 정리하고 선점을 계속한다. 409가 아니다 */
    @Test
    void orphanUserHoldIsCleanedAndHoldSucceeds() {
        String orphanHoldId = UUID.randomUUID().toString();
        seatHold.hold(SCREENING, USER, orphanHoldId, List.of(103L), NOW.plus(BookingPolicy.HOLD_TTL));
        assertThat(bookingRepository.findByHoldId(orphanHoldId)).isEmpty();

        SeatHoldResponse response = seatHoldService.hold(USER, new SeatHoldRequest(SCREENING, List.of(101L)));

        assertThat(response.status()).isEqualTo(BookingStatus.PENDING);
        assertThat(redis.opsForValue().get(RedisKeys.userHold(SCREENING, USER)))
            .isNotEqualTo(orphanHoldId);
        assertThat(redis.opsForZSet().score(RedisKeys.holdExpiry(),
            RedisKeys.holdExpiryMember(SCREENING, orphanHoldId))).isNull();
    }

    @Test
    void takenSeatTellsWhetherItIsHeldOrReserved() {
        seatHoldService.hold("22222222-2222-4222-8222-222222222222",
            new SeatHoldRequest(SCREENING, List.of(101L)));

        assertThatThrownBy(() -> seatHoldService.hold(USER, new SeatHoldRequest(SCREENING, List.of(101L))))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException ex = (BusinessException) e;
                assertThat(ex.code().responseCode()).isEqualTo(CommonResponseCode.SEAT_UNAVAILABLE.name());
                assertThat(ex.details()).anySatisfy(detail -> {
                    assertThat(detail.value()).isEqualTo(101L);
                    assertThat(detail.reason()).isEqualTo(SeatStatus.HELD.name());
                });
            });
    }

    @Test
    void releaseCancelsBookingClearsValkeyAndPublishes() {
        SeatHoldResponse held = seatHoldService.hold(USER, new SeatHoldRequest(SCREENING, List.of(101L, 102L)));
        Booking booking = bookingRepository.findById(held.bookingId()).orElseThrow();

        var released = seatReleaseService.release(USER, held.bookingId());

        assertThat(released.status()).isEqualTo(BookingStatus.CANCELED);
        assertThat(released.canceledAt()).isNotNull();
        assertThat(bookingSeatRepository.findSeatIdsByBookingId(held.bookingId())).isEmpty();
        assertThat(redis.hasKey(RedisKeys.seat(SCREENING, 101L))).isFalse();
        assertThat(redis.hasKey(RedisKeys.userHold(SCREENING, USER))).isFalse();
        assertThat(redis.opsForZSet().score(RedisKeys.holdExpiry(),
            RedisKeys.holdExpiryMember(SCREENING, booking.getHoldId()))).isNull();
        assertThat(redis.opsForValue().get(RedisKeys.seatSeq(SCREENING))).isEqualTo("2");   // HELD, RELEASED
    }

    @Test
    void releaseRejectsOtherUsersBooking() {
        SeatHoldResponse held = seatHoldService.hold(USER, new SeatHoldRequest(SCREENING, List.of(101L)));

        assertThatThrownBy(() -> seatReleaseService.release("33333333-3333-4333-8333-333333333333",
            held.bookingId()))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.FORBIDDEN.name());
    }

    @Test
    void snapshotMergesRdsAndValkeyWithLayoutOrder() {
        seatHoldService.hold(USER, new SeatHoldRequest(SCREENING, List.of(101L)));

        var snapshot = seatSnapshotService.snapshot(SCREENING);

        assertThat(snapshot.screeningId()).isEqualTo(SCREENING);
        assertThat(snapshot.layoutVersion()).isEqualTo(1);
        assertThat(snapshot.seq()).isEqualTo(1L);
        // 비활성(104)은 배치에서 빠지고, 선점한 101만 HELD
        assertThat(snapshot.seats()).extracting(s -> s.seatId()).containsExactly(101L, 102L, 103L);
        assertThat(snapshot.seats()).extracting(s -> s.status())
            .containsExactly(SeatStatus.HELD, SeatStatus.AVAILABLE, SeatStatus.AVAILABLE);
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

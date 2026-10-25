package com.seatsac.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.component.CatalogClient;
import com.seatsac.booking.component.CatalogClient.AuditoriumLayout;
import com.seatsac.booking.component.CatalogClient.AuditoriumLayout.LayoutSeat;
import com.seatsac.booking.component.ScreeningCache;
import com.seatsac.booking.dto.request.SeatHoldRequest;
import com.seatsac.booking.dto.response.SeatCountsResponse;
import com.seatsac.booking.repository.BookingRepository;
import com.seatsac.booking.repository.BookingSeatRepository;
import com.seatsac.common.test.MySqlContainerSupport;
import com.seatsac.common.test.ValkeyContainerSupport;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 내부 좌석 수 조회 — catalog(CAT-03 2차)가 300ms 안에 받아 잔여석을 계산한다.
 * 선점은 실제 흐름으로 만들어, 집계가 {@code booking_seat} + 상태와 맞는지 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({MySqlContainerSupport.class, ValkeyContainerSupport.class})
class SeatCountFlowTest {

    private static final Long SCREENING = 42L;
    private static final Long OTHER_SCREENING = 43L;
    /** 서비스는 실제 시계를 쓴다 — 고정 시각을 쓰면 시간이 지나 「상영 시작됨」이 된다 */
    private static final Instant NOW = Instant.now();

    @MockitoBean
    CatalogClient catalogClient;

    @Autowired
    MockMvc mvc;
    @Autowired
    ScreeningCache screeningCache;
    @Autowired
    SeatHoldService seatHoldService;
    @Autowired
    SeatCountService seatCountService;
    @Autowired
    BookingRepository bookingRepository;
    @Autowired
    BookingSeatRepository bookingSeatRepository;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        bookingSeatRepository.deleteAllInBatch();
        bookingRepository.deleteAllInBatch();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        screeningCache.clear();

        List.of(SCREENING, OTHER_SCREENING).forEach(id ->
            given(catalogClient.findScreening(id)).willReturn(new CatalogClient.Screening(
                id, 10_004L, 1, NOW.plusSeconds(3600), NOW.plusSeconds(10_000),
                "승부", "건대입구", "1관")));
        given(catalogClient.findLayout(10_004L, 1)).willReturn(new AuditoriumLayout(10_004L, 1,
            IntStream.rangeClosed(1, 20).mapToObj(i -> new LayoutSeat(100L + i, "J" + i, true))
                .toList()));
    }

    @Test
    void countsHeldSeatsPerScreening() {
        seatHoldService.hold("11111111-1111-4111-8111-111111111111",
            new SeatHoldRequest(SCREENING, List.of(101L, 102L)));
        seatHoldService.hold("22222222-2222-4222-8222-222222222222",
            new SeatHoldRequest(OTHER_SCREENING, List.of(103L)));

        SeatCountsResponse counts = seatCountService.countFor(List.of(SCREENING, OTHER_SCREENING));

        assertThat(counts.items()).containsExactly(
            new SeatCountsResponse.SeatCountView(SCREENING, 2, 0),
            new SeatCountsResponse.SeatCountView(OTHER_SCREENING, 1, 0));
    }

    /** 예매가 없는 회차도 0으로 담는다 — 빠지면 catalog가 「조회 실패」와 구분할 수 없다 */
    @Test
    void screeningsWithoutBookingsAreReturnedAsZero() {
        SeatCountsResponse counts = seatCountService.countFor(List.of(SCREENING, 99L));

        assertThat(counts.items()).hasSize(2);
        assertThat(counts.items()).allSatisfy(item -> {
            assertThat(item.heldCount()).isZero();
            assertThat(item.reservedCount()).isZero();
        });
    }

    /** 요청 순서를 지킨다 (catalog가 순서로 짝지을 수 있게) */
    @Test
    void keepsRequestedOrder() {
        SeatCountsResponse counts = seatCountService.countFor(List.of(99L, SCREENING, 50L));

        assertThat(counts.items()).extracting(SeatCountsResponse.SeatCountView::screeningId)
            .containsExactly(99L, SCREENING, 50L);
    }

    @Test
    void releasedSeatsAreNoLongerCounted() {
        var hold = seatHoldService.hold("11111111-1111-4111-8111-111111111111",
            new SeatHoldRequest(SCREENING, List.of(101L, 102L)));

        mvcOk(SCREENING, 2);

        // 해제·만료와 같은 효과 (@Modifying 쿼리라 트랜잭션이 필요하다)
        transactions.executeWithoutResult(status ->
            bookingSeatRepository.deleteByBookingId(hold.bookingId()));

        mvcOk(SCREENING, 0);
    }

    // --- HTTP 층 ---

    @Test
    void endpointReturnsItemsForCatalog() throws Exception {
        seatHoldService.hold("11111111-1111-4111-8111-111111111111",
            new SeatHoldRequest(SCREENING, List.of(101L)));

        mvc.perform(get("/internal/screenings/seat-counts").param("ids", "42,43"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].screeningId").value(42))
            .andExpect(jsonPath("$.items[0].heldCount").value(1))
            .andExpect(jsonPath("$.items[0].reservedCount").value(0))
            .andExpect(jsonPath("$.items[1].screeningId").value(43))
            .andExpect(jsonPath("$.items[1].heldCount").value(0));
    }

    @Test
    void tooManyIdsIsRejected() throws Exception {
        String ids = IntStream.rangeClosed(1, BookingPolicy.MAX_SEAT_COUNT_IDS + 1)
            .mapToObj(String::valueOf).reduce((a, b) -> a + "," + b).orElseThrow();

        mvc.perform(get("/internal/screenings/seat-counts").param("ids", ids))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details[0].reason").value("TOO_MANY"));
    }

    @Test
    void missingIdsIsRejected() throws Exception {
        mvc.perform(get("/internal/screenings/seat-counts"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details").isArray());
    }

    private void mvcOk(Long screeningId, long expectedHeld) {
        SeatCountsResponse counts = seatCountService.countFor(List.of(screeningId));
        assertThat(counts.items().getFirst().heldCount()).isEqualTo(expectedHeld);
    }
}

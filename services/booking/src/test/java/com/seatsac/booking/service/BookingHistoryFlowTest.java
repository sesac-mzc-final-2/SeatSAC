package com.seatsac.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatsac.booking.dto.response.BookingSummaryResponse;
import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.booking.repository.BookingRepository;
import com.seatsac.booking.repository.BookingSeatRepository;
import com.seatsac.common.core.PageResponse;
import com.seatsac.common.test.MySqlContainerSupport;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BKG-14 예매 내역 (호출자 user, USR-04). RDS만 읽으므로 Valkey·Catalog 없이 돈다.
 *
 * <p>상태 전이는 3.2.5·3.2.6이 담당하므로 여기서는 상태를 직접 만들어 두고 <b>조회 규칙</b>만 본다 —
 * {@code PENDING} 제외, 최신순, 사용자 분리, 페이징.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MySqlContainerSupport.class)
class BookingHistoryFlowTest {

    private static final String USER = "11111111-1111-4111-8111-111111111111";
    private static final String OTHER_USER = "22222222-2222-4222-8222-222222222222";
    private static final Long SCREENING = 42L;
    private static final Instant BASE = Instant.parse("2026-10-20T03:00:00Z");

    @Autowired
    MockMvc mvc;
    @Autowired
    BookingHistoryService bookingHistoryService;
    @Autowired
    BookingRepository bookingRepository;
    @Autowired
    BookingSeatRepository bookingSeatRepository;
    @Autowired
    EntityManager entityManager;
    @Autowired
    TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        bookingSeatRepository.deleteAllInBatch();
        bookingRepository.deleteAllInBatch();
    }

    /** 예매를 만들고 상태를 그 값으로 바꿔 둔다 (전이 로직은 3.2.5·3.2.6) */
    private Long given(String userId, BookingStatus status, Instant createdAt) {
        Long bookingId = transactions.execute(tx -> bookingRepository.saveAndFlush(
            Booking.pending(userId, SCREENING, UUID.randomUUID().toString(), List.of("J7", "J8"),
                2, createdAt.plusSeconds(300), createdAt)).getBookingId());

        if (status != BookingStatus.PENDING) {
            transactions.executeWithoutResult(tx -> entityManager.createQuery("""
                    update Booking b
                       set b.status = :status,
                           b.confirmedAt = :confirmedAt,
                           b.canceledAt = :canceledAt
                     where b.bookingId = :bookingId
                    """)
                .setParameter("status", status)
                .setParameter("confirmedAt", status == BookingStatus.CONFIRMED ? createdAt : null)
                .setParameter("canceledAt", status == BookingStatus.CANCELED ? createdAt : null)
                .setParameter("bookingId", bookingId)
                .executeUpdate());
        }
        return bookingId;
    }

    // --- 조회 규칙 ---

    /** 선점 중({@code PENDING})은 내역이 아니다 — 진행 중인 결제이고 5분 뒤 사라질 수도 있다 */
    @Test
    void pendingIsNotPartOfTheHistory() {
        given(USER, BookingStatus.PENDING, BASE);
        Long confirmed = given(USER, BookingStatus.CONFIRMED, BASE.plusSeconds(60));

        PageResponse<BookingSummaryResponse> page = bookingHistoryService.findByUser(USER, null, 0, 20);

        assertThat(page.content()).extracting(BookingSummaryResponse::bookingId)
            .containsExactly(confirmed);
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    void returnsNewestFirst() {
        Long old = given(USER, BookingStatus.CANCELED, BASE);
        Long middle = given(USER, BookingStatus.PAYMENT_FAILED, BASE.plusSeconds(60));
        Long newest = given(USER, BookingStatus.CONFIRMED, BASE.plusSeconds(120));

        PageResponse<BookingSummaryResponse> page = bookingHistoryService.findByUser(USER, null, 0, 20);

        assertThat(page.content()).extracting(BookingSummaryResponse::bookingId)
            .containsExactly(newest, middle, old);
    }

    @Test
    void filtersByStatus() {
        given(USER, BookingStatus.CONFIRMED, BASE);
        Long canceled = given(USER, BookingStatus.CANCELED, BASE.plusSeconds(60));

        PageResponse<BookingSummaryResponse> page =
            bookingHistoryService.findByUser(USER, BookingStatus.CANCELED, 0, 20);

        assertThat(page.content()).extracting(BookingSummaryResponse::bookingId)
            .containsExactly(canceled);
    }

    @Test
    void doesNotLeakOtherUsersBookings() {
        given(OTHER_USER, BookingStatus.CONFIRMED, BASE);

        assertThat(bookingHistoryService.findByUser(USER, null, 0, 20).content()).isEmpty();
    }

    @Test
    void pagesWithTotals() {
        given(USER, BookingStatus.CONFIRMED, BASE);
        given(USER, BookingStatus.CONFIRMED, BASE.plusSeconds(60));
        given(USER, BookingStatus.CONFIRMED, BASE.plusSeconds(120));

        PageResponse<BookingSummaryResponse> first = bookingHistoryService.findByUser(USER, null, 0, 2);
        PageResponse<BookingSummaryResponse> second = bookingHistoryService.findByUser(USER, null, 1, 2);

        assertThat(first.content()).hasSize(2);
        assertThat(second.content()).hasSize(1);
        assertThat(first.totalElements()).isEqualTo(3);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.page()).isZero();
        assertThat(first.size()).isEqualTo(2);
    }

    /** 좌석 번호는 {@code seat_labels}에서 온다 — 끝난 예매는 {@code booking_seat} 행이 없다 */
    @Test
    void seatLabelsComeFromTheBookingRow() {
        given(USER, BookingStatus.EXPIRED, BASE);

        assertThat(bookingHistoryService.findByUser(USER, null, 0, 20).content())
            .singleElement()
            .satisfies(item -> {
                assertThat(item.seatLabels()).containsExactly("J7", "J8");
                assertThat(item.seatCount()).isEqualTo(2);
                assertThat(item.amount()).isEqualTo(30_000);
                assertThat(item.createdAt()).isNotNull();
            });
    }

    // --- HTTP 층 ---

    @Test
    void endpointReturnsPagedShapeForUserService() throws Exception {
        given(USER, BookingStatus.CONFIRMED, BASE);

        mvc.perform(get("/internal/bookings").param("userId", USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].status").value("CONFIRMED"))
            .andExpect(jsonPath("$.content[0].seatLabels[0]").value("J7"))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.totalPages").value(1));
    }

    /** 내부 API는 인증을 쓰지 않는다 — 형식 오류는 400이다(계약에 401 응답이 없다) */
    @Test
    void userIdMustBeCognitoSub() throws Exception {
        mvc.perform(get("/internal/bookings").param("userId", "not-a-uuid"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details[0].field").value("userId"))
            .andExpect(jsonPath("$.details[0].reason").value("INVALID_USER_ID"));
    }

    /** PENDING을 콕 집어 요청하면 400 — 조용히 빈 목록을 주면 호출자가 오해한다 */
    @Test
    void pendingStatusParameterIsRejected() throws Exception {
        mvc.perform(get("/internal/bookings").param("userId", USER).param("status", "PENDING"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details[0].reason").value("NOT_IN_HISTORY"));
    }

    @Test
    void sizeOverFiftyIsRejected() throws Exception {
        mvc.perform(get("/internal/bookings").param("userId", USER).param("size", "51"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details[0].reason").value("OUT_OF_RANGE"));
    }
}

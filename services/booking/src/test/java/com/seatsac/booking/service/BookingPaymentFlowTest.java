package com.seatsac.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.booking.common.RedisKeys;
import com.seatsac.booking.component.CatalogClient;
import com.seatsac.booking.component.CatalogClient.AuditoriumLayout;
import com.seatsac.booking.component.CatalogClient.AuditoriumLayout.LayoutSeat;
import com.seatsac.booking.component.PaymentClient;
import com.seatsac.booking.component.PaymentClient.PaymentCancelReason;
import com.seatsac.booking.component.PaymentClient.PaymentResult;
import com.seatsac.booking.component.ScreeningCache;
import com.seatsac.booking.dto.request.BookingPayRequest;
import com.seatsac.booking.dto.request.SeatHoldRequest;
import com.seatsac.booking.dto.response.BookingPaymentResponse;
import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.booking.enums.OutboxStatus;
import com.seatsac.booking.enums.PaymentMethod;
import com.seatsac.booking.repository.BookingRepository;
import com.seatsac.booking.repository.BookingSeatRepository;
import com.seatsac.booking.repository.OutboxMessageRepository;
import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.CommonResponseCode;
import com.seatsac.common.test.MySqlContainerSupport;
import com.seatsac.common.test.ValkeyContainerSupport;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BKG-10 결제 → BKG-06 확정·실패, BKG-07 취소 (명세 5·6장).
 * Payment·Catalog만 가짜로 두고 MySQL·Valkey는 실제로 띄워 <b>순서</b>를 본다 —
 * 확정과 아웃박스가 한 트랜잭션인지, 커밋 후에 발행·정리가 일어나는지.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({MySqlContainerSupport.class, ValkeyContainerSupport.class})
class BookingPaymentFlowTest {

    private static final String USER = "11111111-1111-4111-8111-111111111111";
    private static final Long SCREENING = 42L;
    private static final String CARD = "4111111111111112";

    @MockitoBean
    CatalogClient catalogClient;
    @MockitoBean
    PaymentClient paymentClient;

    @Autowired
    ScreeningCache screeningCache;
    @Autowired
    SeatHoldService seatHoldService;
    @Autowired
    BookingPaymentService bookingPaymentService;
    @Autowired
    BookingCancelService bookingCancelService;
    @Autowired
    BookingDetailService bookingDetailService;
    @Autowired
    BookingRepository bookingRepository;
    @Autowired
    BookingSeatRepository bookingSeatRepository;
    @Autowired
    OutboxMessageRepository outboxMessageRepository;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    TransactionTemplate transactions;
    @Autowired
    jakarta.persistence.EntityManager entityManager;

    @BeforeEach
    void setUp() {
        outboxMessageRepository.deleteAllInBatch();
        bookingSeatRepository.deleteAllInBatch();
        bookingRepository.deleteAllInBatch();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        screeningCache.clear();

        Instant now = Instant.now();
        given(catalogClient.findScreening(SCREENING)).willReturn(new CatalogClient.Screening(
            SCREENING, 10_004L, 1, now.plusSeconds(3600), now.plusSeconds(10_000),
            "승부", "건대입구", "1관"));
        given(catalogClient.findLayout(10_004L, 1)).willReturn(new AuditoriumLayout(10_004L, 1,
            IntStream.rangeClosed(1, 20).mapToObj(i -> new LayoutSeat(100L + i, "J" + i, true))
                .toList()));
    }

    private Long hold(Long... seatIds) {
        return seatHoldService.hold(USER, new SeatHoldRequest(SCREENING, List.of(seatIds)))
            .bookingId();
    }

    private static BookingPayRequest payRequest() {
        return new BookingPayRequest(PaymentMethod.CARD,
            new BookingPayRequest.Card(CARD, "12/28", "KIM"));
    }

    private PaymentResult approved(Long bookingId) {
        return new PaymentResult(UUID.randomUUID().toString(), bookingId, 30_000, "APPROVED",
            "1112", null, null, Instant.now(), null);
    }

    private PaymentResult declined(Long bookingId) {
        return new PaymentResult(UUID.randomUUID().toString(), bookingId, 30_000, "FAILED",
            "0000", "CARD_DECLINED", null, null, null);
    }

    // --- 승인 → 확정 ---

    @Test
    void approvedPaymentConfirmsBookingAndWritesOutboxInOneTransaction() throws Exception {
        Long bookingId = hold(101L, 102L);
        given(paymentClient.request(any())).willReturn(approved(bookingId));

        BookingPaymentResponse response = bookingPaymentService.pay(USER, bookingId, payRequest());

        assertThat(response.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(response.confirmedAt()).isNotNull();
        assertThat(response.seatLabels()).containsExactly("J1", "J2");   // 이 테스트 배치는 seat 101 → J1

        Booking confirmed = bookingRepository.findById(bookingId).orElseThrow();
        assertThat(confirmed.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(confirmed.getConfirmedAt()).isNotNull();
        // 좌석 행은 유지된다 (RESERVED)
        assertThat(bookingSeatRepository.findSeatIdsByBookingId(bookingId)).hasSize(2);

        // 같은 트랜잭션에서 아웃박스 행이 들어갔다
        var outbox = outboxMessageRepository.findAll();
        assertThat(outbox).singleElement().satisfies(row -> {
            assertThat(row.getAggregateId()).isEqualTo(bookingId);
            assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(row.getTraceId()).matches("^[0-9a-f]{32}$");
        });

        // 알림 본문이 계약 모양이다
        JsonNode event = objectMapper.readTree(outbox.getFirst().getPayload());
        assertThat(event.get("type").asText()).isEqualTo("booking.confirmed");
        assertThat(event.get("eventId").asText()).isEqualTo(outbox.getFirst().getMessageKey());
        assertThat(event.get("payload").get("movieTitle").asText()).isEqualTo("승부");
        assertThat(event.get("payload").get("seatLabels").get(0).asText()).isEqualTo("J1");
        assertThat(event.get("payload").get("amount").asInt()).isEqualTo(30_000);

        // 커밋 후: 선점 키 정리 + CONFIRMED 발행(선점 HELD 1 → 확정 2)
        assertThat(redis.hasKey(RedisKeys.seat(SCREENING, 101L))).isFalse();
        assertThat(redis.opsForValue().get(RedisKeys.seatSeq(SCREENING))).isEqualTo("2");
    }

    /** Catalog 조회가 실패해도 확정은 진행하고 이름·시각만 비운다 (계약이 null을 허용하는 이유) */
    @Test
    void catalogFailureEmptiesTheNotificationButStillConfirms() throws Exception {
        Long bookingId = hold(101L);
        given(paymentClient.request(any())).willReturn(approved(bookingId));
        screeningCache.clear();
        willThrow(new IllegalStateException("catalog down")).given(catalogClient)
            .findScreening(SCREENING);

        BookingPaymentResponse response = bookingPaymentService.pay(USER, bookingId, payRequest());

        assertThat(response.status()).isEqualTo(BookingStatus.CONFIRMED);
        JsonNode payload = objectMapper.readTree(outboxMessageRepository.findAll().getFirst()
            .getPayload()).get("payload");
        assertThat(payload.get("movieTitle").isNull()).isTrue();
        assertThat(payload.get("startsAt").isNull()).isTrue();
        assertThat(payload.get("seatCount").asInt()).isEqualTo(1);   // 우리 값은 그대로
    }

    // --- 거절 ---

    @Test
    void declinedPaymentReleasesSeatsAndKeepsNoOutbox() {
        Long bookingId = hold(101L, 102L);
        given(paymentClient.request(any())).willReturn(declined(bookingId));

        BookingPaymentResponse response = bookingPaymentService.pay(USER, bookingId, payRequest());

        assertThat(response.status()).isEqualTo(BookingStatus.PAYMENT_FAILED);
        assertThat(response.payment().failureCode()).isEqualTo("CARD_DECLINED");
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
            .isEqualTo(BookingStatus.PAYMENT_FAILED);
        assertThat(bookingSeatRepository.findSeatIdsByBookingId(bookingId)).isEmpty();
        assertThat(outboxMessageRepository.count()).isZero();   // 알림은 확정에만
        assertThat(redis.hasKey(RedisKeys.seat(SCREENING, 101L))).isFalse();
    }

    // --- 승인 뒤 만료 ---

    /** 승인을 받았는데 그 사이 선점이 만료되면 확정하지 않고 <b>환불</b>한다 */
    @Test
    void approvedButExpiredRefundsAndRejects() {
        Long bookingId = hold(101L);
        // 결제를 부르는 "사이에" 만료되는 상황 — 검증은 이미 지나갔다
        given(paymentClient.request(any())).willAnswer(invocation -> {
            expire(bookingId);
            return approved(bookingId);
        });

        assertThatThrownBy(() -> bookingPaymentService.pay(USER, bookingId, payRequest()))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.BOOKING_STATE_CONFLICT.name());

        verify(paymentClient).cancel(anyString(), org.mockito.ArgumentMatchers
            .eq(PaymentCancelReason.HOLD_EXPIRED));
        assertThat(outboxMessageRepository.count()).isZero();
        // 환불하고 여기서 끝낸다. 만료 스캔에 넘기면 안 된다 — 스캔은 결제를 요청한 예매
        // (current_payment_id 있음)를 계속 보류하므로 아무도 다시 조회하지 않으면 영구히 PENDING이다
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
            .isEqualTo(BookingStatus.EXPIRED);
        // 좌석 행도 같이 지운다. 남으면 좌석 현황에는 비어 보이는데 유일 제약 때문에 다시 선점되지 않는다
        assertThat(bookingSeatRepository.findSeatIdsByBookingId(bookingId)).isEmpty();
    }

    /** 승인 뒤 만료인데 <b>환불 호출이 실패</b>하면 예매를 PENDING으로 남긴다 — 다음 조회가 다시 환불한다 */
    @Test
    void approvedButExpiredKeepsPendingWhenRefundFails() {
        Long bookingId = hold(102L);
        given(paymentClient.request(any())).willAnswer(invocation -> {
            expire(bookingId);
            return approved(bookingId);
        });
        willThrow(new IllegalStateException("payment 응답 없음"))
            .given(paymentClient).cancel(anyString(), any());

        assertThatThrownBy(() -> bookingPaymentService.pay(USER, bookingId, payRequest()))
            .isInstanceOf(BusinessException.class);

        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
            .isEqualTo(BookingStatus.PENDING);
        assertThat(outboxMessageRepository.count()).isZero();
    }

    // --- 예매당 1회 ---

    @Test
    void secondPaymentRequestIsRejected() {
        Long bookingId = hold(101L);
        given(paymentClient.request(any())).willReturn(approved(bookingId));
        bookingPaymentService.pay(USER, bookingId, payRequest());

        assertThatThrownBy(() -> bookingPaymentService.pay(USER, bookingId, payRequest()))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.PAYMENT_ALREADY_REQUESTED.name());
    }

    @Test
    void otherUsersBookingCannotBePaid() {
        Long bookingId = hold(101L);

        assertThatThrownBy(() -> bookingPaymentService.pay(
            "22222222-2222-4222-8222-222222222222", bookingId, payRequest()))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.FORBIDDEN.name());
        verify(paymentClient, never()).request(any());
    }

    @Test
    void expiredHoldCannotBePaid() {
        Long bookingId = hold(101L);
        expire(bookingId);

        assertThatThrownBy(() -> bookingPaymentService.pay(USER, bookingId, payRequest()))
            .isInstanceOf(BusinessException.class);
        verify(paymentClient, never()).request(any());
    }

    // --- BKG-07 취소 ---

    @Test
    void confirmedBookingIsCanceledAndRefunded() {
        Long bookingId = hold(101L, 102L);
        PaymentResult payment = approved(bookingId);
        given(paymentClient.request(any())).willReturn(payment);
        bookingPaymentService.pay(USER, bookingId, payRequest());
        given(paymentClient.cancel(anyString(), any())).willReturn(new PaymentResult(
            payment.paymentId(), bookingId, 30_000, "CANCELED", "1112", null,
            "BOOKING_CANCELED", payment.approvedAt(), Instant.now()));

        var response = bookingCancelService.cancel(USER, bookingId);

        assertThat(response.status()).isEqualTo(BookingStatus.CANCELED);
        assertThat(response.payment().status()).isEqualTo("CANCELED");
        assertThat(bookingSeatRepository.findSeatIdsByBookingId(bookingId)).isEmpty();
        verify(paymentClient).cancel(anyString(), org.mockito.ArgumentMatchers
            .eq(PaymentCancelReason.BOOKING_CANCELED));
    }

    /** 환불 호출이 실패해도 취소는 유효하다 — PAY-03은 멱등이라 다시 부르면 된다 */
    @Test
    void cancelStandsEvenIfRefundCallFails() {
        Long bookingId = hold(101L);
        given(paymentClient.request(any())).willReturn(approved(bookingId));
        bookingPaymentService.pay(USER, bookingId, payRequest());
        willThrow(new IllegalStateException("payment down")).given(paymentClient)
            .cancel(anyString(), any());

        var response = bookingCancelService.cancel(USER, bookingId);

        assertThat(response.status()).isEqualTo(BookingStatus.CANCELED);
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
            .isEqualTo(BookingStatus.CANCELED);
    }

    @Test
    void cancelIsIdempotent() {
        Long bookingId = hold(101L);
        given(paymentClient.request(any())).willReturn(approved(bookingId));
        bookingPaymentService.pay(USER, bookingId, payRequest());
        given(paymentClient.cancel(anyString(), any())).willReturn(new PaymentResult(
            UUID.randomUUID().toString(), bookingId, 30_000, "CANCELED", "1112", null,
            "BOOKING_CANCELED", Instant.now(), Instant.now()));
        bookingCancelService.cancel(USER, bookingId);

        var again = bookingCancelService.cancel(USER, bookingId);

        assertThat(again.status()).isEqualTo(BookingStatus.CANCELED);
    }

    @Test
    void pendingBookingCannotBeCanceledWithBkg07() {
        Long bookingId = hold(101L);

        assertThatThrownBy(() -> bookingCancelService.cancel(USER, bookingId))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.BOOKING_STATE_CONFLICT.name());
    }

    // --- 단건 조회에서 결과 반영 (지연 카드 경로) ---

    /**
     * BKG-10이 결과를 못 받아 503을 돌려준 뒤, 화면이 단건 조회를 다시 부르면 그때 확정된다.
     */
    @Test
    void detailReconcilesLatePaymentApproval() {
        Long bookingId = hold(101L);
        PaymentResult processing = new PaymentResult(UUID.randomUUID().toString(), bookingId,
            30_000, "PROCESSING", "9999", null, null, null, null);
        given(paymentClient.request(any())).willThrow(
            com.seatsac.booking.common.BookingErrorCode.PAYMENT_RESULT_UNKNOWN.exception());

        assertThatThrownBy(() -> bookingPaymentService.pay(USER, bookingId, payRequest()))
            .isInstanceOf(BusinessException.class);
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
            .isEqualTo(BookingStatus.PENDING);

        // 아직 PROCESSING이면 그대로 둔다
        given(paymentClient.find(anyString())).willReturn(java.util.Optional.of(processing));
        assertThat(bookingDetailService.get(USER, bookingId).status())
            .isEqualTo(BookingStatus.PENDING);

        // 승인으로 바뀌면 그때 확정된다
        given(paymentClient.find(anyString())).willReturn(java.util.Optional.of(approved(bookingId)));
        var detail = bookingDetailService.get(USER, bookingId);

        assertThat(detail.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(outboxMessageRepository.count()).isEqualTo(1);
        assertThat(detail.seatIds()).containsExactly(101L);
    }

    @Test
    void detailHidesSeatIdsForFinishedBookings() {
        Long bookingId = hold(101L);
        given(paymentClient.request(any())).willReturn(declined(bookingId));
        bookingPaymentService.pay(USER, bookingId, payRequest());

        var detail = bookingDetailService.get(USER, bookingId);

        assertThat(detail.status()).isEqualTo(BookingStatus.PAYMENT_FAILED);
        assertThat(detail.seatIds()).isEmpty();
    }

    /** 선점 만료 시각을 과거로 돌린다 (상태 전이는 3.2.5 만료 스캔 몫이다) */
    private void expire(Long bookingId) {
        transactions.executeWithoutResult(tx -> entityManager
            .createQuery("update Booking b set b.expiresAt = :past where b.bookingId = :id")
            .setParameter("past", Instant.now().minusSeconds(10))
            .setParameter("id", bookingId)
            .executeUpdate());
    }
}

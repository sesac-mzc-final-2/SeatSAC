package com.seatsac.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.booking.common.RedisKeys;
import com.seatsac.booking.common.ScreeningNotFoundException;
import com.seatsac.booking.component.CatalogClient;
import com.seatsac.booking.component.CatalogClient.AuditoriumLayout;
import com.seatsac.booking.component.CatalogClient.AuditoriumLayout.LayoutSeat;
import com.seatsac.booking.component.PaymentClient;
import com.seatsac.booking.component.PaymentClient.PaymentCancelReason;
import com.seatsac.booking.component.PaymentClient.PaymentResult;
import com.seatsac.booking.component.ScreeningCache;
import com.seatsac.booking.dto.request.BookingPayRequest;
import com.seatsac.booking.dto.request.PaymentPrepareRequest;
import com.seatsac.booking.dto.request.SeatHoldRequest;
import com.seatsac.booking.dto.response.BookingPaymentResponse;
import com.seatsac.booking.dto.response.PaymentPrepareResponse;
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
    /** application-test.yml {@code app.payment.mock-key} */
    private static final String MOCK_KEY = "test-mock-key";

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

    /** 같은 회차에 사용자당 선점 1건이라 두 번째 예매는 다른 사용자로 만든다 */
    private static final String OTHER = "33333333-3333-4333-8333-333333333333";

    private Long holdAs(String userId, Long... seatIds) {
        return seatHoldService.hold(userId, new SeatHoldRequest(SCREENING, List.of(seatIds))).bookingId();
    }

    private static BookingPayRequest payRequest() {
        return new BookingPayRequest(PaymentMethod.CARD, null,
            new BookingPayRequest.Card(CARD, "12/28", "KIM"));
    }

    private static BookingPayRequest tossPayRequest(String paymentId) {
        return new BookingPayRequest(PaymentMethod.TOSSPAY, paymentId, null);
    }

    /** 준비 응답 — payment가 PROCESSING 행 + 결제창 값을 돌려준다 */
    private PaymentResult preparedFor(Long bookingId, String paymentId) {
        return new PaymentResult(paymentId, bookingId, 30_000, "PROCESSING", null, null, null, null,
            null, "TOSSPAY", "PORTONE", null,
            new PaymentClient.Checkout("PORTONE", "store-test", "channel-key-test", "EASY_PAY", "KRW"));
    }

    private PaymentResult tossPayApproved(Long bookingId, String paymentId) {
        return new PaymentResult(paymentId, bookingId, 30_000, "APPROVED", null, null, null,
            Instant.now(), null, "TOSSPAY", "PORTONE", "tx-portone-1", null);
    }

    private PaymentResult tossPayFailed(Long bookingId, String paymentId, String failureCode) {
        return new PaymentResult(paymentId, bookingId, 30_000, "FAILED", null, failureCode, null,
            null, null, "TOSSPAY", "PORTONE", null, null);
    }

    /** 준비까지 한다 — payment 준비 응답은 넘긴 paymentId를 그대로 돌려준다 */
    private PaymentPrepareResponse prepare(Long bookingId) {
        // willAnswer…given 형태 — given(mock.prepare(any()))를 두 번 부르면 앞 스텁이 null 인자로 불린다
        org.mockito.BDDMockito.willAnswer(invocation -> {
            PaymentClient.PaymentRequest request = invocation.getArgument(0);
            return preparedFor(bookingId, request.paymentId());
        }).given(paymentClient).prepare(any());
        return bookingPaymentService.prepare(USER, bookingId, new PaymentPrepareRequest(PaymentMethod.TOSSPAY));
    }

    private static String reasonOf(Throwable e) {
        return ((BusinessException) e).details().stream()
            .map(com.seatsac.common.core.error.ErrorDetail::reason)
            .filter(java.util.Objects::nonNull)
            .findFirst().orElse(null);
    }

    private PaymentResult approved(Long bookingId) {
        return new PaymentResult(UUID.randomUUID().toString(), bookingId, 30_000, "APPROVED",
            "1112", null, null, Instant.now(), null);
    }

    /** 결제 취소(환불) 응답 — 상태가 CANCELED여야 환불로 본다 */
    private PaymentResult canceledFor(Long bookingId) {
        return new PaymentResult(UUID.randomUUID().toString(), bookingId, 30_000, "CANCELED",
            "1112", null, "HOLD_EXPIRED", Instant.now(), Instant.now());
    }

    private PaymentResult declined(Long bookingId) {
        return new PaymentResult(UUID.randomUUID().toString(), bookingId, 30_000, "FAILED",
            "0000", "CARD_DECLINED", null, null, null);
    }

    // --- 승인 → 확정 ---

    @Test
    void approvedPaymentConfirmsBookingAndWritesOutboxInOneTransaction() throws Exception {
        Long bookingId = hold(101L, 102L);
        long seqAfterHold = Long.parseLong(redis.opsForValue().get(RedisKeys.seatSeq(SCREENING)));
        given(paymentClient.request(any())).willReturn(approved(bookingId));

        BookingPaymentResponse response = bookingPaymentService.pay(USER, MOCK_KEY, bookingId, payRequest());

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
        assertThat(redis.opsForValue().get(RedisKeys.seatSeq(SCREENING))).isEqualTo(String.valueOf(seqAfterHold + 1));
    }

    /**
     * 승인 뒤 알림 본문의 Catalog 조회가 실패해도 확정은 진행하고 이름·시각만 비운다 (계약이 null을 허용하는 이유).
     * 결제 전 마감 확인은 선점 때 채운 파드 캐시로 통과한다 — 알림 본문은 Catalog를 직접 부른다
     */
    @Test
    void catalogFailureEmptiesTheNotificationButStillConfirms() throws Exception {
        Long bookingId = hold(101L);
        given(paymentClient.request(any())).willReturn(approved(bookingId));
        willThrow(new IllegalStateException("catalog down")).given(catalogClient)
            .findScreening(SCREENING);

        BookingPaymentResponse response = bookingPaymentService.pay(USER, MOCK_KEY, bookingId, payRequest());

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

        BookingPaymentResponse response = bookingPaymentService.pay(USER, MOCK_KEY, bookingId, payRequest());

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
        given(paymentClient.cancel(anyString(), any())).willReturn(canceledFor(bookingId));
        // 결제를 부르는 "사이에" 만료되는 상황 — 검증은 이미 지나갔다
        given(paymentClient.request(any())).willAnswer(invocation -> {
            expire(bookingId);
            return approved(bookingId);
        });

        assertThatThrownBy(() -> bookingPaymentService.pay(USER, MOCK_KEY, bookingId, payRequest()))
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

        // 「만료·환불됨」(409 refunded=true)이 아니라 결과 미확인(503) — 돈이 아직 잡혀 있을 수 있다
        assertThatThrownBy(() -> bookingPaymentService.pay(USER, MOCK_KEY, bookingId, payRequest()))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.PAYMENT_RESULT_UNKNOWN.name());

        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
            .isEqualTo(BookingStatus.PENDING);
        assertThat(outboxMessageRepository.count()).isZero();
    }

    /** 취소 호출이 예외 없이 끝나도 응답이 CANCELED가 아니면 환불로 보지 않는다 */
    @Test
    void approvedButExpiredKeepsPendingWhenCancelIsNotConfirmed() {
        Long bookingId = hold(105L);
        PaymentResult approved = approved(bookingId);
        given(paymentClient.request(any())).willAnswer(invocation -> {
            expire(bookingId);
            return approved;
        });
        given(paymentClient.cancel(anyString(), any())).willReturn(approved);   // 아직 APPROVED

        assertThatThrownBy(() -> bookingPaymentService.pay(USER, MOCK_KEY, bookingId, payRequest()))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.PAYMENT_RESULT_UNKNOWN.name());
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
            .isEqualTo(BookingStatus.PENDING);
    }

    /**
     * 환불 응답만 못 받고 실제로는 취소된 경우: 다음 단건 조회가 결제를 CANCELED로 읽는다.
     * 거절(PAYMENT_FAILED)이 아니라 만료(EXPIRED)로 끝내야 한다 — 화면이 「결제 실패」가 아니라 「만료·환불」을 보여 준다
     */
    @Test
    void detailExpiresWhenRefundWentThroughButResponseWasLost() {
        Long bookingId = hold(106L);
        PaymentResult approved = approved(bookingId);
        given(paymentClient.request(any())).willAnswer(invocation -> {
            expire(bookingId);
            return approved;
        });
        willThrow(new IllegalStateException("payment 응답 없음(취소는 됐다)"))
            .given(paymentClient).cancel(anyString(), any());
        assertThatThrownBy(() -> bookingPaymentService.pay(USER, MOCK_KEY, bookingId, payRequest()))
            .isInstanceOf(BusinessException.class);

        PaymentResult canceled = new PaymentResult(approved.paymentId(), bookingId, 30_000, "CANCELED",
            "1112", null, "HOLD_EXPIRED", approved.approvedAt(), Instant.now());
        given(paymentClient.find(anyString())).willReturn(java.util.Optional.of(canceled));

        var detail = bookingDetailService.get(USER, bookingId);
        assertThat(detail.status()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(detail.payment().status()).isEqualTo("CANCELED");
        assertThat(bookingSeatRepository.findSeatIdsByBookingId(bookingId)).isEmpty();
        assertThat(redis.hasKey(RedisKeys.seat(SCREENING, 106L))).isFalse();
    }

    /** 환불을 확인하지 못한 예매는 다음 단건 조회가 다시 환불하고, 그때 EXPIRED로 끝낸다 */
    @Test
    void detailRetriesRefundLeftPendingByPayment() {
        Long bookingId = hold(103L);
        PaymentResult approved = approved(bookingId);
        given(paymentClient.request(any())).willAnswer(invocation -> {
            expire(bookingId);
            return approved;
        });
        willThrow(new IllegalStateException("payment 응답 없음"))
            .given(paymentClient).cancel(anyString(), any());
        assertThatThrownBy(() -> bookingPaymentService.pay(USER, MOCK_KEY, bookingId, payRequest()))
            .isInstanceOf(BusinessException.class);

        // 환불이 아직 안 되는 동안의 조회: 예매 PENDING + 결제 APPROVED (둘 다 사실이다)
        given(paymentClient.find(anyString())).willReturn(java.util.Optional.of(approved));
        var stillPending = bookingDetailService.get(USER, bookingId);
        assertThat(stillPending.status()).isEqualTo(BookingStatus.PENDING);
        assertThat(stillPending.payment().status()).isEqualTo("APPROVED");

        // payment가 돌아오면 조회가 환불하고 만료로 끝낸다. 응답의 결제는 다시 읽은 CANCELED
        PaymentResult canceled = new PaymentResult(approved.paymentId(), bookingId, 30_000, "CANCELED",
            "1112", null, "HOLD_EXPIRED", approved.approvedAt(), Instant.now());
        willReturn(canceled).given(paymentClient).cancel(anyString(), any());
        given(paymentClient.find(anyString()))
            .willReturn(java.util.Optional.of(approved), java.util.Optional.of(canceled));

        var detail = bookingDetailService.get(USER, bookingId);
        assertThat(detail.status()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(detail.payment().status()).isEqualTo("CANCELED");
    }

    /** 환불 뒤 결제를 다시 읽지 못하면 결제 없이 답한다 — 낡은 APPROVED를 EXPIRED 예매와 같이 내보내지 않는다 */
    @Test
    void detailOmitsStaleApprovalWhenRereadFailsAfterRefund() {
        Long bookingId = hold(104L);
        given(paymentClient.cancel(anyString(), any())).willReturn(canceledFor(bookingId));
        PaymentResult approved = approved(bookingId);
        given(paymentClient.request(any())).willThrow(
            com.seatsac.booking.common.BookingErrorCode.PAYMENT_RESULT_UNKNOWN.exception());
        assertThatThrownBy(() -> bookingPaymentService.pay(USER, MOCK_KEY, bookingId, payRequest()))
            .isInstanceOf(BusinessException.class);
        expire(bookingId);

        given(paymentClient.find(anyString()))
            .willReturn(java.util.Optional.of(approved))
            .willThrow(new IllegalStateException("payment 응답 없음"));

        var detail = bookingDetailService.get(USER, bookingId);
        assertThat(detail.status()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(detail.payment()).isNull();
    }

    // --- 예매당 1회 ---

    @Test
    void secondPaymentRequestIsRejected() {
        Long bookingId = hold(101L);
        given(paymentClient.request(any())).willReturn(approved(bookingId));
        bookingPaymentService.pay(USER, MOCK_KEY, bookingId, payRequest());

        assertThatThrownBy(() -> bookingPaymentService.pay(USER, MOCK_KEY, bookingId, payRequest()))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.PAYMENT_ALREADY_REQUESTED.name());
    }

    @Test
    void otherUsersBookingCannotBePaid() {
        Long bookingId = hold(101L);

        assertThatThrownBy(() -> bookingPaymentService.pay(
            "22222222-2222-4222-8222-222222222222", MOCK_KEY, bookingId, payRequest()))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.FORBIDDEN.name());
        verify(paymentClient, never()).request(any());
    }

    @Test
    void expiredHoldCannotBePaid() {
        Long bookingId = hold(101L);
        expire(bookingId);

        assertThatThrownBy(() -> bookingPaymentService.pay(USER, MOCK_KEY, bookingId, payRequest()))
            .isInstanceOf(BusinessException.class);
        verify(paymentClient, never()).request(any());
    }

    // --- 예매 마감 (상영 시작 5분 전) ---

    /** 마감 뒤 결제는 Payment를 부르지 않고 400 — 돈이 나가지 않고 예매는 PENDING 그대로(선점은 TTL로 풀린다) */
    @Test
    void paymentIsRejectedFromFiveMinutesBeforeStart() {
        Long bookingId = hold(101L);
        startScreeningIn(240);   // 4분 뒤 시작 = 마감 지남

        assertThatThrownBy(() -> bookingPaymentService.pay(USER, MOCK_KEY, bookingId, payRequest()))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException ex = (BusinessException) e;
                assertThat(ex.code().responseCode()).isEqualTo(CommonResponseCode.SCREENING_STARTED.name());
                assertThat(ex.details()).singleElement()
                    .satisfies(detail -> assertThat(detail.reason()).isEqualTo("BOOKING_CLOSED"));
            });

        Booking booking = bookingRepository.findById(bookingId).orElseThrow();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(booking.getCurrentPaymentId()).isNull();
        verify(paymentClient, never()).request(any());
    }

    /** 마감 전이면 결제한다 — 6분 뒤 시작 */
    @Test
    void paymentIsAcceptedBeforeTheCutoff() {
        Long bookingId = hold(101L);
        startScreeningIn(360);
        given(paymentClient.request(any())).willReturn(approved(bookingId));

        assertThat(bookingPaymentService.pay(USER, MOCK_KEY, bookingId, payRequest()).status())
            .isEqualTo(BookingStatus.CONFIRMED);
    }

    /** 마감 여부를 모르면 결제를 부르지 않는다 — 503, 아무것도 바뀌지 않는다 */
    @Test
    void paymentIsUnavailableWhenCatalogIsDown() {
        Long bookingId = hold(101L);
        screeningCache.clear();
        willThrow(new IllegalStateException("catalog down")).given(catalogClient).findScreening(SCREENING);

        assertThatThrownBy(() -> bookingPaymentService.pay(USER, MOCK_KEY, bookingId, payRequest()))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.SERVICE_UNAVAILABLE.name());

        assertThat(bookingRepository.findById(bookingId).orElseThrow().getCurrentPaymentId()).isNull();
        verify(paymentClient, never()).request(any());
    }

    // --- BKG-07 취소 ---

    @Test
    void confirmedBookingIsCanceledAndRefunded() {
        Long bookingId = hold(101L, 102L);
        PaymentResult payment = approved(bookingId);
        given(paymentClient.request(any())).willReturn(payment);
        bookingPaymentService.pay(USER, MOCK_KEY, bookingId, payRequest());
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
        bookingPaymentService.pay(USER, MOCK_KEY, bookingId, payRequest());
        willThrow(new IllegalStateException("payment down")).given(paymentClient)
            .cancel(anyString(), any());

        var response = bookingCancelService.cancel(USER, bookingId);

        assertThat(response.status()).isEqualTo(BookingStatus.CANCELED);
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
            .isEqualTo(BookingStatus.CANCELED);
    }

    /** 상영이 시작된 예매는 취소·환불하지 않는다 — 400 SCREENING_STARTED, 아무것도 바뀌지 않는다 (계약 BKG-07) */
    @Test
    void confirmedBookingCannotBeCanceledAfterScreeningStarted() {
        Long bookingId = confirmedBooking(101L);
        startScreening();

        assertThatThrownBy(() -> bookingCancelService.cancel(USER, bookingId))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.SCREENING_STARTED.name());

        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
            .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(bookingSeatRepository.findSeatIdsByBookingId(bookingId)).containsExactly(101L);
        verify(paymentClient, never()).cancel(anyString(), any());
    }

    /** 회차 정보를 못 받으면 상영 시작 여부를 알 수 없다 — 503, 취소·환불하지 않는다 */
    @Test
    void cancelIsUnavailableWhenCatalogIsDown() {
        Long bookingId = confirmedBooking(101L);
        screeningCache.clear();
        willThrow(new IllegalStateException("catalog down")).given(catalogClient).findScreening(SCREENING);

        assertThatThrownBy(() -> bookingCancelService.cancel(USER, bookingId))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.SERVICE_UNAVAILABLE.name());

        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
            .isEqualTo(BookingStatus.CONFIRMED);
        verify(paymentClient, never()).cancel(anyString(), any());
    }

    /** 예매가 가리키는 회차를 Catalog가 404로 답해도 「예매 없음」이 아니라 503이다. 아무것도 바뀌지 않는다 */
    @Test
    void cancelIsUnavailableWhenScreeningIsMissingInCatalog() {
        Long bookingId = confirmedBooking(101L);
        screeningCache.clear();
        willThrow(new ScreeningNotFoundException()).given(catalogClient).findScreening(SCREENING);

        assertThatThrownBy(() -> bookingCancelService.cancel(USER, bookingId))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.SERVICE_UNAVAILABLE.name());

        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
            .isEqualTo(BookingStatus.CONFIRMED);
        verify(paymentClient, never()).cancel(anyString(), any());
    }

    /** 시작 시각이 없는 회차 응답은 「시작 전」으로 보지 않는다 — 503, 취소·환불하지 않는다 */
    @Test
    void cancelIsUnavailableWhenStartTimeIsMissing() {
        Long bookingId = confirmedBooking(101L);
        screeningCache.clear();
        given(catalogClient.findScreening(SCREENING)).willReturn(new CatalogClient.Screening(
            SCREENING, 10_004L, 1, null, null, "승부", "건대입구", "1관"));

        assertThatThrownBy(() -> bookingCancelService.cancel(USER, bookingId))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.SERVICE_UNAVAILABLE.name());
        verify(paymentClient, never()).cancel(anyString(), any());
    }

    /**
     * 상영 전에 취소했지만 환불 호출이 실패한 예매 — 상영이 시작된 뒤 다시 요청해도 결제가 아직
     * APPROVED면 환불을 다시 시도한다(상영 시작 확인보다 멱등 경로가 먼저다)
     */
    @Test
    void refundIsRetriedForCanceledBookingEvenAfterScreeningStarted() {
        Long bookingId = confirmedBooking(101L);
        willThrow(new IllegalStateException("payment down")).given(paymentClient).cancel(anyString(), any());
        bookingCancelService.cancel(USER, bookingId);
        startScreening();
        String paymentId = bookingRepository.findById(bookingId).orElseThrow().getCurrentPaymentId();
        given(paymentClient.find(paymentId)).willReturn(java.util.Optional.of(new PaymentResult(
            paymentId, bookingId, 30_000, "APPROVED", "1112", null, null, Instant.now(), null)));
        willReturn(new PaymentResult(paymentId, bookingId, 30_000, "CANCELED", "1112", null,
            "BOOKING_CANCELED", Instant.now(), Instant.now())).given(paymentClient).cancel(anyString(), any());

        var response = bookingCancelService.cancel(USER, bookingId);

        assertThat(response.status()).isEqualTo(BookingStatus.CANCELED);
        assertThat(response.payment().status()).isEqualTo("CANCELED");
        verify(paymentClient, org.mockito.Mockito.times(2)).cancel(org.mockito.ArgumentMatchers.eq(paymentId), any());
    }

    /** 상영 전에 취소한 예매는 상영이 시작된 뒤 다시 요청해도 같은 결과(200)다 — 멱등 응답이 먼저다 */
    @Test
    void alreadyCanceledBookingStillAnswersAfterScreeningStarted() {
        Long bookingId = confirmedBooking(101L);
        given(paymentClient.cancel(anyString(), any())).willReturn(new PaymentResult(
            UUID.randomUUID().toString(), bookingId, 30_000, "CANCELED", "1112", null,
            "BOOKING_CANCELED", Instant.now(), Instant.now()));
        bookingCancelService.cancel(USER, bookingId);
        startScreening();

        assertThat(bookingCancelService.cancel(USER, bookingId).status()).isEqualTo(BookingStatus.CANCELED);
    }

    @Test
    void cancelIsIdempotent() {
        Long bookingId = hold(101L);
        given(paymentClient.request(any())).willReturn(approved(bookingId));
        bookingPaymentService.pay(USER, MOCK_KEY, bookingId, payRequest());
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

        assertThatThrownBy(() -> bookingPaymentService.pay(USER, MOCK_KEY, bookingId, payRequest()))
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
        bookingPaymentService.pay(USER, MOCK_KEY, bookingId, payRequest());

        var detail = bookingDetailService.get(USER, bookingId);

        assertThat(detail.status()).isEqualTo(BookingStatus.PAYMENT_FAILED);
        assertThat(detail.seatIds()).isEmpty();
    }

    /** 선점 → 승인 결제로 확정된 예매 */
    private Long confirmedBooking(Long seatId) {
        Long bookingId = hold(seatId);
        given(paymentClient.request(any())).willReturn(approved(bookingId));
        bookingPaymentService.pay(USER, MOCK_KEY, bookingId, payRequest());
        return bookingId;
    }

    // --- TOSSPAY 준비 (ADR-112) ---

    @Test
    void prepareAttachesPaymentIdAndReturnsCheckout() {
        Long bookingId = hold(101L, 102L);

        PaymentPrepareResponse response = prepare(bookingId);

        assertThat(response.bookingId()).isEqualTo(bookingId);
        assertThat(response.amount()).isEqualTo(30_000);
        assertThat(response.orderName()).isEqualTo("SeatSAC 영화 예매 2석");
        assertThat(response.expiresAt()).isNotNull();
        assertThat(response.checkout()).isEqualTo(new PaymentPrepareResponse.Checkout("PORTONE",
            "store-test", "channel-key-test", "EASY_PAY", "KRW"));

        Booking booking = bookingRepository.findById(bookingId).orElseThrow();
        assertThat(booking.getCurrentPaymentId()).isEqualTo(response.paymentId());
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(outboxMessageRepository.count()).isZero();

        var sent = org.mockito.ArgumentCaptor.forClass(PaymentClient.PaymentRequest.class);
        verify(paymentClient).prepare(sent.capture());
        assertThat(sent.getValue().method()).isEqualTo(PaymentMethod.TOSSPAY);
        assertThat(sent.getValue().card()).isNull();
        assertThat(sent.getValue().orderName()).isEqualTo(response.orderName());
        assertThat(sent.getValue().paymentId()).isEqualTo(response.paymentId());
        verify(paymentClient, never()).request(any());
    }

    /** 준비는 예매 단위로 멱등 — 새로고침·결제창을 닫은 뒤 다시 누르면 같은 결제로 결제창을 연다 */
    @Test
    void prepareAgainReturnsTheSamePaymentWhileCheckoutIsOpen() {
        Long bookingId = hold(101L);
        PaymentPrepareResponse first = prepare(bookingId);

        PaymentPrepareResponse again = prepare(bookingId);

        assertThat(again.paymentId()).isEqualTo(first.paymentId());
        assertThat(again.checkout()).isEqualTo(first.checkout());
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getCurrentPaymentId())
            .isEqualTo(first.paymentId());
    }

    /** 이미 끝난 결제(또는 결제창이 없는 모의 결제)가 붙어 있으면 다시 준비할 수 없다 */
    @Test
    void prepareAfterAFinishedPaymentIsRejected() {
        Long bookingId = hold(101L);
        String paymentId = prepare(bookingId).paymentId();
        willReturn(new PaymentResult(paymentId, bookingId, 30_000, "APPROVED", null, null, null, Instant.now(),
            null, "TOSSPAY", "PORTONE", "tx-1", null)).given(paymentClient).prepare(any());

        assertThatThrownBy(() -> bookingPaymentService.prepare(USER, bookingId,
            new PaymentPrepareRequest(PaymentMethod.TOSSPAY)))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.PAYMENT_ALREADY_REQUESTED.name());
    }

    @Test
    void prepareRejectsCardMethodAndClosedScreening() {
        Long bookingId = hold(101L);
        assertThatThrownBy(() -> bookingPaymentService.prepare(USER, bookingId,
            new PaymentPrepareRequest(PaymentMethod.CARD)))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(reasonOf(e)).isEqualTo("INVALID_PAYMENT_METHOD"));

        startScreeningIn(240);   // 마감 지남
        assertThatThrownBy(() -> prepare(bookingId))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.SCREENING_STARTED.name());
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getCurrentPaymentId()).isNull();
        verify(paymentClient, never()).prepare(any());
    }

    /** payment에 닿지 못해도 paymentId는 붙은 채다 — 만료 처리가 유예 뒤 정리한다(HoldExpiryFlowTest) */
    @Test
    void preparePaymentUnavailableLeavesPaymentIdAttached() {
        Long bookingId = hold(101L);
        given(paymentClient.prepare(any())).willThrow(
            com.seatsac.booking.common.BookingErrorCode.PAYMENT_UNAVAILABLE.exception());

        assertThatThrownBy(() -> bookingPaymentService.prepare(USER, bookingId,
            new PaymentPrepareRequest(PaymentMethod.TOSSPAY)))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.SERVICE_UNAVAILABLE.name());

        Booking booking = bookingRepository.findById(bookingId).orElseThrow();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(booking.getCurrentPaymentId()).isNotNull();

        // payment가 돌아오면 다시 눌러 같은 paymentId로 준비가 된다(409로 막히지 않는다)
        assertThat(prepare(bookingId).paymentId()).isEqualTo(booking.getCurrentPaymentId());
    }

    /** 완료 확인이 503(결제창 직후 포트원 반영 전)이어도, 화면의 단건 조회가 포트원 확인을 다시 해 결과가 나온다 */
    @Test
    void detailVerifiesTossPayStillProcessingAndConfirms() {
        Long bookingId = hold(101L);
        String paymentId = prepare(bookingId).paymentId();
        given(paymentClient.find(paymentId)).willReturn(java.util.Optional.of(preparedFor(bookingId, paymentId)));
        given(paymentClient.verify(paymentId)).willReturn(tossPayApproved(bookingId, paymentId));

        var detail = bookingDetailService.get(USER, bookingId);

        assertThat(detail.status()).isEqualTo(BookingStatus.CONFIRMED);
        verify(paymentClient).verify(paymentId);
        assertThat(outboxMessageRepository.count()).isEqualTo(1);
    }

    /** 포트원 확인이 아직이면(결제창 미완료·응답 없음) 예매는 PENDING 그대로 200 */
    @Test
    void detailKeepsPendingWhenTossPayVerifyHasNoResultYet() {
        Long bookingId = hold(101L);
        String paymentId = prepare(bookingId).paymentId();
        given(paymentClient.find(paymentId)).willReturn(java.util.Optional.of(preparedFor(bookingId, paymentId)));
        given(paymentClient.verify(paymentId)).willThrow(
            com.seatsac.booking.common.BookingErrorCode.PAYMENT_RESULT_UNKNOWN.exception());

        var detail = bookingDetailService.get(USER, bookingId);

        assertThat(detail.status()).isEqualTo(BookingStatus.PENDING);
        assertThat(detail.payment().status()).isEqualTo("PROCESSING");
    }

    // --- 모의 결제(CARD) 호출자 제한 ---

    @Test
    void mockCardNeedsKey() {
        Long bookingId = hold(101L);
        assertThatThrownBy(() -> bookingPaymentService.pay(USER, "wrong-key", bookingId, payRequest()))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                assertThat(((BusinessException) e).code().responseCode())
                    .isEqualTo(CommonResponseCode.VALIDATION_FAILED.name());
                assertThat(reasonOf(e)).isEqualTo("MOCK_NOT_ALLOWED");
            });
        assertThatThrownBy(() -> bookingPaymentService.pay(USER, null, bookingId, payRequest()))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(reasonOf(e)).isEqualTo("MOCK_NOT_ALLOWED"));
        verify(paymentClient, never()).request(any());
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getCurrentPaymentId()).isNull();

        // 맞는 키면 결제한다 (k6·분석 Job)
        given(paymentClient.request(any())).willReturn(approved(bookingId));
        assertThat(bookingPaymentService.pay(USER, MOCK_KEY, bookingId, payRequest()).status())
            .isEqualTo(BookingStatus.CONFIRMED);
    }

    // --- TOSSPAY 완료 확인 ---

    @Test
    void tossPayCompletionConfirmsWhenVerifyApproves() {
        Long bookingId = hold(101L, 102L);
        String paymentId = prepare(bookingId).paymentId();
        given(paymentClient.verify(paymentId)).willReturn(tossPayApproved(bookingId, paymentId));

        BookingPaymentResponse response = bookingPaymentService.pay(USER, null, bookingId,
            tossPayRequest(paymentId));

        assertThat(response.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(response.payment().method()).isEqualTo("TOSSPAY");
        assertThat(response.payment().cardLast4()).isNull();
        assertThat(response.payment().paymentId()).isEqualTo(paymentId);
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
            .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(bookingSeatRepository.findSeatIdsByBookingId(bookingId)).hasSize(2);
        assertThat(outboxMessageRepository.count()).isEqualTo(1);
        assertThat(redis.hasKey(RedisKeys.seat(SCREENING, 101L))).isFalse();
    }

    /** 결제창이 아직 끝나지 않았다 — 503, 예매는 PENDING 그대로(화면이 단건 조회로 다시 본다) */
    @Test
    void tossPayCompletionStillProcessingIsUnknown() {
        Long bookingId = hold(101L);
        String paymentId = prepare(bookingId).paymentId();
        given(paymentClient.verify(paymentId)).willThrow(
            com.seatsac.booking.common.BookingErrorCode.PAYMENT_RESULT_UNKNOWN.exception());

        assertThatThrownBy(() -> bookingPaymentService.pay(USER, null, bookingId,
            tossPayRequest(paymentId)))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.PAYMENT_RESULT_UNKNOWN.name());
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
            .isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void tossPayCompletionDeclinedReleasesSeats() {
        Long bookingId = hold(101L);
        String paymentId = prepare(bookingId).paymentId();
        given(paymentClient.verify(paymentId))
            .willReturn(tossPayFailed(bookingId, paymentId, "USER_CANCELED"));

        BookingPaymentResponse response = bookingPaymentService.pay(USER, null, bookingId,
            tossPayRequest(paymentId));

        assertThat(response.status()).isEqualTo(BookingStatus.PAYMENT_FAILED);
        assertThat(response.payment().failureCode()).isEqualTo("USER_CANCELED");
        assertThat(bookingSeatRepository.findSeatIdsByBookingId(bookingId)).isEmpty();
        assertThat(redis.hasKey(RedisKeys.seat(SCREENING, 101L))).isFalse();
    }

    @Test
    void tossPayCompletionWithWrongPaymentIdIsConflict() {
        Long bookingId = hold(101L);
        prepare(bookingId);
        assertThatThrownBy(() -> bookingPaymentService.pay(USER, null, bookingId,
            tossPayRequest(UUID.randomUUID().toString())))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                assertThat(((BusinessException) e).code().responseCode())
                    .isEqualTo(CommonResponseCode.PAYMENT_ALREADY_REQUESTED.name());
                assertThat(reasonOf(e)).isEqualTo("PAYMENT_ID_MISMATCH");
            });

        // 준비하지 않은 예매
        Long notPrepared = holdAs(OTHER, 105L);
        assertThatThrownBy(() -> bookingPaymentService.pay(OTHER, null, notPrepared,
            tossPayRequest(UUID.randomUUID().toString())))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(reasonOf(e)).isEqualTo("PAYMENT_ID_MISMATCH"));
        verify(paymentClient, never()).verify(anyString());
    }

    /** 결제창에서 시간을 넘겼다 — 승인됐어도 환불하고 409(refunded=true) */
    @Test
    void tossPayCompletionAfterExpiryRefunds() {
        Long bookingId = hold(101L);
        String paymentId = prepare(bookingId).paymentId();
        expire(bookingId);
        given(paymentClient.verify(paymentId)).willReturn(tossPayApproved(bookingId, paymentId));
        given(paymentClient.cancel(anyString(), any())).willReturn(canceledFor(bookingId));

        assertThatThrownBy(() -> bookingPaymentService.pay(USER, null, bookingId,
            tossPayRequest(paymentId)))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException ex = (BusinessException) e;
                assertThat(ex.code().responseCode()).isEqualTo(CommonResponseCode.BOOKING_STATE_CONFLICT.name());
                assertThat(ex.details()).anySatisfy(d -> {
                    assertThat(d.field()).isEqualTo("refunded");
                    assertThat(d.value()).isEqualTo(true);
                });
            });
        verify(paymentClient).cancel(paymentId, PaymentCancelReason.HOLD_EXPIRED);
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
            .isEqualTo(BookingStatus.EXPIRED);
        assertThat(outboxMessageRepository.count()).isZero();
    }

    /** 마감 검사는 준비에서만 한다 — 결제창에서 이미 돈을 냈을 수 있다 */
    @Test
    void tossPayCompletionAfterCutoffIsStillAccepted() {
        Long bookingId = hold(101L);
        Long another = holdAs(OTHER, 105L);
        String paymentId = prepare(bookingId).paymentId();
        startScreeningIn(180);   // 3분 뒤 시작 = 마감 지남
        given(paymentClient.verify(paymentId)).willReturn(tossPayApproved(bookingId, paymentId));

        assertThat(bookingPaymentService.pay(USER, null, bookingId, tossPayRequest(paymentId))
            .status()).isEqualTo(BookingStatus.CONFIRMED);

        // 대조군: 같은 조건에서 준비는 막힌다
        assertThatThrownBy(() -> bookingPaymentService.prepare(OTHER, another,
            new PaymentPrepareRequest(PaymentMethod.TOSSPAY)))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.SCREENING_STARTED.name());
    }

    /** 폴링(단건 조회)이 먼저 확정했으면 다시 확인하지 않고 확정 결과를 돌려준다 */
    @Test
    void tossPayCompletionWhenAlreadyConfirmedByPollingReturnsConfirmed() {
        Long bookingId = hold(101L);
        String paymentId = prepare(bookingId).paymentId();
        given(paymentClient.find(paymentId))
            .willReturn(java.util.Optional.of(tossPayApproved(bookingId, paymentId)));
        assertThat(bookingDetailService.get(USER, bookingId).status()).isEqualTo(BookingStatus.CONFIRMED);

        BookingPaymentResponse response = bookingPaymentService.pay(USER, null, bookingId,
            tossPayRequest(paymentId));

        assertThat(response.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(response.confirmedAt()).isNotNull();
        assertThat(response.payment().method()).isEqualTo("TOSSPAY");
        verify(paymentClient, never()).verify(anyString());
        assertThat(outboxMessageRepository.count()).isEqualTo(1);
    }

    /** 회차가 이미 시작된 것으로 바꾼다 (파드 캐시도 비운다) */
    private void startScreening() {
        screeningCache.clear();
        Instant now = Instant.now();
        given(catalogClient.findScreening(SCREENING)).willReturn(new CatalogClient.Screening(
            SCREENING, 10_004L, 1, now.minusSeconds(60), now.plusSeconds(6000), "승부", "건대입구", "1관"));
    }

    /** 회차 시작 시각을 지금부터 {@code seconds}초 뒤로 바꾼다 (파드 캐시도 비운다) */
    private void startScreeningIn(long seconds) {
        screeningCache.clear();
        Instant now = Instant.now();
        given(catalogClient.findScreening(SCREENING)).willReturn(new CatalogClient.Screening(
            SCREENING, 10_004L, 1, now.plusSeconds(seconds), now.plusSeconds(seconds + 6000),
            "승부", "건대입구", "1관"));
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

package com.seatsac.booking.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.booking.checkout.dto.CheckoutAcceptedResponse;
import com.seatsac.booking.checkout.dto.CheckoutRequest;
import com.seatsac.booking.checkout.dto.CheckoutResponse;
import com.seatsac.booking.component.CatalogClient;
import com.seatsac.booking.component.CatalogClient.AuditoriumLayout;
import com.seatsac.booking.component.CatalogClient.AuditoriumLayout.LayoutSeat;
import com.seatsac.booking.component.PaymentClient;
import com.seatsac.booking.component.ScreeningCache;
import com.seatsac.booking.dto.request.BookingPayRequest;
import com.seatsac.booking.dto.request.SeatHoldRequest;
import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.entity.OutboxMessage;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.booking.enums.OutboxDestination;
import com.seatsac.booking.enums.PaymentMethod;
import com.seatsac.booking.repository.BookingRepository;
import com.seatsac.booking.repository.BookingSeatRepository;
import com.seatsac.booking.repository.OutboxMessageRepository;
import com.seatsac.booking.service.BookingDetailService;
import com.seatsac.booking.service.BookingPaymentService;
import com.seatsac.booking.service.HoldExpiryService;
import com.seatsac.booking.service.SeatHoldService;
import com.seatsac.booking.service.SeatReleaseService;
import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.test.MySqlContainerSupport;
import com.seatsac.common.test.ValkeyContainerSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 6.2.1·6.2.2 체크아웃 saga — 시작 → 명령(outbox) → 결과(핸들러에 직접) → 예매 반영, 스윕(시간 초과·재확인·만료),
 * 동기 경로와의 경계(D26). MySQL·Valkey는 실제로 띄우고 Catalog는 가짜, <b>payment HTTP는 한 번도 부르지 않아야</b> 한다.
 * 명령은 계약 파일({@code contracts/events/payment.*-requested.v1.json})의 필수 필드를 함께 본다.
 */
@SpringBootTest(properties = "app.checkout.payment-retry-enabled=true")
@ActiveProfiles("test")
@Import({MySqlContainerSupport.class, ValkeyContainerSupport.class})
class CheckoutSagaFlowTest {

    private static final String USER = "11111111-1111-4111-8111-111111111111";
    private static final Long SCREENING = 42L;
    private static final String MOCK_KEY = "test-mock-key";
    private static final Path CONTRACTS = Path.of("../../contracts/events");

    @MockitoBean
    CatalogClient catalogClient;
    @MockitoBean
    PaymentClient paymentClient;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    CheckoutResultTx resultTx;

    @Autowired
    ScreeningCache screeningCache;
    @Autowired
    SeatHoldService seatHoldService;
    @Autowired
    CheckoutSagaService checkoutService;
    @Autowired
    CheckoutReplyHandler replies;
    @Autowired
    CheckoutSweeper sweeper;
    @Autowired
    HoldExpiryService holdExpiryService;
    @Autowired
    BookingPaymentService bookingPaymentService;
    @Autowired
    SeatReleaseService seatReleaseService;
    @Autowired
    BookingDetailService bookingDetailService;
    @Autowired
    BookingRepository bookings;
    @Autowired
    BookingSeatRepository bookingSeats;
    @Autowired
    OutboxMessageRepository outbox;
    @Autowired
    CheckoutSagaRepository sagas;
    @Autowired
    ProcessedMessageRepository inbox;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        inbox.deleteAllInBatch();
        sagas.deleteAllInBatch();
        outbox.deleteAllInBatch();
        bookingSeats.deleteAllInBatch();
        bookings.deleteAllInBatch();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        screeningCache.clear();
        Instant now = Instant.now();
        given(catalogClient.findScreening(SCREENING)).willReturn(new CatalogClient.Screening(
            SCREENING, 10_004L, 1, now.plusSeconds(3600), now.plusSeconds(10_000), "승부", "건대입구", "1관"));
        given(catalogClient.findLayout(10_004L, 1)).willReturn(new AuditoriumLayout(10_004L, 1,
            IntStream.rangeClosed(1, 20).mapToObj(i -> new LayoutSeat(100L + i, "J" + i, true)).toList()));
    }

    // --- 모의 카드: 시작 → 청구 명령 → 승인 → 확정 ---

    @Test
    void cardCheckoutSendsChargeCommandAndConfirmsOnApproved() throws IOException {
        Long bookingId = hold();

        CheckoutAcceptedResponse accepted = start(bookingId, card(), null);

        assertThat(accepted.state()).isEqualTo(CheckoutState.CHARGING);
        assertThat(accepted.pollAfterMs()).isEqualTo(1000L);
        Booking booking = bookings.findById(bookingId).orElseThrow();
        assertThat(booking.getCurrentPaymentId()).isNotNull();   // 결제 명령을 처음 보낼 때 붙인다(D14)
        JsonNode charge = command("payment.charge-requested");
        assertThat(charge.at("/payload/cardLast4").asText()).isEqualTo("1112");
        assertThat(charge.at("/payload/amount").asInt()).isEqualTo(30_000);
        assertThat(charge.toString()).doesNotContain("4111111111111112");   // 번호 전체는 큐에 넣지 않는다
        assertThat(charge.get("correlationId").asText()).isEqualTo(accepted.checkoutId());
        assertMatchesContract(charge);

        replies.handle(reply(accepted.checkoutId(), "payment.approved", booking.getCurrentPaymentId(), bookingId,
            "\"amount\":30000,\"approvedAt\":\"2026-11-24T14:00:13+09:00\",\"pgTransactionId\":null"));

        assertThat(bookings.findById(bookingId)).get().extracting(Booking::getStatus).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(saga(accepted).getState()).isEqualTo(CheckoutState.CONFIRMED);
        assertThat(outbox.findAll()).anyMatch(m -> m.getDestination() == OutboxDestination.NOTIFICATION);
        // 6.2.4 — 보낼 곳(SNS 토픽)이 설정되지 않으면 예매 이벤트 행을 만들지 않는다
        assertThat(outbox.findAll()).noneMatch(m -> m.getDestination() == OutboxDestination.BOOKING_EVENTS);
        verifyNoInteractions(paymentClient);
    }

    @Test
    void sameReplyTwiceIsAppliedOnce() {
        Long bookingId = hold();
        CheckoutAcceptedResponse accepted = start(bookingId, card(), null);
        String paymentId = bookings.findById(bookingId).orElseThrow().getCurrentPaymentId();
        String approved = reply(accepted.checkoutId(), "payment.approved", paymentId, bookingId,
            "\"amount\":30000,\"approvedAt\":\"2026-11-24T14:00:13+09:00\",\"pgTransactionId\":null");

        replies.handle(approved);
        replies.handle(approved);

        assertThat(outbox.findAll().stream().filter(m -> m.getDestination() == OutboxDestination.NOTIFICATION)).hasSize(1);
        assertThat(inbox.count()).isEqualTo(1);
    }

    @Test
    void declinedReleasesSeatsAndFailsTheSaga() {
        Long bookingId = hold();
        CheckoutAcceptedResponse accepted = start(bookingId, card(), null);
        // 청구 중(CHARGING) 거절 — payment는 청구 실패도 declined로 낸다(02, #182 리뷰)
        assertThat(accepted.state()).isEqualTo(CheckoutState.CHARGING);
        String paymentId = bookings.findById(bookingId).orElseThrow().getCurrentPaymentId();

        replies.handle(reply(accepted.checkoutId(), "payment.declined", paymentId, bookingId,
            "\"failureCode\":\"CARD_DECLINED\",\"retryable\":false"));

        assertThat(bookings.findById(bookingId)).get().extracting(Booking::getStatus)
            .isEqualTo(BookingStatus.PAYMENT_FAILED);
        assertThat(bookingSeats.count()).isZero();
        CheckoutSaga saga = saga(accepted);
        assertThat(saga.getState()).isEqualTo(CheckoutState.FAILED);
        assertThat(saga.getFailureCode()).isEqualTo("PAYMENT_DECLINED");
        assertThat(saga.getFailureReason()).isEqualTo("CARD_DECLINED");
    }

    // --- 토스페이: 준비 → 결제창 → 확인 → 결과 불명 → 재확인 → 승인 ---

    @Test
    void tossPayGoesReadyToPayThenVerifyRetryThenConfirms() throws IOException {
        Long bookingId = hold();
        CheckoutAcceptedResponse accepted = start(bookingId, tossPay(), null);
        assertThat(accepted.state()).isEqualTo(CheckoutState.PAYMENT_PREPARING);
        JsonNode prepare = command("payment.preparation-requested");
        assertMatchesContract(prepare);
        String paymentId = prepare.at("/payload/paymentId").asText();

        replies.handle(reply(accepted.checkoutId(), "payment.prepared", paymentId, bookingId,
            "\"checkout\":{\"provider\":\"PORTONE\",\"storeId\":\"store-test\",\"channelKey\":\"ck\","
                + "\"payMethod\":\"EASY_PAY\",\"currency\":\"KRW\"}"));
        CheckoutResponse ready = checkoutService.get(USER, bookingId);
        assertThat(ready.state()).isEqualTo(CheckoutState.READY_TO_PAY);
        assertThat(ready.payment().portone().storeId()).isEqualTo("store-test");
        assertThat(ready.payment().portone().totalAmount()).isEqualTo(30_000);
        assertThat(ready.payment().portone().paymentId()).isEqualTo(paymentId);

        assertThat(checkoutService.confirm(USER, bookingId, paymentId).state()).isEqualTo(CheckoutState.VERIFYING);
        assertMatchesContract(command("payment.verification-requested"));

        replies.handle(reply(accepted.checkoutId(), "payment.result-unknown", paymentId, bookingId,
            "\"reason\":\"NOT_YET\",\"attempt\":1"));
        assertThat(saga(accepted).getLastUnknown()).isEqualTo("NOT_YET");

        pastDeadline(accepted.checkoutId());
        assertThat(sweeper.sweepOnce()).isEqualTo(1);
        assertThat(commands("payment.verification-requested")).hasSize(2);
        assertThat(saga(accepted).getVerifyAttempt()).isEqualTo(2);

        replies.handle(reply(accepted.checkoutId(), "payment.approved", paymentId, bookingId,
            "\"amount\":30000,\"approvedAt\":\"2026-11-24T14:00:13+09:00\",\"pgTransactionId\":\"tx-1\""));
        assertThat(saga(accepted).getState()).isEqualTo(CheckoutState.CONFIRMED);
        verifyNoInteractions(paymentClient);
    }

    @Test
    void confirmTwiceIsNotReady() {
        Long bookingId = hold();
        CheckoutAcceptedResponse accepted = start(bookingId, tossPay(), null);
        String paymentId = command("payment.preparation-requested").at("/payload/paymentId").asText();
        replies.handle(reply(accepted.checkoutId(), "payment.prepared", paymentId, bookingId,
            "\"checkout\":{\"provider\":\"PORTONE\",\"storeId\":\"s\",\"channelKey\":\"c\",\"payMethod\":\"EASY_PAY\",\"currency\":\"KRW\"}"));
        checkoutService.confirm(USER, bookingId, paymentId);

        assertThatThrownBy(() -> checkoutService.confirm(USER, bookingId, paymentId))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode()).isEqualTo("CHECKOUT_NOT_READY");
        assertThatThrownBy(() -> checkoutService.confirm(USER, bookingId, UUID.randomUUID().toString()))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode()).isEqualTo("VALIDATION_FAILED");
    }

    // --- 스윕: 돈 전 단계 시간 초과 · 선점 만료 ---

    @Test
    void preparationTimeoutCompensatesAndSendsRelease() {
        Long bookingId = hold();
        CheckoutAcceptedResponse accepted = start(bookingId, tossPay(), null);
        pastDeadline(accepted.checkoutId());

        sweeper.sweepOnce();

        CheckoutSaga saga = saga(accepted);
        assertThat(saga.getState()).isEqualTo(CheckoutState.FAILED);
        assertThat(saga.getFailureCode()).isEqualTo("STEP_TIMEOUT");
        assertThat(bookings.findById(bookingId)).get().extracting(Booking::getStatus)
            .isEqualTo(BookingStatus.PAYMENT_FAILED);
        assertThat(commands("payment.release-requested")).hasSize(1);   // 늦게 만든 결제가 있으면 payment가 닫는다
    }

    /** D15·D23 — 선점 5분에 바로 만료(유예·payment 조회 없음) → 스윕이 saga를 닫고 해제 명령 → 늦은 승인은 해제를 한 번 더 */
    @Test
    void holdExpiryClosesSagaBookingImmediatelyAndLateApprovalResendsRelease() throws IOException {
        Long bookingId = hold();
        CheckoutAcceptedResponse accepted = start(bookingId, card(), null);
        String paymentId = bookings.findById(bookingId).orElseThrow().getCurrentPaymentId();
        // 시각은 UTC로 저장한다(JVM 시간대로 넣으면 9시간 밀린다)
        jdbc.update("update booking set expires_at = utc_timestamp(3) - interval 1 second where booking_id = ?",
            bookingId);

        assertThat(holdExpiryService.expire(bookingId)).isEqualTo(HoldExpiryService.Outcome.EXPIRED);
        sweeper.sweepOnce();

        CheckoutSaga saga = saga(accepted);
        assertThat(saga.getState()).isEqualTo(CheckoutState.EXPIRED);
        assertThat(saga.getRefundNotice()).isEqualTo("WILL_REFUND_IF_PAID");
        JsonNode release = command("payment.release-requested");
        assertThat(release.at("/payload/reason").asText()).isEqualTo("HOLD_EXPIRED");
        assertMatchesContract(release);

        replies.handle(reply(accepted.checkoutId(), "payment.approved", paymentId, bookingId,
            "\"amount\":30000,\"approvedAt\":\"2026-11-24T14:00:13+09:00\",\"pgTransactionId\":null"));

        assertThat(bookings.findById(bookingId)).get().extracting(Booking::getStatus).isEqualTo(BookingStatus.EXPIRED);
        assertThat(saga(accepted).getRefundNotice()).isEqualTo("REFUND_IN_PROGRESS");
        assertThat(commands("payment.release-requested")).hasSize(2);
        verifyNoInteractions(paymentClient);   // 동기 취소를 부르지 않는다(D16)
    }

    /** 06 5장 — 끝난 saga(실패)에 온 승인은 확정에 쓰지 않고 해제 명령으로 환불한다 */
    @Test
    void approvalOnAFailedSagaSendsReleaseInsteadOfIgnoring() {
        Long bookingId = hold();
        CheckoutAcceptedResponse accepted = start(bookingId, card(), null);
        String paymentId = bookings.findById(bookingId).orElseThrow().getCurrentPaymentId();
        replies.handle(reply(accepted.checkoutId(), "payment.declined", paymentId, bookingId,
            "\"failureCode\":\"CARD_DECLINED\",\"retryable\":false"));

        replies.handle(reply(accepted.checkoutId(), "payment.approved", paymentId, bookingId,
            "\"amount\":30000,\"approvedAt\":\"2026-11-24T14:00:13+09:00\",\"pgTransactionId\":null"));

        assertThat(bookings.findById(bookingId)).get().extracting(Booking::getStatus)
            .isEqualTo(BookingStatus.PAYMENT_FAILED);
        JsonNode release = command("payment.release-requested");
        assertThat(release.at("/payload/paymentId").asText()).isEqualTo(paymentId);
    }

    /** 확정한 결제의 승인이 한 번 더 왔다(확인 명령 두 개가 둘 다 답함) — 환불하지 않는다 */
    @Test
    void duplicateApprovalOfTheConfirmingPaymentDoesNotRelease() {
        Long bookingId = hold();
        CheckoutAcceptedResponse accepted = start(bookingId, card(), null);
        String paymentId = bookings.findById(bookingId).orElseThrow().getCurrentPaymentId();
        String fields = "\"amount\":30000,\"approvedAt\":\"2026-11-24T14:00:13+09:00\",\"pgTransactionId\":null";
        replies.handle(reply(accepted.checkoutId(), "payment.approved", paymentId, bookingId, fields));

        replies.handle(reply(accepted.checkoutId(), "payment.approved", paymentId, bookingId, fields));

        assertThat(saga(accepted).getState()).isEqualTo(CheckoutState.CONFIRMED);
        assertThat(commands("payment.release-requested")).isEmpty();
    }

    /** 만료 뒤 서로 다른 늦은 승인이 두 번 — 각자 해제를 한 번씩(유일 키에 걸리지 않는다) */
    @Test
    void twoDifferentLateApprovalsEachResendRelease() {
        Long bookingId = hold();
        CheckoutAcceptedResponse accepted = start(bookingId, card(), null);
        String paymentId = bookings.findById(bookingId).orElseThrow().getCurrentPaymentId();
        jdbc.update("update booking set expires_at = utc_timestamp(3) - interval 1 second where booking_id = ?",
            bookingId);
        holdExpiryService.expire(bookingId);
        sweeper.sweepOnce();
        String fields = "\"amount\":30000,\"approvedAt\":\"2026-11-24T14:00:13+09:00\",\"pgTransactionId\":null";

        replies.handle(reply(accepted.checkoutId(), "payment.approved", paymentId, bookingId, fields));
        replies.handle(reply(accepted.checkoutId(), "payment.approved", paymentId, bookingId, fields));

        assertThat(commands("payment.release-requested")).hasSize(3);   // 만료 1 + 늦은 승인 2
    }

    /** 형식이 틀린 결과는 전용 예외 — 리스너가 지운다(04 5장). inbox·saga는 건드리지 않는다 */
    @Test
    void malformedReplyIsRejectedWithItsOwnException() {
        assertThatThrownBy(() -> replies.handle("not json")).isInstanceOf(MalformedReplyException.class);
        assertThatThrownBy(() -> replies.handle("{\"type\":\"payment.approved\"}"))
            .isInstanceOf(MalformedReplyException.class);
        assertThat(inbox.count()).isZero();
    }

    /** 유일 키 충돌인데 inbox에 이 결과가 없다 — 지우지 않고 재배달시킨다 */
    @Test
    void uniqueKeyClashOutsideTheInboxIsRedelivered() {
        Long bookingId = hold();
        CheckoutAcceptedResponse accepted = start(bookingId, card(), null);
        String paymentId = bookings.findById(bookingId).orElseThrow().getCurrentPaymentId();
        String unknown = reply(accepted.checkoutId(), "payment.result-unknown", paymentId, bookingId,
            "\"reason\":\"NOT_YET\",\"attempt\":1");
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("outbox UK"))
            .when(resultTx).noteUnknown(any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> replies.handle(unknown))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(inbox.count()).isZero();

        org.mockito.Mockito.reset(resultTx);
        replies.handle(unknown);   // 재배달 — 이번에는 처리된다
        assertThat(inbox.count()).isEqualTo(1);
    }

    // --- 쿠폰(none 어댑터) · 재시도 ---

    @Test
    void couponWithNoneAdapterIsRejectedKeepingSeatsAndCanStartAgain() {
        Long bookingId = hold();

        CheckoutAcceptedResponse rejected = start(bookingId, card(), "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d");

        assertThat(rejected.state()).isEqualTo(CheckoutState.COUPON_REJECTED);
        assertThat(rejected.pollAfterMs()).isEqualTo(1000L);   // 계약 CheckoutAccepted는 필수 정수
        Booking booking = bookings.findById(bookingId).orElseThrow();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(booking.getCurrentPaymentId()).isNull();   // 쿠폰 단계에서는 붙이지 않는다(D14)
        CheckoutResponse view = checkoutService.get(USER, bookingId);
        assertThat(view.failure().code()).isEqualTo("COUPON_NOT_USABLE");
        assertThat(view.retryable()).isTrue();

        CheckoutAcceptedResponse again = start(bookingId, card(), null);
        assertThat(again.state()).isEqualTo(CheckoutState.CHARGING);
        assertThat(saga(again).getAttempt()).isEqualTo(2);
    }

    /** D25(플래그 켬) — 포트원 FAILED(돈 안 빠짐)면 좌석을 두고 같은 예매로 새 결제 ID를 붙여 다시 결제 */
    @Test
    void retryableDeclineKeepsSeatsAndNextCheckoutReplacesPaymentId() {
        Long bookingId = hold();
        CheckoutAcceptedResponse first = start(bookingId, tossPay(), null);
        String firstPayment = bookings.findById(bookingId).orElseThrow().getCurrentPaymentId();

        replies.handle(reply(first.checkoutId(), "payment.declined", firstPayment, bookingId,
            "\"failureCode\":\"USER_CANCELED\",\"retryable\":true"));

        // 준비 중(PAYMENT_PREPARING)에 온 거절은 늦은·엉뚱한 결과로 보고 무시한다 — 재결제는 결제창 뒤에서만
        assertThat(saga(first).getState()).isEqualTo(CheckoutState.PAYMENT_PREPARING);

        replies.handle(reply(first.checkoutId(), "payment.prepared", firstPayment, bookingId,
            "\"checkout\":{\"provider\":\"PORTONE\",\"storeId\":\"s\",\"channelKey\":\"c\",\"payMethod\":\"EASY_PAY\",\"currency\":\"KRW\"}"));
        replies.handle(reply(first.checkoutId(), "payment.declined", firstPayment, bookingId,
            "\"failureCode\":\"USER_CANCELED\",\"retryable\":true"));
        assertThat(saga(first).getState()).isEqualTo(CheckoutState.PAYMENT_RETRYABLE);
        assertThat(bookings.findById(bookingId)).get().extracting(Booking::getStatus).isEqualTo(BookingStatus.PENDING);
        assertThat(bookingSeats.count()).isEqualTo(2);

        start(bookingId, tossPay(), null);
        String secondPayment = bookings.findById(bookingId).orElseThrow().getCurrentPaymentId();
        assertThat(secondPayment).isNotEqualTo(firstPayment);
    }

    // --- 동기 경로와의 경계 (D26) ---

    @Test
    void syncPaymentReleaseAndDetailRespectTheSagaPath() {
        Long bookingId = hold();
        CheckoutAcceptedResponse accepted = start(bookingId, card(), null);

        assertThatThrownBy(() -> bookingPaymentService.pay(USER, MOCK_KEY, bookingId,
            new BookingPayRequest(PaymentMethod.CARD, null, card())))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode()).isEqualTo("PAYMENT_PATH_CONFLICT");
        assertThatThrownBy(() -> seatReleaseService.release(USER, bookingId))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode()).isEqualTo("CHECKOUT_IN_PROGRESS");
        assertThatThrownBy(() -> start(bookingId, card(), null))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode()).isEqualTo("CHECKOUT_IN_PROGRESS");

        var detail = bookingDetailService.get(USER, bookingId);
        assertThat(detail.checkout().checkoutId()).isEqualTo(accepted.checkoutId());
        assertThat(detail.checkout().state()).isEqualTo("CHARGING");
        assertThat(detail.payment()).isNull();
        verifyNoInteractions(paymentClient);
    }

    @Test
    void syncPathBookingCannotStartCheckout() {
        Long bookingId = hold();
        given(paymentClient.request(any())).willThrow(new IllegalStateException("결과를 모른다"));
        try {
            bookingPaymentService.pay(USER, MOCK_KEY, bookingId, new BookingPayRequest(PaymentMethod.CARD, null, card()));
        } catch (RuntimeException ignored) {
            // 결제 ID는 이미 붙었다
        }

        assertThatThrownBy(() -> start(bookingId, card(), null))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode()).isEqualTo("PAYMENT_PATH_CONFLICT");
    }

    // --- 도우미 ---

    private Long hold() {
        return seatHoldService.hold(USER, new SeatHoldRequest(SCREENING, List.of(101L, 102L))).bookingId();
    }

    private CheckoutAcceptedResponse start(Long bookingId, BookingPayRequest.Card card, String couponIssueId) {
        PaymentMethod method = card == null ? PaymentMethod.TOSSPAY : PaymentMethod.CARD;
        return checkoutService.start(USER, MOCK_KEY, bookingId, new CheckoutRequest(method, couponIssueId, card));
    }

    private static BookingPayRequest.Card card() {
        return new BookingPayRequest.Card("4111111111111112", "12/28", "KIM");
    }

    private static BookingPayRequest.Card tossPay() {
        return null;
    }

    private CheckoutSaga saga(CheckoutAcceptedResponse accepted) {
        return sagas.findById(accepted.checkoutId()).orElseThrow();
    }

    private void pastDeadline(String checkoutId) {
        jdbc.update("update checkout_saga set step_deadline_at = utc_timestamp(3) - interval 1 second "
            + "where checkout_id = ?", checkoutId);
    }

    private List<JsonNode> commands(String type) {
        return outbox.findAll().stream()
            .filter(m -> m.getDestination() == OutboxDestination.PAYMENT_COMMANDS)
            .filter(m -> type.equals(m.getEventType().messageType()))
            .map(OutboxMessage::getPayload)
            .map(this::json)
            .toList();
    }

    private JsonNode command(String type) {
        List<JsonNode> found = commands(type);
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private String reply(String checkoutId, String type, String paymentId, Long bookingId, String fields) {
        return """
            {"eventId":"%s","type":"%s","version":1,"occurredAt":"2026-11-24T14:00:13+09:00","producer":"payment",
             "traceId":"4bf92f3577b34da6a3ce929d0e0e4736","correlationId":"%s",
             "payload":{"checkoutId":"%s","paymentId":"%s","bookingId":%d,%s}}
            """.formatted(UUID.randomUUID(), type, checkoutId, checkoutId, paymentId, bookingId, fields);
    }

    private JsonNode json(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertMatchesContract(JsonNode message) throws IOException {
        JsonNode schema = objectMapper.readTree(Files.readString(CONTRACTS.resolve(message.get("type").asText() + ".v1.json")));
        schema.get("required").forEach(f -> assertThat(message.has(f.asText())).as(f.asText()).isTrue());
        schema.at("/properties/payload/required").forEach(f ->
            assertThat(message.get("payload").has(f.asText())).as("payload." + f.asText()).isTrue());
        assertThat(message.get("producer").asText()).isEqualTo("booking");
    }
}

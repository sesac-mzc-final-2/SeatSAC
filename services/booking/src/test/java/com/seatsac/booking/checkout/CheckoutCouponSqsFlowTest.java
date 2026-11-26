package com.seatsac.booking.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.booking.checkout.dto.CheckoutAcceptedResponse;
import com.seatsac.booking.checkout.dto.CheckoutRequest;
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
import com.seatsac.booking.service.SeatHoldService;
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
 * 쿠폰 어댑터 {@code sqs}(설계 03 3-2) — 명령 3종이 {@code coupon-commands} 행으로, 결과 2종({@code coupon.reserved}·
 * {@code coupon.reservation-rejected})이 saga를 움직인다. 명령은 계약 파일({@code contracts/events/coupon.*.v1.json}, #187)의
 * 필수 필드를 함께 본다. payment HTTP는 한 번도 부르지 않는다.
 */
@SpringBootTest(properties = "app.checkout.coupon-adapter=sqs")
@ActiveProfiles("test")
@Import({MySqlContainerSupport.class, ValkeyContainerSupport.class})
class CheckoutCouponSqsFlowTest {

    private static final String USER = "11111111-1111-4111-8111-111111111111";
    private static final Long SCREENING = 42L;
    private static final String MOCK_KEY = "test-mock-key";
    private static final String COUPON = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d";
    private static final Path CONTRACTS = Path.of("../../contracts/events");

    @MockitoBean
    CatalogClient catalogClient;
    @MockitoBean
    PaymentClient paymentClient;

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

    /** 모의 카드 + 쿠폰: 예약 명령 → coupon.reserved(50%) → 할인된 금액으로 청구 → 승인 → 확정 + 사용 명령 */
    @Test
    void reservedCouponDiscountsTheChargeAndConfirmSendsUse() throws IOException {
        Long bookingId = hold();

        CheckoutAcceptedResponse accepted = start(bookingId, card(), COUPON);

        assertThat(accepted.state()).isEqualTo(CheckoutState.COUPON_RESERVING);
        assertThat(bookings.findById(bookingId).orElseThrow().getCurrentPaymentId()).isNull();   // 쿠폰 단계에서는 붙이지 않는다(D14)
        assertThat(commands(OutboxDestination.PAYMENT_COMMANDS, "payment.charge-requested")).isEmpty();
        JsonNode reserve = command("coupon.reservation-requested");
        assertThat(reserve.at("/payload/couponIssueId").asText()).isEqualTo(COUPON);
        assertThat(reserve.at("/payload/userId").asText()).isEqualTo(USER);
        assertThat(reserve.at("/payload/bookingId").asLong()).isEqualTo(bookingId);
        assertThat(reserve.get("correlationId").asText()).isEqualTo(accepted.checkoutId());
        assertMatchesContract(reserve);

        replies.handle(couponReply(accepted.checkoutId(), "coupon.reserved", bookingId,
            "\"couponIssueId\":\"" + COUPON + "\",\"discountRate\":50,\"eventId\":3"));

        CheckoutSaga saga = saga(accepted);
        assertThat(saga.getState()).isEqualTo(CheckoutState.CHARGING);
        assertThat(saga.getDiscountAmount()).isEqualTo(15_000);
        assertThat(saga.getFinalAmount()).isEqualTo(15_000);
        String paymentId = bookings.findById(bookingId).orElseThrow().getCurrentPaymentId();
        assertThat(paymentId).isNotNull();
        JsonNode charge = commands(OutboxDestination.PAYMENT_COMMANDS, "payment.charge-requested").get(0);
        assertThat(charge.at("/payload/amount").asInt()).isEqualTo(15_000);
        assertThat(charge.at("/payload/cardLast4").asText()).isEqualTo("1112");   // 시작 때 남긴 끝 4자리

        replies.handle(paymentReply(accepted.checkoutId(), "payment.approved", paymentId, bookingId,
            "\"amount\":15000,\"approvedAt\":\"2026-11-24T14:00:13+09:00\",\"pgTransactionId\":null"));

        assertThat(saga(accepted).getState()).isEqualTo(CheckoutState.CONFIRMED);
        assertThat(bookings.findById(bookingId)).get().extracting(Booking::getStatus).isEqualTo(BookingStatus.CONFIRMED);
        assertMatchesContract(command("coupon.use-requested"));
        assertThat(commands(OutboxDestination.COUPON_COMMANDS, "coupon.release-requested")).isEmpty();
        verifyNoInteractions(paymentClient);
    }

    /** 거절 — 보상·반환 없이 COUPON_REJECTED, 예매·좌석 그대로라 쿠폰 없이 다시 시작할 수 있다(D14) */
    @Test
    void rejectedCouponEndsWithoutReleaseAndCanStartAgain() {
        Long bookingId = hold();
        CheckoutAcceptedResponse accepted = start(bookingId, card(), COUPON);

        replies.handle(couponReply(accepted.checkoutId(), "coupon.reservation-rejected", bookingId,
            "\"couponIssueId\":\"" + COUPON + "\",\"reason\":\"NOT_ISSUED\""));

        CheckoutSaga saga = saga(accepted);
        assertThat(saga.getState()).isEqualTo(CheckoutState.COUPON_REJECTED);
        assertThat(saga.getFailureCode()).isEqualTo("COUPON_NOT_USABLE");
        assertThat(saga.getFailureReason()).isEqualTo("NOT_ISSUED");
        assertThat(commands(OutboxDestination.COUPON_COMMANDS, "coupon.release-requested")).isEmpty();
        assertThat(bookings.findById(bookingId)).get().extracting(Booking::getStatus).isEqualTo(BookingStatus.PENDING);
        assertThat(bookingSeats.count()).isEqualTo(2);

        assertThat(start(bookingId, card(), null).state()).isEqualTo(CheckoutState.CHARGING);
    }

    /** 쿠폰 단계 시간 초과 → 좌석까지 보상 + 반환(예약 여부를 모른다). 그 뒤 온 coupon.reserved는 받기만 한다 */
    @Test
    void reservationTimeoutReleasesCouponAndLateReservedIsIgnored() throws IOException {
        Long bookingId = hold();
        CheckoutAcceptedResponse accepted = start(bookingId, card(), COUPON);
        pastDeadline(accepted.checkoutId());

        sweeper.sweepOnce();

        assertThat(saga(accepted).getState()).isEqualTo(CheckoutState.FAILED);
        assertThat(saga(accepted).getFailureCode()).isEqualTo("STEP_TIMEOUT");
        JsonNode release = command("coupon.release-requested");
        assertThat(release.at("/payload/reason").asText()).isEqualTo("STEP_TIMEOUT");
        assertMatchesContract(release);
        assertThat(commands(OutboxDestination.PAYMENT_COMMANDS, "payment.release-requested")).isEmpty();   // 결제가 없었다

        replies.handle(couponReply(accepted.checkoutId(), "coupon.reserved", bookingId,
            "\"couponIssueId\":\"" + COUPON + "\",\"discountRate\":50"));

        assertThat(saga(accepted).getState()).isEqualTo(CheckoutState.FAILED);
        assertThat(commands(OutboxDestination.PAYMENT_COMMANDS, "payment.charge-requested")).isEmpty();
        assertThat(inbox.count()).isEqualTo(1);
    }

    /** 결제 거절 보상 — 쿠폰 반환 사유는 PAYMENT_DECLINED */
    @Test
    void declinedAfterCouponReleasesWithReason() {
        Long bookingId = hold();
        CheckoutAcceptedResponse accepted = start(bookingId, card(), COUPON);
        replies.handle(couponReply(accepted.checkoutId(), "coupon.reserved", bookingId,
            "\"couponIssueId\":\"" + COUPON + "\",\"discountRate\":10"));
        String paymentId = bookings.findById(bookingId).orElseThrow().getCurrentPaymentId();

        replies.handle(paymentReply(accepted.checkoutId(), "payment.declined", paymentId, bookingId,
            "\"failureCode\":\"CARD_DECLINED\",\"retryable\":false"));

        assertThat(saga(accepted).getState()).isEqualTo(CheckoutState.FAILED);
        assertThat(command("coupon.release-requested").at("/payload/reason").asText()).isEqualTo("PAYMENT_DECLINED");
    }

    /** 금액을 정할 수 없는 결과(할인율 없음·범위 밖)는 형식 오류 — saga는 그대로, 시간 초과가 닫는다 */
    @Test
    void reservedWithoutUsableRateIsMalformed() {
        Long bookingId = hold();
        CheckoutAcceptedResponse accepted = start(bookingId, card(), COUPON);

        assertThatThrownBy(() -> replies.handle(couponReply(accepted.checkoutId(), "coupon.reserved", bookingId,
            "\"couponIssueId\":\"" + COUPON + "\",\"discountRate\":100")))
            .isInstanceOf(MalformedReplyException.class);

        assertThat(saga(accepted).getState()).isEqualTo(CheckoutState.COUPON_RESERVING);
        assertThat(inbox.count()).isZero();
    }

    // --- 도우미 ---

    private Long hold() {
        return seatHoldService.hold(USER, new SeatHoldRequest(SCREENING, List.of(101L, 102L))).bookingId();
    }

    private CheckoutAcceptedResponse start(Long bookingId, BookingPayRequest.Card card, String couponIssueId) {
        return checkoutService.start(USER, MOCK_KEY, bookingId, new CheckoutRequest(PaymentMethod.CARD, couponIssueId, card));
    }

    private static BookingPayRequest.Card card() {
        return new BookingPayRequest.Card("4111111111111112", "12/28", "KIM");
    }

    private CheckoutSaga saga(CheckoutAcceptedResponse accepted) {
        return sagas.findById(accepted.checkoutId()).orElseThrow();
    }

    private void pastDeadline(String checkoutId) {
        jdbc.update("update checkout_saga set step_deadline_at = utc_timestamp(3) - interval 1 second "
            + "where checkout_id = ?", checkoutId);
    }

    private List<JsonNode> commands(OutboxDestination destination, String type) {
        return outbox.findAll().stream()
            .filter(m -> m.getDestination() == destination)
            .filter(m -> type.equals(m.getEventType().messageType()))
            .map(OutboxMessage::getPayload)
            .map(this::json)
            .toList();
    }

    private JsonNode command(String type) {
        List<JsonNode> found = commands(OutboxDestination.COUPON_COMMANDS, type);
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private String couponReply(String checkoutId, String type, Long bookingId, String fields) {
        return """
            {"eventId":"%s","type":"%s","version":1,"occurredAt":"2026-11-27T14:05:02+09:00","producer":"coupon",
             "traceId":"4bf92f3577b34da6a3ce929d0e0e4736","correlationId":"%s",
             "payload":{"checkoutId":"%s","bookingId":%d,%s}}
            """.formatted(UUID.randomUUID(), type, checkoutId, checkoutId, bookingId, fields);
    }

    private String paymentReply(String checkoutId, String type, String paymentId, Long bookingId, String fields) {
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
        assertThat(message.at("/payload/couponIssueId").asText().length()).isLessThanOrEqualTo(64);
    }
}

package com.seatsac.booking.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.booking.checkout.dto.CheckoutAcceptedResponse;
import com.seatsac.booking.checkout.dto.CheckoutRequest;
import com.seatsac.booking.component.BookingEventPublisher;
import com.seatsac.booking.component.CatalogClient;
import com.seatsac.booking.component.CatalogClient.AuditoriumLayout;
import com.seatsac.booking.component.CatalogClient.AuditoriumLayout.LayoutSeat;
import com.seatsac.booking.component.PaymentClient;
import com.seatsac.booking.component.ScreeningCache;
import com.seatsac.booking.dto.request.BookingPayRequest;
import com.seatsac.booking.dto.request.SeatHoldRequest;
import com.seatsac.booking.entity.OutboxMessage;
import com.seatsac.booking.enums.OutboxDestination;
import com.seatsac.booking.enums.OutboxEventType;
import com.seatsac.booking.enums.PaymentMethod;
import com.seatsac.booking.repository.BookingRepository;
import com.seatsac.booking.repository.BookingSeatRepository;
import com.seatsac.booking.repository.OutboxMessageRepository;
import com.seatsac.booking.service.BookingCancelService;
import com.seatsac.booking.service.SeatHoldService;
import com.seatsac.common.test.MySqlContainerSupport;
import com.seatsac.common.test.ValkeyContainerSupport;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 6.2.4 SNS {@code booking-events}(D28·D29) — 보낼 곳이 설정된 상태({@link BookingEventPublisher#enabled()} = true)에서
 * 확정·취소·saga 보상이 아웃박스에 이벤트 행을 넣는지, 본문이 계약 필수 필드를 갖는지, 시간 지표가 남는지 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({MySqlContainerSupport.class, ValkeyContainerSupport.class})
class BookingEventsFlowTest {

    private static final String USER = "11111111-1111-4111-8111-111111111111";
    private static final Long SCREENING = 42L;
    private static final Path CONTRACTS = Path.of("../../contracts/events");

    @MockitoBean
    CatalogClient catalogClient;
    @MockitoBean
    PaymentClient paymentClient;
    @MockitoBean
    BookingEventPublisher eventPublisher;

    @Autowired
    ScreeningCache screeningCache;
    @Autowired
    SeatHoldService seatHoldService;
    @Autowired
    CheckoutSagaService checkoutService;
    @Autowired
    CheckoutReplyHandler replies;
    @Autowired
    BookingCancelService cancelService;
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
    ObjectMapper objectMapper;
    @Autowired
    MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        inbox.deleteAllInBatch();
        sagas.deleteAllInBatch();
        outbox.deleteAllInBatch();
        bookingSeats.deleteAllInBatch();
        bookings.deleteAllInBatch();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        screeningCache.clear();
        given(eventPublisher.enabled()).willReturn(true);
        Instant now = Instant.now();
        given(catalogClient.findScreening(SCREENING)).willReturn(new CatalogClient.Screening(
            SCREENING, 10_004L, 1, now.plusSeconds(3600), now.plusSeconds(10_000), "승부", "건대입구", "1관"));
        given(catalogClient.findLayout(10_004L, 1)).willReturn(new AuditoriumLayout(10_004L, 1,
            IntStream.rangeClosed(1, 20).mapToObj(i -> new LayoutSeat(100L + i, "J" + i, true)).toList()));
    }

    /** D28 — 확정은 알림 큐 행 + SNS 행. eventId는 행마다 다르고, SNS 행은 최종 금액·할인액·checkoutId를 싣는다 */
    @Test
    void sagaConfirmWritesNotificationAndSnsRows() throws IOException {
        Long bookingId = hold();
        CheckoutAcceptedResponse accepted = startCard(bookingId);
        approve(accepted, bookingId);

        OutboxMessage notification = only(OutboxEventType.BOOKING_CONFIRMED, OutboxDestination.NOTIFICATION);
        OutboxMessage sns = only(OutboxEventType.BOOKING_CONFIRMED, OutboxDestination.BOOKING_EVENTS);
        assertThat(sns.getAggregateKey()).isEqualTo("sns");
        assertThat(sns.getMessageKey()).isNotEqualTo(notification.getMessageKey());
        assertThat(sns.getCorrelationId()).isEqualTo(accepted.checkoutId());
        JsonNode body = objectMapper.readTree(sns.getPayload());
        assertThat(body.get("eventId").asText()).isEqualTo(sns.getMessageKey());
        assertThat(body.get("correlationId").asText()).isEqualTo(accepted.checkoutId());
        assertThat(body.at("/payload/amount").asInt()).isEqualTo(30_000);
        assertThat(body.at("/payload/discountAmount").asInt()).isZero();
        assertMatchesContract(body);
        assertMatchesContract(objectMapper.readTree(notification.getPayload()));

        assertThat(meterRegistry.find("booking_checkout_step").tag("step", "applied").timer()).isNotNull();
        assertThat(meterRegistry.find("booking_checkout_step").tag("step", "verified").timer()).isNotNull();
        assertThat(meterRegistry.find("booking_checkout").timer()).isNotNull();
    }

    /** D29 — 확정 예매 취소(BKG-07)도 booking.canceled{USER_CANCELED}, 키 canceled */
    @Test
    void confirmedBookingCancelWritesUserCanceled() throws IOException {
        Long bookingId = hold();
        approve(startCard(bookingId), bookingId);

        cancelService.cancel(USER, bookingId);

        OutboxMessage canceled = only(OutboxEventType.BOOKING_CANCELED, OutboxDestination.BOOKING_EVENTS);
        assertThat(canceled.getAggregateKey()).isEqualTo("canceled");
        assertThat(canceled.getCorrelationId()).isNull();
        JsonNode body = objectMapper.readTree(canceled.getPayload());
        assertThat(body.at("/payload/reason").asText()).isEqualTo("USER_CANCELED");
        assertThat(body.at("/payload/bookingId").asLong()).isEqualTo(bookingId);
        assertMatchesContract(body);
    }

    /** saga 보상 — 결제 거절로 예매가 PAYMENT_FAILED면 booking.canceled{PAYMENT_FAILED}, 키 canceled:{checkoutId} */
    @Test
    void sagaDeclineWritesPaymentFailed() throws IOException {
        Long bookingId = hold();
        CheckoutAcceptedResponse accepted = startCard(bookingId);
        String paymentId = bookings.findById(bookingId).orElseThrow().getCurrentPaymentId();

        replies.handle(reply(accepted.checkoutId(), "payment.declined", paymentId, bookingId,
            "\"failureCode\":\"CARD_DECLINED\",\"retryable\":false"));

        OutboxMessage canceled = only(OutboxEventType.BOOKING_CANCELED, OutboxDestination.BOOKING_EVENTS);
        assertThat(canceled.getAggregateKey()).isEqualTo("canceled:" + accepted.checkoutId());
        assertThat(canceled.getCorrelationId()).isEqualTo(accepted.checkoutId());
        JsonNode body = objectMapper.readTree(canceled.getPayload());
        assertThat(body.at("/payload/reason").asText()).isEqualTo("PAYMENT_FAILED");
        assertMatchesContract(body);
        assertThat(outbox.findAll()).noneMatch(m -> m.getEventType() == OutboxEventType.BOOKING_CONFIRMED);
    }

    private Long hold() {
        return seatHoldService.hold(USER, new SeatHoldRequest(SCREENING, List.of(101L, 102L))).bookingId();
    }

    private CheckoutAcceptedResponse startCard(Long bookingId) {
        return checkoutService.start(USER, "test-mock-key", bookingId, new CheckoutRequest(PaymentMethod.CARD, null,
            new BookingPayRequest.Card("4111111111111112", "12/28", "KIM")));
    }

    private void approve(CheckoutAcceptedResponse accepted, Long bookingId) {
        String paymentId = bookings.findById(bookingId).orElseThrow().getCurrentPaymentId();
        replies.handle(reply(accepted.checkoutId(), "payment.approved", paymentId, bookingId,
            "\"amount\":30000,\"approvedAt\":\"2026-11-24T14:00:13+09:00\",\"pgTransactionId\":null"));
    }

    private OutboxMessage only(OutboxEventType type, OutboxDestination destination) {
        List<OutboxMessage> rows = outbox.findAll().stream()
            .filter(m -> m.getEventType() == type && m.getDestination() == destination).toList();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private String reply(String checkoutId, String type, String paymentId, Long bookingId, String fields) {
        return """
            {"eventId":"%s","type":"%s","version":1,"occurredAt":"2026-11-24T14:00:13+09:00","producer":"payment",
             "traceId":"4bf92f3577b34da6a3ce929d0e0e4736","correlationId":"%s",
             "payload":{"checkoutId":"%s","paymentId":"%s","bookingId":%d,%s}}
            """.formatted(UUID.randomUUID(), type, checkoutId, checkoutId, paymentId, bookingId, fields);
    }

    private void assertMatchesContract(JsonNode message) throws IOException {
        JsonNode schema = objectMapper.readTree(Files.readString(CONTRACTS.resolve(message.get("type").asText() + ".v1.json")));
        schema.get("required").forEach(f -> assertThat(message.has(f.asText())).as(f.asText()).isTrue());
        schema.at("/properties/payload/required").forEach(f ->
            assertThat(message.get("payload").has(f.asText())).as("payload." + f.asText()).isTrue());
        assertThat(message.get("producer").asText()).isEqualTo("booking");
    }
}

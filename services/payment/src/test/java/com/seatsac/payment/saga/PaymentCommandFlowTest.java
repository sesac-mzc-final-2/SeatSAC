package com.seatsac.payment.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.common.test.MySqlContainerSupport;
import com.seatsac.payment.dto.request.PaymentCreateRequest;
import com.seatsac.payment.entity.Payment;
import com.seatsac.payment.enums.PaymentFailureCode;
import com.seatsac.payment.enums.PaymentMethod;
import com.seatsac.payment.enums.PaymentStatus;
import com.seatsac.payment.portone.FakePortOne;
import com.seatsac.payment.portone.FakePortOne.Remote;
import com.seatsac.payment.repository.PaymentRepository;
import com.seatsac.payment.service.PaymentService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 6.2.3 saga 명령 → 결과 (설계 02). MySQL은 실제로 띄우고 포트원은 {@link FakePortOne}, SQS는 끊는다 —
 * 리스너가 부르는 {@link PaymentCommandHandler#handle}을 직접 부르고 발송({@link ReplyPublisher})은 가짜로 바꾼다.
 * 결과 메시지는 계약 파일({@code contracts/events/payment.*.v1.json})의 필수 필드를 모두 갖는지 함께 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlContainerSupport.class)
class PaymentCommandFlowTest {

    private static final FakePortOne PORTONE = startFake();
    private static final Path CONTRACTS = Path.of("../../contracts/events");
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    @DynamicPropertySource
    static void portOneUrl(DynamicPropertyRegistry registry) {
        registry.add("app.portone.api-base-url", PORTONE::baseUrl);
    }

    @Autowired
    PaymentCommandHandler handler;
    @Autowired
    ReplyOutboxRelay relay;
    @Autowired
    PaymentService paymentService;
    @Autowired
    PaymentRepository payments;
    @Autowired
    ReplyOutboxRepository outbox;
    @Autowired
    ProcessedMessageRepository inbox;
    @Autowired
    InboxCleaner inboxCleaner;
    @Autowired
    SagaPaymentRepository sagaPayments;
    @Autowired
    CircuitBreakerRegistry circuitBreakers;
    @Autowired
    ObjectMapper objectMapper;
    @MockitoBean
    ReplyPublisher publisher;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    SagaResultWriter writer;

    private long booking = 9_100L;

    @BeforeEach
    void setUp() {
        outbox.deleteAllInBatch();
        inbox.deleteAllInBatch();
        sagaPayments.deleteAllInBatch();
        payments.deleteAllInBatch();
        PORTONE.reset();
        circuitBreakers.circuitBreaker("portone").reset();
        booking++;
    }

    @AfterAll
    static void stopFake() {
        PORTONE.close();
    }

    // --- 모의 카드 청구 ---

    @Test
    void chargeApprovesAndWritesInboxMarkAndOneApprovedReply() throws IOException {
        Ids ids = new Ids();

        assertThat(handler.handle(charge(ids, 30_000, "4242"))).isEqualTo(PaymentCommandHandler.Result.OK);

        Payment payment = payments.findById(ids.payment).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.getCardLast4()).isEqualTo("4242");
        assertThat(inbox.existsById(ids.command)).isTrue();
        assertThat(sagaPayments.findById(ids.payment)).get()
            .extracting(SagaPayment::getCheckoutId).isEqualTo(ids.checkout);
        JsonNode reply = onlyReply();
        assertThat(reply.get("type").asText()).isEqualTo("payment.approved");
        assertThat(reply.get("correlationId").asText()).isEqualTo(ids.checkout);
        assertThat(reply.get("traceId").asText()).isEqualTo(TRACE);
        assertThat(reply.at("/payload/amount").asInt()).isEqualTo(30_000);
        assertMatchesContract(reply);
    }

    @Test
    void sameCommandTwiceProcessesOnceAndRepliesOnce() {
        Ids ids = new Ids();
        String body = charge(ids, 30_000, "4242");

        handler.handle(body);
        assertThat(handler.handle(body)).isEqualTo(PaymentCommandHandler.Result.DUPLICATE);

        assertThat(outbox.findByAggregateIdOrderByOutboxIdAsc(booking)).hasSize(1);
        assertThat(payments.count()).isEqualTo(1);
    }

    @Test
    void declinedMockCardIsNotRetryable() throws IOException {
        Ids ids = new Ids();

        handler.handle(charge(ids, 30_000, "0000"));

        JsonNode reply = onlyReply();
        assertThat(reply.get("type").asText()).isEqualTo("payment.declined");
        assertThat(reply.at("/payload/failureCode").asText()).isEqualTo("CARD_DECLINED");
        assertThat(reply.at("/payload/retryable").asBoolean()).isFalse();
        assertMatchesContract(reply);
    }

    /** D20 — 쿠폰 할인 금액(15,000 배수가 아님)도 받는다 */
    @Test
    void discountedAmountIsAccepted() {
        Ids ids = new Ids();

        handler.handle(charge(ids, 7_500, "4242"));

        assertThat(payments.findById(ids.payment)).get().extracting(Payment::getAmount).isEqualTo(7_500);
        assertThat(onlyReply().get("type").asText()).isEqualTo("payment.approved");
    }

    // --- 확인 ---

    /** 표준 큐라 확인이 청구보다 먼저 올 수 있다 — 예외(재배달·DLQ)가 아니라 결과 불명 */
    @Test
    void verificationBeforeAnyPaymentRowIsResultUnknownNotFound() throws IOException {
        Ids ids = new Ids();

        handler.handle(verification(ids, 1));

        JsonNode reply = onlyReply();
        assertThat(reply.get("type").asText()).isEqualTo("payment.result-unknown");
        assertThat(reply.at("/payload/reason").asText()).isEqualTo("NOT_FOUND");
        assertThat(reply.at("/payload/attempt").asInt()).isEqualTo(1);
        assertMatchesContract(reply);
    }

    @Test
    void tossPayPrepareThenVerifyUserCancelIsRetryableDecline() throws IOException {
        Ids ids = new Ids();

        handler.handle(prepare(ids, 15_000));
        JsonNode prepared = onlyReply();
        assertThat(prepared.get("type").asText()).isEqualTo("payment.prepared");
        assertThat(prepared.at("/payload/checkout/storeId").asText()).isEqualTo("store-test");
        assertMatchesContract(prepared);

        PORTONE.payments.put(ids.payment, Remote.failed("사용자가 결제를 취소했습니다"));
        outbox.deleteAllInBatch();
        handler.handle(verification(ids, 1));

        JsonNode declined = onlyReply();
        assertThat(declined.at("/payload/failureCode").asText()).isEqualTo("USER_CANCELED");
        assertThat(declined.at("/payload/retryable").asBoolean()).isTrue();
        assertMatchesContract(declined);
    }

    @Test
    void verifyPaidIsApprovedAndNotYetIsResultUnknown() throws IOException {
        Ids ids = new Ids();
        handler.handle(prepare(ids, 15_000));
        outbox.deleteAllInBatch();

        PORTONE.payments.put(ids.payment, Remote.ready());
        handler.handle(verification(ids, 1));
        assertThat(onlyReply().at("/payload/reason").asText()).isEqualTo("NOT_YET");

        outbox.deleteAllInBatch();
        PORTONE.payments.put(ids.payment, Remote.paid(15_000));
        handler.handle(verification(ids, 2));
        JsonNode approved = onlyReply();
        assertThat(approved.get("type").asText()).isEqualTo("payment.approved");
        assertThat(approved.at("/payload/pgTransactionId").isTextual()).isTrue();
        assertMatchesContract(approved);
    }

    @Test
    void portOneDownIsResultUnknownNotAnException() {
        Ids ids = new Ids();
        handler.handle(prepare(ids, 15_000));
        outbox.deleteAllInBatch();
        PORTONE.getStatus = 500;

        handler.handle(verification(ids, 3));

        JsonNode reply = onlyReply();
        assertThat(reply.at("/payload/reason").asText()).isEqualTo("PORTONE_UNAVAILABLE");
        assertThat(reply.at("/payload/attempt").asInt()).isEqualTo(3);
        assertThat(payments.findById(ids.payment)).get().extracting(Payment::getStatus)
            .isEqualTo(PaymentStatus.PROCESSING);
    }

    // --- 해제 (D16) ---

    @Test
    void releaseOfApprovedTossPayMakesRefundOwedAndRepliesBookingExpired() throws IOException {
        Ids ids = new Ids();
        handler.handle(prepare(ids, 15_000));
        PORTONE.payments.put(ids.payment, Remote.paid(15_000));
        handler.handle(verification(ids, 1));
        outbox.deleteAllInBatch();

        handler.handle(release(ids));

        Payment payment = payments.findById(ids.payment).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureCode()).isEqualTo(PaymentFailureCode.BOOKING_EXPIRED);
        assertThat(payment.isRefundOwed()).isTrue();   // 스윕이 포트원 전액 취소를 보낸다
        assertThat(PORTONE.cancels()).isEmpty();      // 해제 명령은 포트원을 부르지 않는다(D6)
        JsonNode reply = onlyReply();
        assertThat(reply.at("/payload/failureCode").asText()).isEqualTo("BOOKING_EXPIRED");
        assertThat(reply.at("/payload/retryable").asBoolean()).isFalse();
        assertMatchesContract(reply);
    }

    @Test
    void releaseWhileProcessingThenPaidBecomesBookingExpiredNotApproved() {
        Ids ids = new Ids();
        handler.handle(prepare(ids, 15_000));
        handler.handle(release(ids));
        outbox.deleteAllInBatch();

        PORTONE.payments.put(ids.payment, Remote.paid(15_000));
        handler.handle(verification(ids, 1));

        Payment payment = payments.findById(ids.payment).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureCode()).isEqualTo(PaymentFailureCode.BOOKING_EXPIRED);
        assertThat(payment.getPgTransactionId()).isNotNull();
        assertThat(onlyReply().at("/payload/failureCode").asText()).isEqualTo("BOOKING_EXPIRED");
    }

    /** 해제가 준비보다 먼저 왔다(순서 역전) — 늦은 준비는 결제를 만들지 않는다 */
    @Test
    void releaseBeforePrepareStopsTheLatePrepare() {
        Ids ids = new Ids();
        handler.handle(release(ids));

        handler.handle(prepare(ids, 15_000));

        assertThat(payments.findById(ids.payment)).isEmpty();
        JsonNode reply = onlyReply();
        assertThat(reply.get("type").asText()).isEqualTo("payment.preparation-failed");
        assertThat(reply.at("/payload/reason").asText()).isEqualTo("BOOKING_EXPIRED");
    }

    @Test
    void releaseOfApprovedMockCancelsDirectly() {
        Ids ids = new Ids();
        handler.handle(charge(ids, 30_000, "4242"));
        outbox.deleteAllInBatch();

        handler.handle(release(ids));

        assertThat(payments.findById(ids.payment)).get().extracting(Payment::getStatus)
            .isEqualTo(PaymentStatus.CANCELED);
        assertThat(onlyReply().at("/payload/failureCode").asText()).isEqualTo("BOOKING_EXPIRED");
    }

    // --- inbox 정리(04 4장) ---

    /** 받은 지 7일이 지난 inbox 행만 지운다. 1,000건씩 나눠 지워도 끝까지 */
    @Test
    void inboxCleanerDeletesOnlyRowsOlderThanSevenDays() {
        java.time.Instant now = java.time.Instant.now();
        List<ProcessedMessage> old = new ArrayList<>();
        for (int i = 0; i < 1_001; i++) {   // 한 번(1,000건)으로 끝나지 않게
            old.add(ProcessedMessage.of(UUID.randomUUID().toString(), "payment.charge-requested",
                now.minus(java.time.Duration.ofDays(8))));
        }
        inbox.saveAll(old);
        String recent = UUID.randomUUID().toString();
        inbox.save(ProcessedMessage.of(recent, "payment.charge-requested", now.minus(java.time.Duration.ofDays(6))));

        assertThat(inboxCleaner.cleanOnce()).isEqualTo(1_001);

        assertThat(inbox.findAll()).extracting(ProcessedMessage::getMessageId).containsExactly(recent);
        assertThat(inboxCleaner.cleanOnce()).isZero();
    }

    // --- 형식 · 유일 키 · 릴레이 ---

    @Test
    void malformedCommandIsRejectedWithoutTouchingTheDatabase() {
        assertThatThrownBy(() -> handler.handle("{\"type\":\"payment.charge-requested\"}"))
            .isInstanceOf(MalformedCommandException.class);
        assertThatThrownBy(() -> handler.handle(envelope(UUID.randomUUID().toString(), "payment.unknown",
            "{\"checkoutId\":\"%s\",\"paymentId\":\"%s\",\"bookingId\":1}".formatted(UUID.randomUUID(), UUID.randomUUID()))))
            .isInstanceOf(MalformedCommandException.class);
        assertThat(inbox.count()).isZero();
    }

    /** ADR-24 11/24 보완 — 실패한 결제가 있어도 같은 예매로 새 결제를 만들 수 있다. 살아 있는 결제 2건은 막힌다 */
    @Test
    void failedPaymentDoesNotBlockTheNextAttemptButLiveOneDoes() {
        Ids first = new Ids();
        handler.handle(charge(first, 30_000, "0000"));   // FAILED

        Ids second = new Ids();
        handler.handle(charge(second, 30_000, "4242"));  // 같은 예매, 새 결제 → APPROVED
        assertThat(payments.findById(second.payment)).get().extracting(Payment::getStatus)
            .isEqualTo(PaymentStatus.APPROVED);

        Ids third = new Ids();
        handler.handle(charge(third, 30_000, "4242"));   // 살아 있는 결제(APPROVED)가 있다
        assertThat(payments.findById(third.payment)).isEmpty();
        // 청구 결과는 approved·declined뿐(02) — preparation-failed면 CHARGING인 booking이 받지 않는다
        JsonNode last = replies().get(replies().size() - 1);
        assertThat(last.get("type").asText()).isEqualTo("payment.declined");
        assertThat(last.at("/payload/failureCode").asText()).isEqualTo("PAYMENT_CONFLICT");
        assertThat(last.at("/payload/retryable").asBoolean()).isFalse();
    }

    /** 해제가 청구보다 먼저 왔다 — 청구하지 않고 declined(BOOKING_EXPIRED) */
    @Test
    void releaseBeforeChargeDeclinesWithoutCharging() {
        Ids ids = new Ids();
        handler.handle(release(ids));

        handler.handle(charge(ids, 30_000, "4242"));

        assertThat(payments.findById(ids.payment)).isEmpty();
        JsonNode reply = onlyReply();
        assertThat(reply.get("type").asText()).isEqualTo("payment.declined");
        assertThat(reply.at("/payload/failureCode").asText()).isEqualTo("BOOKING_EXPIRED");
        assertThat(reply.at("/payload/retryable").asBoolean()).isFalse();
    }

    /** 유일 키 충돌인데 inbox에 이 명령이 없다(다른 표의 충돌) — 중복으로 지우지 않고 재배달시킨다 */
    @Test
    void uniqueKeyClashOutsideTheInboxIsRedeliveredNotDropped() {
        Ids ids = new Ids();
        doThrow(new org.springframework.dao.DataIntegrityViolationException("saga_payment PK"))
            .when(writer).recordRelease(any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), any(), any());

        assertThatThrownBy(() -> handler.handle(release(ids)))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(inbox.existsById(ids.releaseCommand)).isFalse();

        org.mockito.Mockito.reset(writer);
        handler.handle(release(ids));   // 재배달 — 이번에는 처리된다
        assertThat(sagaPayments.isReleased(ids.payment)).isTrue();
    }

    @Test
    void relaySendsPendingRepliesOnceAndRetriesOnFailure() {
        handler.handle(charge(new Ids(), 30_000, "4242"));
        doThrow(new IllegalStateException("queue down")).when(publisher).publish(any());

        assertThat(relay.relayOnce()).isZero();
        assertThat(outbox.findAll()).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(ReplyOutbox.Status.PENDING);
            assertThat(row.getAttemptCount()).isEqualTo(1);
        });

        org.mockito.Mockito.reset(publisher);
        sleepQuietly(1_100);   // 첫 재시도 간격 1초
        assertThat(relay.relayOnce()).isEqualTo(1);
        verify(publisher, times(1)).publish(any());
        assertThat(outbox.findAll()).allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(ReplyOutbox.Status.SENT));
        assertThat(relay.relayOnce()).isZero();
        verify(publisher, times(1)).publish(any());
    }

    @Test
    void syncPathPaymentsGetNoSagaMarkOrReplies() {
        paymentService.pay(new PaymentCreateRequest(UUID.randomUUID().toString(), booking, 30_000, PaymentMethod.CARD,
            null, new PaymentCreateRequest.Card("4242424242424242", "12/28", "KIM")));

        assertThat(sagaPayments.count()).isZero();
        assertThat(outbox.count()).isZero();
        verify(publisher, never()).publish(any());
    }

    // --- 도우미 ---

    private final class Ids {
        final String checkout = UUID.randomUUID().toString();
        final String payment = UUID.randomUUID().toString();
        final String command = UUID.randomUUID().toString();
        final String releaseCommand = UUID.randomUUID().toString();
    }

    private String charge(Ids ids, int amount, String last4) {
        return envelope(ids.command, "payment.charge-requested", """
            {"checkoutId":"%s","paymentId":"%s","bookingId":%d,"amount":%d,"method":"CARD","cardLast4":"%s"}
            """.formatted(ids.checkout, ids.payment, booking, amount, last4), ids.checkout);
    }

    private String prepare(Ids ids, int amount) {
        return envelope(UUID.randomUUID().toString(), "payment.preparation-requested", """
            {"checkoutId":"%s","paymentId":"%s","bookingId":%d,"amount":%d,"method":"TOSSPAY","orderName":"SeatSAC 영화 예매 1석"}
            """.formatted(ids.checkout, ids.payment, booking, amount), ids.checkout);
    }

    private String verification(Ids ids, int attempt) {
        return envelope(UUID.randomUUID().toString(), "payment.verification-requested", """
            {"checkoutId":"%s","paymentId":"%s","bookingId":%d,"expectedAmount":15000,"attempt":%d}
            """.formatted(ids.checkout, ids.payment, booking, attempt), ids.checkout);
    }

    private String release(Ids ids) {
        return envelope(ids.releaseCommand, "payment.release-requested", """
            {"checkoutId":"%s","paymentId":"%s","bookingId":%d,"reason":"HOLD_EXPIRED"}
            """.formatted(ids.checkout, ids.payment, booking), ids.checkout);
    }

    private static String envelope(String eventId, String type, String payload) {
        return envelope(eventId, type, payload, UUID.randomUUID().toString());
    }

    private static String envelope(String eventId, String type, String payload, String correlationId) {
        return """
            {"eventId":"%s","type":"%s","version":1,"occurredAt":"2026-11-24T14:00:00+09:00","producer":"booking",
             "traceId":"%s","correlationId":"%s","payload":%s}
            """.formatted(eventId, type, TRACE, correlationId, payload.strip());
    }

    private List<JsonNode> replies() {
        List<JsonNode> result = new ArrayList<>();
        for (ReplyOutbox row : outbox.findAll()) {
            try {
                result.add(objectMapper.readTree(row.getPayload()));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return result;
    }

    private JsonNode onlyReply() {
        List<JsonNode> all = replies();
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    /** 계약 파일의 envelope·payload 필수 필드가 모두 있는가(타입 검사는 계약 CI의 예시 검증이 맡는다) */
    private void assertMatchesContract(JsonNode reply) throws IOException {
        JsonNode schema = objectMapper.readTree(Files.readString(CONTRACTS.resolve(reply.get("type").asText() + ".v1.json")));
        schema.get("required").forEach(field -> assertThat(reply.has(field.asText())).as(field.asText()).isTrue());
        schema.at("/properties/payload/required").forEach(field ->
            assertThat(reply.get("payload").has(field.asText())).as("payload." + field.asText()).isTrue());
        assertThat(reply.get("producer").asText()).isEqualTo("payment");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static FakePortOne startFake() {
        try {
            return new FakePortOne();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

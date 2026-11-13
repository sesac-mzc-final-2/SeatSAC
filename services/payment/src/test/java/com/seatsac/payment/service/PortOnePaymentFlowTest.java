package com.seatsac.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.CommonResponseCode;
import com.seatsac.common.test.MySqlContainerSupport;
import com.seatsac.payment.component.PortOneSweeper;
import com.seatsac.payment.component.StaleProcessingCloser;
import com.seatsac.payment.dto.request.PaymentCreateRequest;
import com.seatsac.payment.dto.response.PaymentResponse;
import com.seatsac.payment.entity.Payment;
import com.seatsac.payment.enums.PaymentCancelReason;
import com.seatsac.payment.enums.PaymentFailureCode;
import com.seatsac.payment.enums.PaymentMethod;
import com.seatsac.payment.enums.PaymentStatus;
import com.seatsac.payment.enums.PgProvider;
import com.seatsac.payment.portone.FakePortOne;
import com.seatsac.payment.portone.FakePortOne.Remote;
import com.seatsac.payment.repository.PaymentRepository;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
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

/**
 * TOSSPAY(포트원) 흐름 — ADR-112. 포트원은 {@link FakePortOne}이 대신하고 MySQL은 실제로 띄운다.
 * 명세: 생성(PROCESSING + checkout) → verify(단건 조회로 확정) → 취소(멱등 키 없음 → 대조) → 스윕(결제창 미완료·환불 미완료).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlContainerSupport.class)
class PortOnePaymentFlowTest {

    private static final Long BOOKING = 7001L;
    private static final FakePortOne PORTONE = startFake();

    @DynamicPropertySource
    static void portOneUrl(DynamicPropertyRegistry registry) {
        registry.add("app.portone.api-base-url", PORTONE::baseUrl);
    }

    @Autowired
    PaymentService paymentService;
    @Autowired
    PaymentStateService paymentState;
    @Autowired
    PortOneWebhookService webhookService;
    @Autowired
    PortOneSweeper sweeper;
    @Autowired
    StaleProcessingCloser mockCloser;
    @Autowired
    PaymentRepository paymentRepository;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAllInBatch();
        PORTONE.reset();
    }

    @AfterAll
    static void stopFake() {
        PORTONE.close();
    }

    // --- 생성 ---

    @Test
    void tossPayCreatesProcessingRowWithCheckoutValuesAndNoCard() {
        PaymentResponse response = paymentService.pay(tossPay(BOOKING, 30_000));

        assertThat(response.status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(response.method()).isEqualTo(PaymentMethod.TOSSPAY);
        assertThat(response.pgProvider()).isEqualTo(PgProvider.PORTONE);
        assertThat(response.cardLast4()).isNull();
        assertThat(response.checkout()).isNotNull();
        assertThat(response.checkout().storeId()).isEqualTo("store-test");
        assertThat(response.checkout().channelKey()).isEqualTo("channel-key-tosspay-test");
        assertThat(response.checkout().payMethod()).isEqualTo("EASY_PAY");
        assertThat(response.checkout().currency()).isEqualTo("KRW");
        // 돈이 움직이지 않는다 — 포트원을 부르지 않았다
        assertThat(PORTONE.received).isEmpty();
    }

    @Test
    void sameTossPayRequestTwiceReturnsTheSameProcessingRow() {
        PaymentCreateRequest request = tossPay(BOOKING, 30_000);
        paymentService.pay(request);

        PaymentResponse again = paymentService.pay(request);

        assertThat(again.status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(paymentRepository.count()).isEqualTo(1);
    }

    @Test
    void tossPayNeedsOrderNameAndCardNeedsCard() {
        assertThatThrownBy(() -> paymentService.pay(new PaymentCreateRequest(UUID.randomUUID().toString(), BOOKING,
            30_000, PaymentMethod.TOSSPAY, null, null)))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.VALIDATION_FAILED.name());
        assertThatThrownBy(() -> paymentService.pay(new PaymentCreateRequest(UUID.randomUUID().toString(), BOOKING,
            30_000, PaymentMethod.CARD, null, null)))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.VALIDATION_FAILED.name());
    }

    @Test
    void mockCardStillJudgedWithoutPortOne() {
        PaymentResponse response = paymentService.pay(new PaymentCreateRequest(UUID.randomUUID().toString(), BOOKING,
            30_000, PaymentMethod.CARD, null, new PaymentCreateRequest.Card("4111111111111112", "12/28", "KIM")));

        assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(response.pgProvider()).isEqualTo(PgProvider.MOCK);
        assertThat(response.checkout()).isNull();
        assertThat(PORTONE.received).isEmpty();
    }

    // --- verify (PAY-04) ---

    @Test
    void verifyPaidWithMatchingAmountApproves() {
        String id = prepared(30_000);
        PORTONE.payments.put(id, Remote.paid(30_000));

        PaymentResponse response = paymentService.verify(id);

        assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(response.pgTransactionId()).isEqualTo("tx-30000");
        assertThat(response.approvedAt()).isNotNull();
        assertThat(response.checkout()).isNull();
        assertThat(PORTONE.gets()).hasSize(1);
        // API Secret은 Authorization: PortOne {secret} 로 간다
        assertThat(PORTONE.gets().get(0).headers().get("Authorization")).containsExactly("PortOne test-api-secret");
    }

    @Test
    void verifyBeforeCheckoutFinishedStaysProcessing() {
        String notOpened = prepared(30_000);                    // 포트원에 결제 자체가 없다(404)
        String ready = prepared(30_000);
        PORTONE.payments.put(ready, Remote.ready());

        assertThat(paymentService.verify(notOpened).status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(paymentService.verify(ready).status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(paymentService.verify(ready).checkout()).isNotNull();   // 화면이 다시 열 수 있게
    }

    @Test
    void verifyFailedIsDeclinedAndUserCancelIsToldApart() {
        String declined = prepared(30_000);
        PORTONE.payments.put(declined, Remote.failed("CARD_LIMIT_EXCEEDED"));
        String cancelled = prepared(30_000);
        PORTONE.payments.put(cancelled, Remote.failed("사용자가 결제를 취소했습니다"));

        assertThat(paymentService.verify(declined).failureCode()).isEqualTo(PaymentFailureCode.PG_DECLINED);
        assertThat(paymentService.verify(cancelled).failureCode()).isEqualTo(PaymentFailureCode.USER_CANCELED);
        assertThat(paymentService.verify(cancelled).status()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void verifyAmountMismatchFailsFirstThenRefunds() {
        String id = prepared(30_000);
        PORTONE.payments.put(id, Remote.paid(45_000));          // 브라우저에서 금액을 바꿔 결제했다

        PaymentResponse response = paymentService.verify(id);

        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(response.failureCode()).isEqualTo(PaymentFailureCode.AMOUNT_MISMATCH);
        assertThat(response.pgTransactionId()).isEqualTo("tx-45000");   // 돈이 잡힌 증거
        assertThat(response.canceledAt()).isNotNull();                   // 환불 확인
        assertThat(PORTONE.cancels()).hasSize(1);
        // 전액 + currentCancellableAmount 명시 — 포트원이 잔액을 비교해 겹친 요청을 하나만 받는다
        assertThat(PORTONE.cancels().get(0).body()).contains("\"currentCancellableAmount\":30000").contains("\"amount\":30000");
    }

    @Test
    void amountMismatchRefundLostIsFinishedByTheSweeper() {
        String id = prepared(30_000);
        PORTONE.payments.put(id, Remote.paid(45_000));
        PORTONE.cancelStatus = 500;                              // 환불 응답이 끊긴다

        PaymentResponse response = paymentService.verify(id);

        // 결정(FAILED)과 할 일(환불 미완료)은 먼저 남고, 응답은 그대로 FAILED다
        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(response.failureCode()).isEqualTo(PaymentFailureCode.AMOUNT_MISMATCH);
        assertThat(response.canceledAt()).isNull();
        assertThat(paymentRepository.findById(id).orElseThrow().isRefundOwed()).isTrue();

        // 60초가 안 지났으면 스윕이 건드리지 않는다(첫 요청이 포트원에서 처리 중일 수 있다)
        PORTONE.cancelStatus = 200;
        assertThat(sweeper.sweep()).isZero();
        // 60초가 지나면 포트원을 대조하고(아직 PAID) 다시 전액 취소를 보낸다
        paymentState.touch(id, Instant.now().minus(Duration.ofSeconds(120)));
        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(paymentService.get(id).canceledAt()).isNotNull();
        assertThat(PORTONE.cancels()).hasSize(2);
    }

    @Test
    void verifyIsIdempotentOnceSettledAndRejectsMockRows() {
        String id = prepared(30_000);
        PORTONE.payments.put(id, Remote.paid(30_000));
        paymentService.verify(id);
        int getsBefore = PORTONE.gets().size();

        assertThat(paymentService.verify(id).status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(PORTONE.gets()).hasSize(getsBefore);          // 끝난 행은 포트원을 다시 부르지 않는다

        PaymentResponse mock = paymentService.pay(new PaymentCreateRequest(UUID.randomUUID().toString(), 7002L,
            30_000, PaymentMethod.CARD, null, new PaymentCreateRequest.Card("4111111111111112", "12/28", "KIM")));
        assertThatThrownBy(() -> paymentService.verify(mock.paymentId()))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.PAYMENT_CONFLICT.name());
    }

    @Test
    void verifyWhenPortOneIsDownIsUnavailableAndLeavesTheRowAlone() {
        String id = prepared(30_000);
        PORTONE.getStatus = 500;

        assertThatThrownBy(() -> paymentService.verify(id))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.SERVICE_UNAVAILABLE.name());
        assertThat(paymentService.get(id).status()).isEqualTo(PaymentStatus.PROCESSING);
    }

    // --- 취소 (PAY-03, 멱등 키 없음) ---

    @Test
    void cancelApprovedPortOnePaymentCancelsAtPortOneFirst() {
        String id = approved(30_000);

        PaymentResponse response = paymentService.cancel(id, PaymentCancelReason.BOOKING_CANCELED);

        assertThat(response.status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(response.cancelReason()).isEqualTo(PaymentCancelReason.BOOKING_CANCELED);
        assertThat(PORTONE.cancels()).hasSize(1);
        assertThat(PORTONE.cancels().get(0).body()).contains("\"reason\":\"BOOKING_CANCELED\"");
        // 두 번째 취소는 포트원을 부르지 않고 200 (멱등)
        assertThat(paymentService.cancel(id, PaymentCancelReason.HOLD_EXPIRED).cancelReason())
            .isEqualTo(PaymentCancelReason.BOOKING_CANCELED);
        assertThat(PORTONE.cancels()).hasSize(1);
    }

    @Test
    void cancelRequestedByPgIsNotCompleteYet() {
        String id = approved(30_000);
        PORTONE.cancelBody = "{\"cancellation\":{\"status\":\"REQUESTED\",\"id\":\"c-2\"}}";

        assertThatThrownBy(() -> paymentService.cancel(id, PaymentCancelReason.BOOKING_CANCELED))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.SERVICE_UNAVAILABLE.name());
        assertThat(paymentService.get(id).status()).isEqualTo(PaymentStatus.APPROVED);   // 완료로 기록하지 않는다
    }

    @Test
    void cancelAlreadyCancelledAtPortOneIsReconciledNotRetried() {
        String id = approved(30_000);
        PORTONE.payments.put(id, Remote.cancelled(30_000));     // 앞선 요청이 이미 취소를 끝냈다
        PORTONE.cancelStatus = 409;
        PORTONE.cancelBody = "{\"type\" :\t\"PAYMENT_ALREADY_CANCELLED\",\n \"message\":\"이미 취소\"}";

        PaymentResponse response = paymentService.cancel(id, PaymentCancelReason.HOLD_EXPIRED);

        assertThat(response.status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(PORTONE.cancels()).hasSize(1);
        assertThat(PORTONE.gets()).hasSize(1);                   // 재호출 대신 대조
    }

    @Test
    void cancelResponseLostIsReconciledFromPortOne() {
        String id = approved(30_000);
        PORTONE.cancelStatus = 500;                              // 포트원은 취소했는데 응답을 못 받았다
        PORTONE.payments.put(id, Remote.cancelled(30_000));

        PaymentResponse response = paymentService.cancel(id, PaymentCancelReason.BOOKING_CANCELED);

        assertThat(response.status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(PORTONE.cancels()).hasSize(1);
    }

    @Test
    void cancelResponseLostAndStillPaidIsUnavailableNotCanceled() {
        String id = approved(30_000);
        PORTONE.cancelStatus = 500;
        PORTONE.payments.put(id, Remote.paid(30_000));

        assertThatThrownBy(() -> paymentService.cancel(id, PaymentCancelReason.BOOKING_CANCELED))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.SERVICE_UNAVAILABLE.name());
        assertThat(paymentService.get(id).status()).isEqualTo(PaymentStatus.APPROVED);
    }

    // --- 스윕: 결제창 미완료 (7분) ---

    @Test
    void sweeperClosesAbandonedCheckoutButMockCloserIgnoresPortOneRows() {
        Instant old = Instant.now().minus(Duration.ofMinutes(8));
        String abandoned = paymentState.startOrFind(UUID.randomUUID().toString(), 7101L, 30_000,
            PaymentMethod.TOSSPAY, null, old).payment().getPaymentId();          // 포트원에 없음
        String paidLate = paymentState.startOrFind(UUID.randomUUID().toString(), 7102L, 30_000,
            PaymentMethod.TOSSPAY, null, old).payment().getPaymentId();
        PORTONE.payments.put(paidLate, Remote.paid(30_000));
        String fresh = prepared(30_000);                        // 7분 안 — 결제창이 열려 있을 수 있다

        assertThat(mockCloser.closeStale()).isZero();           // 30초 규칙은 MOCK만
        assertThat(sweeper.sweep()).isEqualTo(2);

        assertThat(paymentService.get(abandoned).status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(paymentService.get(abandoned).failureCode()).isEqualTo(PaymentFailureCode.PROCESSING_TIMEOUT);
        assertThat(paymentService.get(paidLate).status()).isEqualTo(PaymentStatus.APPROVED);   // booking이 ADR-100대로 환불
        assertThat(paymentService.get(fresh).status()).isEqualTo(PaymentStatus.PROCESSING);
    }

    // --- 웹훅: 대조만 ---

    @Test
    void latePaymentWebhookOnlyMarksRefundOwedAndTheSweeperRefundsOnce() {
        String id = paymentState.startOrFind(UUID.randomUUID().toString(), 7201L, 30_000,
            PaymentMethod.TOSSPAY, null, Instant.now().minus(Duration.ofMinutes(8))).payment().getPaymentId();
        sweeper.sweep();                                        // 결제창 미완료 → FAILED(PROCESSING_TIMEOUT)
        PORTONE.payments.put(id, Remote.paid(30_000));          // 그 뒤 사용자가 결제를 끝냈다

        webhookService.handle("Transaction.Paid", id);
        webhookService.handle("Transaction.Paid", id);          // 재발송

        Payment payment = paymentRepository.findById(id).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.isRefundOwed()).isTrue();
        assertThat(PORTONE.cancels()).isEmpty();                // 웹훅은 포트원에 아무것도 보내지 않는다

        paymentState.touch(id, Instant.now().minus(Duration.ofSeconds(120)));
        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(PORTONE.cancels()).hasSize(1);
        assertThat(paymentService.get(id).canceledAt()).isNotNull();
    }

    @Test
    void paidWebhookOnProcessingRowApprovesAndCancelledWebhookOnApprovedRowCancels() {
        String id = prepared(30_000);
        PORTONE.payments.put(id, Remote.paid(30_000));

        webhookService.handle("Transaction.Paid", id);
        assertThat(paymentService.get(id).status()).isEqualTo(PaymentStatus.APPROVED);

        PORTONE.payments.put(id, Remote.cancelled(30_000));     // 콘솔에서 취소됨
        webhookService.handle("Transaction.Cancelled", id);
        assertThat(paymentService.get(id).status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(paymentService.get(id).cancelReason()).isEqualTo(PaymentCancelReason.STALE_PAYMENT);
        assertThat(PORTONE.cancels()).isEmpty();
    }

    @Test
    void webhookForUnknownOrMockPaymentIsIgnored() {
        webhookService.handle("Transaction.Paid", UUID.randomUUID().toString());
        PaymentResponse mock = paymentService.pay(new PaymentCreateRequest(UUID.randomUUID().toString(), 7301L,
            30_000, PaymentMethod.CARD, null, new PaymentCreateRequest.Card("4111111111111112", "12/28", "KIM")));
        webhookService.handle("Transaction.Cancelled", mock.paymentId());

        assertThat(paymentService.get(mock.paymentId()).status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(PORTONE.received).isEmpty();
    }

    // --- helpers ---

    private static PaymentCreateRequest tossPay(Long bookingId, int amount) {
        return new PaymentCreateRequest(UUID.randomUUID().toString(), bookingId, amount,
            PaymentMethod.TOSSPAY, "SeatSAC 영화 예매 2석", null);
    }

    private String prepared(int amount) {
        return paymentService.pay(tossPay(BOOKING + paymentRepository.count(), amount)).paymentId();
    }

    private String approved(int amount) {
        String id = prepared(amount);
        PORTONE.payments.put(id, Remote.paid(amount));
        paymentService.verify(id);
        PORTONE.received.clear();
        return id;
    }

    private static FakePortOne startFake() {
        try {
            return new FakePortOne();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

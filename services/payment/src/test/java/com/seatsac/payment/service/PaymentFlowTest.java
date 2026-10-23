package com.seatsac.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.CommonResponseCode;
import com.seatsac.common.test.MySqlContainerSupport;
import com.seatsac.payment.common.PaymentPolicy;
import com.seatsac.payment.component.StaleProcessingCloser;
import com.seatsac.payment.dto.request.PaymentCreateRequest;
import com.seatsac.payment.dto.response.PaymentResponse;
import com.seatsac.payment.entity.Payment;
import com.seatsac.payment.enums.PaymentCancelReason;
import com.seatsac.payment.enums.PaymentFailureCode;
import com.seatsac.payment.enums.PaymentMethod;
import com.seatsac.payment.enums.PaymentStatus;
import com.seatsac.payment.repository.PaymentRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * PAY-01·조회·PAY-03 흐름 (명세 4·5장). MySQL을 띄우고 모의 판정은 실제 코드를 쓴다
 * (지연만 테스트 프로필에서 50ms로 줄였다).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlContainerSupport.class)
class PaymentFlowTest {

    private static final Long BOOKING = 5001L;

    @Autowired
    PaymentService paymentService;
    @Autowired
    PaymentStateService paymentState;
    @Autowired
    StaleProcessingCloser staleProcessingCloser;
    @Autowired
    PaymentRepository paymentRepository;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAllInBatch();
    }

    // --- 모의 판정 (명세 5장) ---

    @Test
    void normalCardIsApproved() {
        PaymentResponse response = paymentService.pay(request(BOOKING, 30_000, "4111111111111112"));

        assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(response.cardLast4()).isEqualTo("1112");
        assertThat(response.failureCode()).isNull();
        assertThat(response.approvedAt()).isNotNull();
        assertThat(response.canceledAt()).isNull();
    }

    @Test
    void cardEndingInZerosIsDeclined() {
        PaymentResponse response = paymentService.pay(request(BOOKING, 30_000, "4111111111110000"));

        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(response.failureCode()).isEqualTo(PaymentFailureCode.CARD_DECLINED);
        assertThat(response.approvedAt()).isNull();
    }

    @Test
    void cardEndingInOnesHasNotEnoughBalance() {
        PaymentResponse response = paymentService.pay(request(BOOKING, 30_000, "4111111111111111"));

        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(response.failureCode()).isEqualTo(PaymentFailureCode.INSUFFICIENT_BALANCE);
    }

    /** 지연 카드도 결국 승인된다. 지연 중에는 행이 PROCESSING으로 보인다(아래 테스트) */
    @Test
    void delayedCardIsApprovedAfterTheDelay() {
        PaymentResponse response = paymentService.pay(request(BOOKING, 30_000, "4111111111119999"));

        assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
    }

    /** 명세 4장 — 판정 전에 행을 먼저 만들어 커밋한다. 재호출이 와도 이 행을 보고 돌아간다 */
    @Test
    void rowIsInsertedAsProcessingBeforeJudgement() {
        String paymentId = UUID.randomUUID().toString();

        var started = paymentState.startOrFind(paymentId, BOOKING, 30_000, PaymentMethod.CARD,
            "9999", Instant.now()).payment();

        assertThat(started.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(paymentService.get(paymentId).status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(started.getCardLast4()).isEqualTo("9999");
    }

    /**
     * 지연 카드가 판정 중일 때 같은 결제 ID로 다시 오면(Booking 3초 타임아웃 후 재호출)
     * <b>판정을 다시 하지 않고</b> PROCESSING을 그대로 돌려준다 — 또 5초를 기다리면 안 된다.
     */
    @Test
    void reCallWhileProcessingReturnsProcessingWithoutJudgingAgain() {
        PaymentCreateRequest request = request(BOOKING, 30_000, "4111111111119999");
        paymentState.startOrFind(request.paymentId(), BOOKING, 30_000, PaymentMethod.CARD, "9999",
            Instant.now());

        long startedAt = System.nanoTime();
        PaymentResponse response = paymentService.pay(request);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(response.status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(response.approvedAt()).isNull();
        assertThat(elapsedMillis).isLessThan(500);   // 테스트 프로필 지연 50ms도 타지 않는다
    }

    // --- 멱등 (명세 1·4장) ---

    @Test
    void sameRequestTwiceKeepsTheFirstResult() {
        PaymentCreateRequest request = request(BOOKING, 30_000, "4111111111110000");

        PaymentResponse first = paymentService.pay(request);
        PaymentResponse second = paymentService.pay(request);

        assertThat(second.status()).isEqualTo(first.status());
        assertThat(second.failureCode()).isEqualTo(first.failureCode());
        assertThat(second.createdAt()).isEqualTo(first.createdAt());
        assertThat(paymentRepository.count()).isEqualTo(1);
    }

    @Test
    void samePaymentIdWithDifferentContentIsConflict() {
        PaymentCreateRequest request = request(BOOKING, 30_000, "4111111111111112");
        paymentService.pay(request);

        PaymentCreateRequest changed = new PaymentCreateRequest(request.paymentId(), BOOKING, 45_000,
            PaymentMethod.CARD, request.card());

        assertThatThrownBy(() -> paymentService.pay(changed))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.PAYMENT_CONFLICT.name());
    }

    /** 예매당 결제 1회 (ADR-24) — 같은 예매에 다른 결제 ID면 유일 제약이 막는다 */
    @Test
    void secondPaymentForTheSameBookingIsConflict() {
        paymentService.pay(request(BOOKING, 30_000, "4111111111111112"));

        assertThatThrownBy(() -> paymentService.pay(request(BOOKING, 30_000, "4111111111111112")))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.PAYMENT_CONFLICT.name());
    }

    @Test
    void amountMustBeAMultipleOfSeatPrice() {
        assertThatThrownBy(() -> paymentService.pay(request(BOOKING, 20_000, "4111111111111112")))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.VALIDATION_FAILED.name());
        assertThat(paymentRepository.count()).isZero();   // 행을 만들지 않는다
    }

    // --- 조회 ---

    @Test
    void unknownPaymentIsNotFound() {
        assertThatThrownBy(() -> paymentService.get(UUID.randomUUID().toString()))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.NOT_FOUND.name());
    }

    // --- PAY-03 취소 ---

    @Test
    void approvedPaymentIsCanceled() {
        PaymentResponse approved = paymentService.pay(request(BOOKING, 30_000, "4111111111111112"));

        PaymentResponse canceled = paymentService.cancel(approved.paymentId(),
            PaymentCancelReason.HOLD_EXPIRED);

        assertThat(canceled.status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(canceled.cancelReason()).isEqualTo(PaymentCancelReason.HOLD_EXPIRED);
        assertThat(canceled.canceledAt()).isNotNull();
        assertThat(canceled.approvedAt()).isNotNull();   // 승인 시각은 남는다
    }

    /** 결제 승인과 선점 만료가 겹치면 양쪽이 모두 취소를 부른다 → 멱등, 첫 사유를 유지 */
    @Test
    void cancelIsIdempotentAndKeepsTheFirstReason() {
        PaymentResponse approved = paymentService.pay(request(BOOKING, 30_000, "4111111111111112"));
        paymentService.cancel(approved.paymentId(), PaymentCancelReason.HOLD_EXPIRED);

        PaymentResponse again = paymentService.cancel(approved.paymentId(),
            PaymentCancelReason.STALE_PAYMENT);

        assertThat(again.status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(again.cancelReason()).isEqualTo(PaymentCancelReason.HOLD_EXPIRED);
    }

    @Test
    void declinedPaymentCannotBeCanceled() {
        PaymentResponse declined = paymentService.pay(request(BOOKING, 30_000, "4111111111110000"));

        assertThatThrownBy(() -> paymentService.cancel(declined.paymentId(),
            PaymentCancelReason.BOOKING_CANCELED))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.PAYMENT_NOT_APPROVED.name());
    }

    /** PROCESSING은 결과를 모르는 상태라 취소하지 않는다 — Booking은 만료 처리를 보류한다 */
    @Test
    void processingPaymentCannotBeCanceled() {
        String paymentId = UUID.randomUUID().toString();
        paymentState.startOrFind(paymentId, BOOKING, 30_000, PaymentMethod.CARD, "9999",
            Instant.now());

        assertThatThrownBy(() -> paymentService.cancel(paymentId, PaymentCancelReason.STALE_PAYMENT))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.PAYMENT_NOT_APPROVED.name());
    }

    @Test
    void cancelingUnknownPaymentIsNotFound() {
        assertThatThrownBy(() -> paymentService.cancel(UUID.randomUUID().toString(),
            PaymentCancelReason.HOLD_EXPIRED))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.NOT_FOUND.name());
    }

    // --- 기동 시 PROCESSING 정리 (명세 3·7장) ---

    @Test
    void startupClosesProcessingRowsOlderThanThirtySeconds() {
        String stale = UUID.randomUUID().toString();
        paymentState.startOrFind(stale, BOOKING, 30_000, PaymentMethod.CARD, "9999",
            Instant.now().minus(PaymentPolicy.PROCESSING_TIMEOUT).minusSeconds(5));
        String fresh = UUID.randomUUID().toString();
        paymentState.startOrFind(fresh, 5002L, 30_000, PaymentMethod.CARD, "9999", Instant.now());

        assertThat(staleProcessingCloser.closeStale()).isEqualTo(1);

        assertThat(paymentService.get(stale).status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(paymentService.get(stale).failureCode())
            .isEqualTo(PaymentFailureCode.PROCESSING_TIMEOUT);
        // 방금 들어온 판정 중인 요청은 건드리지 않는다 (지연 카드 5초)
        assertThat(paymentService.get(fresh).status()).isEqualTo(PaymentStatus.PROCESSING);
    }

    private static PaymentCreateRequest request(Long bookingId, int amount, String cardNumber) {
        return new PaymentCreateRequest(UUID.randomUUID().toString(), bookingId, amount,
            PaymentMethod.CARD, new PaymentCreateRequest.Card(cardNumber, "12/28", "KIM"));
    }
}

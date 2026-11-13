package com.seatsac.payment.service;

import com.seatsac.common.core.error.ErrorDetail;
import com.seatsac.payment.common.PaymentErrorCode;
import com.seatsac.payment.common.PaymentPolicy;
import com.seatsac.payment.component.MockPaymentGateway;
import com.seatsac.payment.component.MockPaymentGateway.Judgement;
import com.seatsac.payment.config.PortOneProperties;
import com.seatsac.payment.dto.request.PaymentCreateRequest;
import com.seatsac.payment.dto.response.PaymentResponse;
import com.seatsac.payment.dto.response.PaymentResponse.Checkout;
import com.seatsac.payment.entity.Payment;
import com.seatsac.payment.enums.PaymentCancelReason;
import com.seatsac.payment.enums.PaymentMethod;
import com.seatsac.payment.enums.PaymentStatus;
import com.seatsac.payment.service.PaymentStateService.StartResult;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 결제 흐름 — 명세 4장, ADR-112. 트랜잭션은 {@link PaymentStateService}가 짧게 끊고, 이 클래스가 순서를 잡는다.
 *
 * <p>수단이 제공자를 정한다.
 * <pre>
 * CARD(MOCK)    1. 검증 → 2. [tx] PROCESSING INSERT → 3. [tx 밖] 모의 판정(지연 카드 5초) → 4. [tx] PROCESSING일 때만 결과 반영
 * TOSSPAY(PORTONE) 1. 검증 → 2. [tx] PROCESSING INSERT → 응답에 checkout(결제창 값). 돈은 브라우저 결제창에서 움직인다
 *                  → booking이 /verify를 부르면 포트원 단건 조회로 APPROVED·FAILED 확정({@link PortOneReconcileService})
 * </pre>
 *
 * <p>행을 먼저 만드는 이유: 같은 결제 ID로 재호출이 와도 두 번째는 저장된 상태를 돌려준다(판정·결제창을 다시 하지 않는다).
 * MOCK의 지연 카드 재호출 시나리오(타임아웃 → 상태 조회)가 시연 경로라 반드시 막아야 한다.
 */
@Slf4j
@Service
public class PaymentService {

    private final Clock clock;
    private final PaymentStateService paymentState;
    private final MockPaymentGateway gateway;
    private final PortOneReconcileService portOneReconcile;
    private final PortOneProperties portOneProperties;
    private final MeterRegistry meterRegistry;

    public PaymentService(Clock clock, PaymentStateService paymentState, MockPaymentGateway gateway,
                          PortOneReconcileService portOneReconcile, PortOneProperties portOneProperties,
                          MeterRegistry meterRegistry) {
        this.clock = clock;
        this.paymentState = paymentState;
        this.gateway = gateway;
        this.portOneReconcile = portOneReconcile;
        this.portOneProperties = portOneProperties;
        this.meterRegistry = meterRegistry;
    }

    /** PAY-01 — 승인·거절·{@code PROCESSING} 모두 200이다 */
    public PaymentResponse pay(PaymentCreateRequest request) {
        validateAmount(request.amount());
        validateMethodFields(request);
        return request.method() == PaymentMethod.TOSSPAY ? prepareTossPay(request) : payMockCard(request);
    }

    /** TOSSPAY — PROCESSING 행만 만들고 결제창 값을 돌려준다. 돈은 여기서 움직이지 않는다 */
    private PaymentResponse prepareTossPay(PaymentCreateRequest request) {
        if (!portOneProperties.enabled()) {
            throw PaymentErrorCode.PORTONE_DISABLED.exception();
        }
        StartResult started = start(request, null, clock.instant());
        Payment payment = started.payment();
        if (!started.created()) {
            log.info("같은 결제 ID 재호출 — 저장된 상태를 돌려준다 paymentId={} status={}",
                request.paymentId(), payment.getStatus());
        } else {
            log.info("포트원 결제 준비 paymentId={} bookingId={} amount={}", request.paymentId(),
                request.bookingId(), request.amount());
        }
        countRequest(payment.getStatus());
        return respond(payment);
    }

    /** CARD — 모의 판정까지 끝낸다(ADR-13). 테스트·분석 전용, 호출자 제한은 booking이 한다 */
    private PaymentResponse payMockCard(PaymentCreateRequest request) {
        Instant now = clock.instant();
        String cardLast4 = Payment.last4Of(request.card().number());
        StartResult started = start(request, cardLast4, now);
        if (!started.created()) {
            // 같은 결제 ID로 다시 왔다 — 저장된 상태를 그대로 돌려준다(PROCESSING이면 PROCESSING).
            // 판정을 다시 하지 않는다: 지연 카드라면 또 5초를 기다리게 되고 결과가 뒤집힐 수도 있다
            Payment current = started.payment();
            countRequest(current.getStatus());
            log.info("같은 결제 ID 재호출 — 저장된 상태를 돌려준다 paymentId={} status={}",
                request.paymentId(), current.getStatus());
            return respond(current);
        }

        Judgement judgement = gateway.judge(cardLast4);   // 트랜잭션 밖
        Instant settledAt = clock.instant();
        if (!paymentState.settle(request.paymentId(), judgement.status(), judgement.failureCode(), settledAt)) {
            // 그 사이 다른 경로(기동 정리·취소)가 닫았다. 현재 행이 정답이다
            log.info("결제 상태가 이미 바뀌어 있었다 paymentId={} bookingId={}",
                request.paymentId(), request.bookingId());
            return currentOrThrow(request.paymentId());
        }
        countRequest(judgement.status());
        log.info("결제 판정 paymentId={} bookingId={} status={} failureCode={}", request.paymentId(),
            request.bookingId(), judgement.status(), judgement.failureCode());
        return currentOrThrow(request.paymentId());
    }

    /** 결제 상태 조회 — Booking이 결과를 다시 확인할 때 (타임아웃·만료 처리). 포트원은 부르지 않는다 */
    public PaymentResponse get(String paymentId) {
        return currentOrThrow(paymentId);
    }

    /**
     * PAY-04 — 포트원 결제 확인(TOSSPAY). 결제창이 끝난 뒤 booking이 부른다. 포트원 단건 조회로 status·금액을 검증해
     * APPROVED·FAILED를 정한다. 아직 결제창이 끝나지 않았으면 PROCESSING 그대로(booking은 503 → 재조회).
     * 끝난 행은 포트원을 부르지 않고 그대로 돌려준다(멱등).
     */
    public PaymentResponse verify(String paymentId) {
        Payment payment = paymentState.find(paymentId)
            .orElseThrow(PaymentErrorCode.PAYMENT_NOT_FOUND::exception);
        if (!payment.isPortOne()) {
            throw PaymentErrorCode.NOT_VERIFIABLE.exception(
                ErrorDetail.of("paymentId", paymentId, PaymentErrorCode.NOT_VERIFIABLE.name()));
        }
        if (payment.isProcessing()) {
            PortOneReconcileService.Outcome outcome = portOneReconcile.reconcileProcessing(payment);
            switch (outcome) {
                case APPROVED -> countRequest(PaymentStatus.APPROVED);
                case FAILED, MISMATCH -> countRequest(PaymentStatus.FAILED);
                case CANCELED -> countRequest(PaymentStatus.CANCELED);
                case NOT_YET -> { /* 아직 모른다 — 지표는 생성 때 processing으로 셌다 */ }
            }
        }
        return currentOrThrow(paymentId);
    }

    /**
     * PAY-03 — 승인된 결제만 취소한다. 이미 취소된 결제는 <b>그대로 200</b>이다(멱등):
     * 결제 승인과 선점 만료가 겹치면 결제 쪽과 만료 쪽이 모두 취소를 부른다.
     * PORTONE은 포트원 전액 취소가 SUCCEEDED일 때만 CANCELED로 바꾼다({@link PortOneReconcileService#cancelApproved}).
     */
    public PaymentResponse cancel(String paymentId, PaymentCancelReason reason) {
        Payment payment = paymentState.find(paymentId)
            .orElseThrow(PaymentErrorCode.PAYMENT_NOT_FOUND::exception);
        if (payment.isCanceled()) {
            return PaymentResponse.from(payment);   // 첫 취소 사유를 유지한다
        }
        if (!payment.isApproved()) {
            throw notApproved(payment.getStatus());
        }
        if (payment.isPortOne()) {
            portOneReconcile.cancelApproved(payment, reason);   // 실패·미확정이면 503으로 올라간다
            meterRegistry.counter("payment_cancel_total", "reason", reason.name()).increment();
            return currentOrThrow(paymentId);
        }
        if (paymentState.cancel(paymentId, reason, clock.instant())) {
            meterRegistry.counter("payment_cancel_total", "reason", reason.name()).increment();
            log.info("결제 취소 paymentId={} bookingId={} reason={}", paymentId,
                payment.getBookingId(), reason);
        }
        // 0행이면 그 사이 다른 경로가 취소했다 — 어느 쪽이든 현재 행이 정답이다
        return currentOrThrow(paymentId);
    }

    /**
     * 2단계. 같은 결제 ID에 내용이 다르면 409, 같은 예매에 다른 결제 ID면 유일 제약이 막는다.
     * 동시에 들어온 첫 요청 둘이 겹치면 한쪽이 PK 위반을 받으므로 다시 읽어 그 행으로 간다.
     */
    private StartResult start(PaymentCreateRequest request, String cardLast4, Instant now) {
        StartResult started;
        try {
            started = paymentState.startOrFind(request.paymentId(), request.bookingId(),
                request.amount(), request.method(), cardLast4, now);
        } catch (DataIntegrityViolationException e) {
            Payment concurrent = paymentState.find(request.paymentId()).orElse(null);
            if (concurrent == null) {
                // 결제 ID는 비어 있는데 저장이 막혔다 → 같은 예매에 이미 다른 결제가 있다
                throw PaymentErrorCode.PAYMENT_ALREADY_EXISTS.exception(
                    ErrorDetail.of("bookingId", request.bookingId(),
                        PaymentErrorCode.PAYMENT_ALREADY_EXISTS.name()));
            }
            started = new StartResult(concurrent, false);
        }
        if (!started.payment().matches(request.bookingId(), request.amount())) {
            throw PaymentErrorCode.PAYMENT_ID_CONFLICT.exception(
                ErrorDetail.of("paymentId", request.paymentId(),
                    PaymentErrorCode.PAYMENT_ID_CONFLICT.name()));
        }
        return started;
    }

    /** 금액은 15,000의 배수여야 한다 — 범위는 DTO가 본다 (명세 4장) */
    private static void validateAmount(Integer amount) {
        if (amount % PaymentPolicy.SEAT_PRICE != 0) {
            throw PaymentErrorCode.INVALID_AMOUNT.exception(
                ErrorDetail.of("amount", amount, PaymentErrorCode.INVALID_AMOUNT.name()));
        }
    }

    /** 수단별 필수 필드 — CARD는 card, TOSSPAY는 orderName (계약 PaymentRequest) */
    private static void validateMethodFields(PaymentCreateRequest request) {
        if (request.method() == PaymentMethod.CARD && request.card() == null) {
            throw PaymentErrorCode.INVALID_CARD.exception(
                ErrorDetail.of("card", null, PaymentErrorCode.INVALID_CARD.name()));
        }
        if (request.method() == PaymentMethod.TOSSPAY
            && (request.orderName() == null || request.orderName().isBlank())) {
            throw PaymentErrorCode.INVALID_ORDER_NAME.exception(
                ErrorDetail.of("orderName", null, PaymentErrorCode.INVALID_ORDER_NAME.name()));
        }
    }

    private PaymentResponse currentOrThrow(String paymentId) {
        return paymentState.find(paymentId).map(this::respond)
            .orElseThrow(PaymentErrorCode.PAYMENT_NOT_FOUND::exception);
    }

    /** TOSSPAY PROCESSING(결제창 대기)에만 결제창 값을 붙인다 */
    private PaymentResponse respond(Payment payment) {
        if (payment.isPortOne() && payment.isProcessing()) {
            return PaymentResponse.from(payment,
                Checkout.portOneTossPay(portOneProperties.storeId(), portOneProperties.channelKeyTosspay()));
        }
        return PaymentResponse.from(payment);
    }

    private static com.seatsac.common.core.error.BusinessException notApproved(PaymentStatus status) {
        PaymentErrorCode code = status == PaymentStatus.PROCESSING
            ? PaymentErrorCode.PAYMENT_PROCESSING : PaymentErrorCode.PAYMENT_FAILED;
        return code.exception(ErrorDetail.of("status", status.name(), code.name()));
    }

    /** 지표 {@code payment_request_total{result}} — 라벨 값은 계약 5장 그대로 */
    private void countRequest(PaymentStatus status) {
        String result = switch (status) {
            case APPROVED -> "approved";
            case FAILED -> "declined";
            case PROCESSING -> "processing";
            case CANCELED -> "canceled";
        };
        meterRegistry.counter("payment_request_total", "result", result).increment();
    }
}

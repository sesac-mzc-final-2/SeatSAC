package com.seatsac.payment.service;

import com.seatsac.common.core.error.ErrorDetail;
import com.seatsac.payment.common.PaymentErrorCode;
import com.seatsac.payment.common.PaymentPolicy;
import com.seatsac.payment.component.MockPaymentGateway;
import com.seatsac.payment.component.MockPaymentGateway.Judgement;
import com.seatsac.payment.dto.request.PaymentCreateRequest;
import com.seatsac.payment.dto.response.PaymentResponse;
import com.seatsac.payment.entity.Payment;
import com.seatsac.payment.enums.PaymentCancelReason;
import com.seatsac.payment.enums.PaymentStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 결제 흐름 — 명세 4장. 트랜잭션은 {@link PaymentStateService}가 짧게 끊고, 이 클래스가 순서를 잡는다.
 *
 * <pre>
 * 1. 검증 (금액 배수·범위는 여기서, 형식은 DTO 애너테이션)
 * 2. [tx] 행이 있으면 그대로, 없으면 PROCESSING INSERT → 커밋
 * 3. [tx 밖] 모의 판정 (지연 카드는 5초) — 커넥션을 쥐지 않는다
 * 4. [tx] PROCESSING일 때만 결과 반영 → 0행이면 현재 행을 읽어 응답
 * </pre>
 *
 * <p>행을 먼저 만드는 이유: 지연 카드({@code 9999})를 기다리는 사이 Booking이 같은 결제 ID로
 * 재호출하면, 예전 설계(판정 → 저장)에서는 두 요청이 모두 INSERT를 시도해 PK 충돌로 500이 났다.
 * 이 시나리오가 시연 경로(타임아웃 → 상태 조회)라 반드시 막아야 한다.
 */
@Slf4j
@Service
public class PaymentService {

    private final Clock clock;
    private final PaymentStateService paymentState;
    private final MockPaymentGateway gateway;
    private final MeterRegistry meterRegistry;

    public PaymentService(Clock clock, PaymentStateService paymentState, MockPaymentGateway gateway,
                          MeterRegistry meterRegistry) {
        this.clock = clock;
        this.paymentState = paymentState;
        this.gateway = gateway;
        this.meterRegistry = meterRegistry;
    }

    /** PAY-01 — 승인·거절·{@code PROCESSING} 모두 200이다 */
    public PaymentResponse pay(PaymentCreateRequest request) {
        validateAmount(request.amount());
        Instant now = clock.instant();
        String cardLast4 = Payment.last4Of(request.card().number());

        Payment existing = start(request, cardLast4, now);
        if (existing.getStatus() != PaymentStatus.PROCESSING) {
            // 이미 끝난 결제에 같은 요청이 또 왔다 — 저장된 결과를 그대로 돌려준다(멱등)
            countRequest(existing.getStatus());
            return PaymentResponse.from(existing);
        }

        Judgement judgement = gateway.judge(cardLast4);   // 트랜잭션 밖
        Instant settledAt = clock.instant();
        if (!paymentState.settle(request.paymentId(), judgement.status(), judgement.failureCode(),
            settledAt)) {
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

    /** 결제 상태 조회 — Booking이 결과를 다시 확인할 때 (타임아웃·만료 처리) */
    public PaymentResponse get(String paymentId) {
        return currentOrThrow(paymentId);
    }

    /**
     * PAY-03 — 승인된 결제만 취소한다. 이미 취소된 결제는 <b>그대로 200</b>이다(멱등):
     * 결제 승인과 선점 만료가 겹치면 결제 쪽과 만료 쪽이 모두 취소를 부른다.
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
    private Payment start(PaymentCreateRequest request, String cardLast4, Instant now) {
        Payment payment;
        try {
            payment = paymentState.startOrFind(request.paymentId(), request.bookingId(),
                request.amount(), request.method(), cardLast4, now);
        } catch (DataIntegrityViolationException e) {
            Payment concurrent = paymentState.find(request.paymentId()).orElse(null);
            if (concurrent == null) {
                // 결제 ID는 비어 있는데 저장이 막혔다 → 같은 예매에 이미 다른 결제가 있다
                throw PaymentErrorCode.PAYMENT_ALREADY_EXISTS.exception(
                    ErrorDetail.of("bookingId", request.bookingId(),
                        PaymentErrorCode.PAYMENT_ALREADY_EXISTS.name()));
            }
            payment = concurrent;
        }

        if (!payment.matches(request.bookingId(), request.amount())) {
            throw PaymentErrorCode.PAYMENT_ID_CONFLICT.exception(
                ErrorDetail.of("paymentId", request.paymentId(),
                    PaymentErrorCode.PAYMENT_ID_CONFLICT.name()));
        }
        return payment;
    }

    /** 금액은 15,000의 배수여야 한다 — 범위는 DTO가 본다 (명세 4장) */
    private static void validateAmount(Integer amount) {
        if (amount % PaymentPolicy.SEAT_PRICE != 0) {
            throw PaymentErrorCode.INVALID_AMOUNT.exception(
                ErrorDetail.of("amount", amount, PaymentErrorCode.INVALID_AMOUNT.name()));
        }
    }

    private PaymentResponse currentOrThrow(String paymentId) {
        return paymentState.find(paymentId).map(PaymentResponse::from)
            .orElseThrow(PaymentErrorCode.PAYMENT_NOT_FOUND::exception);
    }

    private static com.seatsac.common.core.error.BusinessException notApproved(PaymentStatus status) {
        PaymentErrorCode code = status == PaymentStatus.PROCESSING
            ? PaymentErrorCode.PAYMENT_PROCESSING : PaymentErrorCode.PAYMENT_FAILED;
        return code.exception(ErrorDetail.of("status", status.name(), code.name()));
    }

    /** OPS-05 — 승인·거절·미확정 건수 */
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

package com.seatsac.payment.service;

import com.seatsac.payment.common.PaymentErrorCode;
import com.seatsac.payment.entity.Payment;
import com.seatsac.payment.enums.PaymentCancelReason;
import com.seatsac.payment.enums.PaymentFailureCode;
import com.seatsac.payment.enums.PaymentStatus;
import com.seatsac.payment.portone.PortOneClient;
import com.seatsac.payment.portone.PortOneClient.CancelOutcome;
import com.seatsac.payment.portone.PortOneClient.PortOneRejectedException;
import com.seatsac.payment.portone.PortOnePayment;
import com.seatsac.payment.portone.PortOneUnavailableException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 포트원 상태와 우리 행을 맞추는 곳 (ADR-112). verify·웹훅·스윕·취소가 모두 이 클래스를 거친다 —
 * 같은 결제를 세 경로가 동시에 봐도 조건부 UPDATE({@link PaymentStateService})가 한 번만 반영한다.
 *
 * <p>포트원 호출은 전부 <b>트랜잭션 밖</b>이다. 돈을 움직이는 요청은 전액 취소 하나뿐이고,
 * 취소 API에 멱등 키가 없으므로 「응답을 못 받으면 재호출이 아니라 조회로 대조」한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortOneReconcileService {

    /** 포트원 금액 대조 통화 — 우리는 원화만 받는다 */
    static final String CURRENCY = "KRW";

    private final Clock clock;
    private final PaymentStateService paymentState;
    private final PortOneClient portOne;

    /** {@link #reconcileProcessing} 결과 */
    public enum Outcome {
        /** 포트원 PAID + 금액 일치 → APPROVED */
        APPROVED,
        /** 포트원 FAILED → FAILED(PG_DECLINED·USER_CANCELED) */
        FAILED,
        /** 포트원 PAID인데 금액이 다름 → FAILED(AMOUNT_MISMATCH) + 환불 미완료 표식, 환불 시도 */
        MISMATCH,
        /** 포트원에서 이미 취소됨 → APPROVED → CANCELED */
        CANCELED,
        /** 아직 결제창이 끝나지 않았다(READY·PENDING, 또는 포트원에 없음) — 그대로 PROCESSING */
        NOT_YET
    }

    /**
     * PROCESSING인 PORTONE 행을 포트원 상태로 확정한다.
     *
     * @throws PortOneUnavailableException 포트원 응답을 못 받음 — 행은 그대로, 부르는 쪽이 503·다음 주기
     */
    public Outcome reconcileProcessing(Payment payment) {
        Optional<PortOnePayment> found = portOne.find(payment.getPaymentId());
        if (found.isEmpty()) {
            return Outcome.NOT_YET;   // 결제창을 열지 않았다(포트원에 결제 자체가 없음)
        }
        PortOnePayment remote = found.get();
        Instant now = clock.instant();
        String paymentId = payment.getPaymentId();

        if (remote.isPaid()) {
            if (remote.matches(payment.getAmount(), CURRENCY)) {
                paymentState.approve(paymentId, remote.transactionId(), remote.paidAtOr(now), now);
                log.info("포트원 결제 확인 → APPROVED paymentId={} bookingId={}", paymentId, payment.getBookingId());
                return Outcome.APPROVED;
            }
            // 금액 위변조 의심. 돈은 잡혔으므로 「쓰지 않는다(FAILED)」와 「환불해야 한다(transactionId)」를 먼저 남기고
            // 그다음 환불을 시도한다 — 환불 응답이 끊겨도 스윕이 이어서 처리한다(다른 팀 장애 기록)
            boolean changed = paymentState.settle(paymentId, PaymentStatus.FAILED, PaymentFailureCode.AMOUNT_MISMATCH,
                remote.transactionId(), now);
            log.warn("포트원 결제 금액 불일치 — FAILED(AMOUNT_MISMATCH), 환불 미완료로 둔다 paymentId={} bookingId={} 기대={} 실제={} {}",
                paymentId, payment.getBookingId(), payment.getAmount(),
                remote.amount() == null ? null : remote.amount().total(), remote.currency());
            if (changed) {
                tryRefund(paymentId, payment.getAmount(), "AMOUNT_MISMATCH", now);
            }
            return Outcome.MISMATCH;
        }
        if (remote.isFailed()) {
            PaymentFailureCode code = remote.looksUserCancelled()
                ? PaymentFailureCode.USER_CANCELED : PaymentFailureCode.PG_DECLINED;
            paymentState.settle(paymentId, PaymentStatus.FAILED, code, null, now);
            log.info("포트원 결제 실패 → FAILED({}) paymentId={} bookingId={}", code, paymentId, payment.getBookingId());
            return Outcome.FAILED;
        }
        if (remote.isCancelled()) {
            // 우리가 보기 전에 포트원에서 취소됐다(콘솔 취소 등). 승인 → 취소로 기록을 맞춘다
            paymentState.approve(paymentId, remote.transactionId(), remote.paidAtOr(now), now);
            paymentState.cancel(paymentId, PaymentCancelReason.STALE_PAYMENT, now);
            log.warn("포트원에서 이미 취소된 결제 → CANCELED paymentId={} bookingId={}", paymentId, payment.getBookingId());
            return Outcome.CANCELED;
        }
        return Outcome.NOT_YET;
    }

    /**
     * 결제창을 끝내지 않고 떠난 PROCESSING(스윕, {@code APP_PORTONE_PENDING_TIMEOUT} 지남). 포트원에 결과가 있으면 그대로
     * 반영하고, 없거나 미완료면 FAILED(PROCESSING_TIMEOUT). 포트원 응답이 없으면 이번 주기는 건너뛴다(돈이 잡혔는지 모른다).
     */
    public void closeAbandoned(Payment payment) {
        try {
            Outcome outcome = reconcileProcessing(payment);
            if (outcome == Outcome.NOT_YET) {
                paymentState.settle(payment.getPaymentId(), PaymentStatus.FAILED, PaymentFailureCode.PROCESSING_TIMEOUT,
                    null, clock.instant());
                log.info("결제창 미완료 → FAILED(PROCESSING_TIMEOUT) paymentId={} bookingId={}",
                    payment.getPaymentId(), payment.getBookingId());
            }
        } catch (PortOneUnavailableException e) {
            log.warn("포트원 응답이 없어 PROCESSING 정리를 미룬다 paymentId={}", payment.getPaymentId());
        }
    }

    /**
     * 늦은 결제 — 이미 FAILED로 끝낸 행(결제창 미완료로 닫은 뒤 사용자가 결제를 끝낸 경우)에 PAID가 왔다.
     * 포트원을 다시 조회해 PAID가 맞으면 <b>환불 미완료 표식만</b> 남긴다. 취소는 스윕이 보낸다 —
     * 웹훅이 여러 번 와도 취소 요청이 여러 번 나가지 않게(다른 팀 장애 기록).
     */
    public boolean noteLatePayment(Payment payment) {
        Optional<PortOnePayment> found = portOne.find(payment.getPaymentId());
        if (found.isEmpty() || !found.get().isPaid()) {
            return false;
        }
        boolean marked = paymentState.markRefundOwed(payment.getPaymentId(), found.get().transactionId(), clock.instant());
        if (marked) {
            log.warn("늦은 결제 — 끝난 예매의 결제가 PAID, 환불 미완료로 표시(스윕이 환불) paymentId={} bookingId={}",
                payment.getPaymentId(), payment.getBookingId());
        }
        return marked;
    }

    /**
     * 환불 미완료 행(FAILED·transactionId 있음·canceledAt 없음)을 끝까지 처리한다(스윕·웹훅 Cancelled).
     *
     * @return 환불이 확인돼 닫혔으면 true. false면 다음 주기에 다시 본다
     */
    public boolean settleRefund(Payment payment) {
        String paymentId = payment.getPaymentId();
        Instant now = clock.instant();
        try {
            Optional<PortOnePayment> found = portOne.find(paymentId);
            if (found.isEmpty() || found.get().isCancelled() || !found.get().isPaid()) {
                // 취소됐거나(환불 끝), 돈이 잡힌 적이 없다(환불할 것 없음) — 둘 다 끝낸다
                paymentState.confirmRefund(paymentId, PaymentCancelReason.STALE_PAYMENT, now);
                log.info("환불 미완료 정리 — 포트원 상태 {} → 끝 paymentId={}",
                    found.map(PortOnePayment::status).orElse("없음"), paymentId);
                return true;
            }
            return tryRefund(paymentId, payment.getAmount(), String.valueOf(payment.getFailureCode()), now);
        } catch (PortOneUnavailableException e) {
            log.warn("포트원 응답이 없어 환불 처리를 미룬다 paymentId={}", paymentId);
            return false;
        }
    }

    /**
     * 승인된 PORTONE 결제의 취소(BKG-07·ADR-100 환불). 포트원 전액 취소 → SUCCEEDED면 CANCELED.
     *
     * @throws com.seatsac.common.core.error.BusinessException 503 {@code CANCEL_PENDING}(REQUESTED·대조 미확정) ·
     *         503 {@code PORTONE_UNAVAILABLE}(응답 없음, 대조도 실패) — booking이 다시 부른다
     */
    public void cancelApproved(Payment payment, PaymentCancelReason reason) {
        String paymentId = payment.getPaymentId();
        Instant now = clock.instant();
        CancelOutcome outcome;
        try {
            outcome = portOne.cancel(paymentId, payment.getAmount(), reason.name());
        } catch (PortOneUnavailableException e) {
            // 취소가 됐는지 모른다 — 재호출하지 않고 대조한다. 대조도 안 되면 그대로 503
            if (confirmCancelledAtPortOne(paymentId)) {
                paymentState.cancel(paymentId, reason, now);
                return;
            }
            throw e;
        } catch (PortOneRejectedException e) {
            // 우리 기록은 APPROVED인데 포트원이 확정 거절(예: PAYMENT_NOT_PAID) — 기록 불일치. 사람이 봐야 한다
            log.error("포트원 취소 확정 거절 — 기록 불일치 paymentId={} bookingId={} type={}",
                paymentId, payment.getBookingId(), e.type());
            throw new PortOneUnavailableException("REJECTED_" + e.type());
        }
        switch (outcome) {
            case SUCCEEDED -> {
                paymentState.cancel(paymentId, reason, now);
                log.info("포트원 취소 완료 paymentId={} bookingId={} reason={}", paymentId, payment.getBookingId(), reason);
            }
            case MAYBE_DONE -> {
                if (confirmCancelledAtPortOne(paymentId)) {
                    paymentState.cancel(paymentId, reason, now);
                    return;
                }
                throw PaymentErrorCode.CANCEL_PENDING.exception();
            }
            case REQUESTED -> {
                // PG가 비동기로 처리 중 — 완료가 아니다. 웹훅 Transaction.Cancelled 또는 재요청·스윕 대조로 확정
                log.info("포트원 취소 처리 중(REQUESTED) — CANCELED로 바꾸지 않는다 paymentId={}", paymentId);
                throw PaymentErrorCode.CANCEL_PENDING.exception();
            }
        }
    }

    /** 포트원이 「취소됨」이라고 하면 true. 응답이 없으면 {@link PortOneUnavailableException} */
    public boolean confirmCancelledAtPortOne(String paymentId) {
        return portOne.find(paymentId).map(PortOnePayment::isCancelled).orElse(false);
    }

    /** 전액 환불 시도. SUCCEEDED면 환불 확인을 기록한다. 그 밖(REQUESTED·대조 필요·거절·응답 없음)은 false — 스윕이 다시 */
    private boolean tryRefund(String paymentId, int amount, String reason, Instant now) {
        try {
            CancelOutcome outcome = portOne.cancel(paymentId, amount, reason);
            if (outcome == CancelOutcome.SUCCEEDED) {
                paymentState.confirmRefund(paymentId, PaymentCancelReason.STALE_PAYMENT, now);
                log.info("환불 완료 paymentId={} reason={}", paymentId, reason);
                return true;
            }
            if (outcome == CancelOutcome.MAYBE_DONE && confirmCancelledAtPortOne(paymentId)) {
                paymentState.confirmRefund(paymentId, PaymentCancelReason.STALE_PAYMENT, now);
                return true;
            }
            log.info("환불 미확정({}) — 스윕이 다시 본다 paymentId={}", outcome, paymentId);
        } catch (PortOneRejectedException e) {
            log.warn("환불 거절 — 스윕이 다시 시도한다 paymentId={} type={}", paymentId, e.type());
        } catch (PortOneUnavailableException e) {
            log.warn("환불 응답 없음 — 스윕이 대조한다 paymentId={}", paymentId);
        }
        paymentState.touch(paymentId, now);
        return false;
    }
}

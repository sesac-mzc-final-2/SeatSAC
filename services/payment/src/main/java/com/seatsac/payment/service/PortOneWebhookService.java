package com.seatsac.payment.service;

import com.seatsac.payment.entity.Payment;
import com.seatsac.payment.enums.PaymentCancelReason;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 웹훅은 <b>대조만</b> 한다 — 포트원에 아무것도 보내지 않는다(다른 팀 장애 기록: 웹훅 대조가 취소를 또 보낸 사고).
 * 결제 상태는 본문이 아니라 {@link PortOneReconcileService}의 단건 조회로 다시 확인한다.
 * booking 쪽 반영(확정·환불)은 여기서 하지 않는다 — booking의 단건 조회 폴링과 만료 스캔이 Payment 상태를 읽는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortOneWebhookService {

    private final Clock clock;
    private final PaymentStateService paymentState;
    private final PortOneReconcileService reconcile;

    /**
     * @throws com.seatsac.payment.portone.PortOneUnavailableException 포트원 조회 실패 — 503으로 올려 포트원이 재발송하게 한다
     */
    public void handle(String type, String paymentId) {
        Optional<Payment> found = paymentState.find(paymentId);
        if (found.isEmpty()) {
            log.warn("웹훅의 결제를 모른다 — 무시 type={} paymentId={}", type, paymentId);
            return;
        }
        Payment payment = found.get();
        if (!payment.isPortOne()) {
            log.warn("MOCK 결제에 포트원 웹훅 — 무시 type={} paymentId={}", type, paymentId);
            return;
        }
        switch (type) {
            case "Transaction.Paid" -> {
                if (payment.isProcessing()) {
                    reconcile.reconcileProcessing(payment);
                } else if (payment.isFailed() && payment.getPgTransactionId() == null) {
                    reconcile.noteLatePayment(payment);   // 늦은 결제 — 표식만, 환불은 스윕
                }
            }
            case "Transaction.Failed" -> {
                if (payment.isProcessing()) {
                    reconcile.reconcileProcessing(payment);
                }
            }
            case "Transaction.Cancelled", "Transaction.PartialCancelled" -> {
                if (payment.isApproved() && reconcile.confirmCancelledAtPortOne(paymentId)) {
                    paymentState.cancel(paymentId, PaymentCancelReason.STALE_PAYMENT, clock.instant());
                    log.info("웹훅으로 취소 확정 paymentId={}", paymentId);
                } else if (payment.isRefundOwed() && reconcile.confirmCancelledAtPortOne(paymentId)) {
                    paymentState.confirmRefund(paymentId, PaymentCancelReason.STALE_PAYMENT, clock.instant());
                    log.info("웹훅으로 환불 확정 paymentId={}", paymentId);
                }
            }
            default -> log.debug("처리하지 않는 웹훅 type={} paymentId={}", type, paymentId);
        }
    }
}

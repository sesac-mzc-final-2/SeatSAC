package com.seatsac.payment.component;

import com.seatsac.payment.config.PortOneProperties;
import com.seatsac.payment.entity.Payment;
import com.seatsac.payment.enums.PgProvider;
import com.seatsac.payment.service.PaymentStateService;
import com.seatsac.payment.service.PortOneReconcileService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * 포트원 스윕 (ADR-112) — {@link StaleProcessingStartupRunner}가 기동 때 한 번, 그 뒤 1분마다 부른다.
 *
 * <ol>
 *   <li><b>결제창 미완료</b>: PROCESSING인 PORTONE 행이 {@code pendingTimeout}(7분 = 선점 300초 + 유예)을 넘기면
 *       포트원 조회로 확정 — PAID면 APPROVED(booking이 ADR-100대로 환불), 없거나 미완료면 FAILED(PROCESSING_TIMEOUT).
 *       MOCK의 30초 정리({@link StaleProcessingCloser})와 다른 기준·다른 질문(포트원에 물을 수 있다)</li>
 *   <li><b>환불 미완료</b>: FAILED인데 돈이 잡혀 있고({@code pg_transaction_id}) 환불 확인이 없는 행을 60초 넘게 손대지
 *       않았으면 대조·재시도. 다시 보내는 주체는 이 스윕과 booking 재요청뿐이다(웹훅은 대조만)</li>
 *   <li><b>늦은 결제 다시 보기</b>: 최근 30분 안에 FAILED로 닫혔고 돈이 잡힌 흔적이 없는 행을 5분마다 포트원에 다시 묻는다.
 *       닫은 뒤 사용자가 열어 둔 결제창에서 결제를 끝냈는데 웹훅({@code Transaction.Paid})이 오지 않으면 이 경로만이
 *       찾는다 → PAID면 환불 미완료 표식 → 2번이 환불</li>
 * </ol>
 *
 * <p>포트원이 꺼져 있으면({@code enabled=false}) 돌지 않는다. 포트원 응답이 없으면 해당 건은 다음 주기로 미룬다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortOneSweeper {

    static final int BATCH_SIZE = 100;
    /** 환불 미완료를 다시 보기까지 — 첫 요청이 아직 포트원에서 처리 중일 수 있어 바로 재시도하지 않는다 */
    static final Duration REFUND_RETRY_AFTER = Duration.ofSeconds(60);
    /** 이보다 오래 환불이 끝나지 않으면 사람이 봐야 한다 — 지표로 알린다 */
    static final Duration REFUND_UNRESOLVED_AFTER = Duration.ofMinutes(15);
    /** 늦은 결제 다시 보기 — 닫힌 뒤 이 기간 동안, 이 간격으로 */
    static final Duration LATE_PAYMENT_WINDOW = Duration.ofMinutes(30);
    static final Duration LATE_PAYMENT_INTERVAL = Duration.ofMinutes(5);

    private final Clock clock;
    private final PortOneProperties props;
    private final PaymentStateService paymentState;
    private final PortOneReconcileService reconcile;
    private final MeterRegistry meterRegistry;

    /** 마지막 늦은 결제 다시 보기 시각 — 파드마다 따로(여러 파드가 겹쳐도 표식은 조건부 UPDATE라 한 번만) */
    private volatile Instant lastLatePaymentCheck = Instant.EPOCH;

    /** @return 닫은 PROCESSING 수 + 끝낸 환불 수 */
    public int sweep() {
        if (!props.enabled()) {
            return 0;
        }
        int handled = closeAbandoned() + settleRefunds();
        Instant now = clock.instant();
        if (!now.isBefore(lastLatePaymentCheck.plus(LATE_PAYMENT_INTERVAL))) {
            lastLatePaymentCheck = now;
            recheckLatePayments();
        }
        return handled;
    }

    /** @return 환불 미완료로 표시한 늦은 결제 수 */
    public int recheckLatePayments() {
        Instant since = clock.instant().minus(LATE_PAYMENT_WINDOW);
        List<Payment> recent = paymentState.findRecentlyFailedWithoutTransaction(since, PageRequest.ofSize(BATCH_SIZE));
        int marked = 0;
        for (Payment payment : recent) {
            try {
                if (reconcile.noteLatePayment(payment)) {
                    marked++;
                }
            } catch (com.seatsac.payment.portone.PortOneUnavailableException e) {
                log.warn("포트원 응답이 없어 늦은 결제 확인을 다음으로 미룬다 paymentId={}", payment.getPaymentId());
            }
        }
        if (marked > 0) {
            log.warn("웹훅 없이 찾은 늦은 결제 — 환불 미완료로 표시 marked={}", marked);
        }
        return marked;
    }

    int closeAbandoned() {
        Instant threshold = clock.instant().minus(props.pendingTimeout());
        List<String> ids = paymentState.findStaleProcessingIds(PgProvider.PORTONE, threshold, PageRequest.ofSize(BATCH_SIZE));
        int handled = 0;
        for (String paymentId : ids) {
            Payment payment = paymentState.find(paymentId).orElse(null);
            if (payment == null || !payment.isProcessing()) {
                continue;
            }
            reconcile.closeAbandoned(payment);
            handled++;
        }
        if (handled > 0) {
            log.info("결제창 미완료 PORTONE 결제 정리 handled={}", handled);
        }
        return handled;
    }

    int settleRefunds() {
        Instant now = clock.instant();
        List<Payment> owed = paymentState.findRefundOwed(now.minus(REFUND_RETRY_AFTER), PageRequest.ofSize(BATCH_SIZE));
        int settled = 0;
        for (Payment payment : owed) {
            if (reconcile.settleRefund(payment)) {
                settled++;
            } else if (Duration.between(payment.getCreatedAt(), now).compareTo(REFUND_UNRESOLVED_AFTER) > 0) {
                meterRegistry.counter("payment_refund_unresolved_total").increment();
                log.error("환불 미완료가 오래 남아 있다 — 포트원 콘솔과 대조 필요 paymentId={} bookingId={} failureCode={}",
                    payment.getPaymentId(), payment.getBookingId(), payment.getFailureCode());
            }
        }
        if (!owed.isEmpty()) {
            log.info("환불 미완료 처리 found={} settled={}", owed.size(), settled);
        }
        return settled;
    }
}

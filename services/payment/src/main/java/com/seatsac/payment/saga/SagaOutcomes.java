package com.seatsac.payment.saga;

import com.seatsac.payment.enums.PaymentCancelReason;
import com.seatsac.payment.enums.PaymentFailureCode;
import com.seatsac.payment.enums.PaymentStatus;
import com.seatsac.payment.service.PaymentStateService;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * payment가 <b>스스로</b> 결제를 닫을 때의 결과 이벤트(D7, 설계 02 5장) — 포트원 스윕(결제창 미완료 정리)·웹훅·MOCK 기동 정리.
 * 체크아웃 중 booking은 payment를 조회하지 않으므로, 상태가 바뀐 결제가 saga 결제({@code saga_payment} 행 있음)면
 * {@code payment.approved}·{@code payment.declined}를 <b>상태 전이와 같은 트랜잭션</b>에 outbox로 넣는다 — 전이는 됐는데
 * 이벤트가 없는 경우가 생기지 않는다. 동기 경로 결제는 행이 없어 지금과 같다.
 *
 * <p>명령 처리(확인 명령의 verify)가 바꾼 상태는 여기서 내지 않는다({@code notify=false}) — 명령 처리가 결과를 직접 보낸다.
 * 이 이벤트가 있어야 해제 명령이 사라진 경우(형식 오류 삭제·DLQ)에도 만료 뒤 승인이 booking에 닿아 해제가 다시 온다(D31·D37).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SagaOutcomes {

    /** 같은 예매로 다시 결제할 수 있는 실패 — 포트원 FAILED(돈이 안 빠짐). MOCK 거절·금액 불일치·만료는 아니다(06) */
    static final Set<PaymentFailureCode> RETRYABLE = Set.of(
        PaymentFailureCode.USER_CANCELED, PaymentFailureCode.PG_DECLINED);

    private final PaymentStateService paymentState;
    private final SagaPaymentRepository sagaPayments;
    private final ReplyOutboxRepository outbox;
    private final SagaMessages messages;

    /** PROCESSING → FAILED. 바뀌었고 saga 결제면 {@code payment.declined} */
    @Transactional
    public boolean fail(String paymentId, PaymentFailureCode code, String pgTransactionId, Instant now, boolean notify) {
        boolean changed = paymentState.settle(paymentId, PaymentStatus.FAILED, code, pgTransactionId, now);
        if (changed && notify) {
            saga(paymentId).ifPresent(s -> publish(s, messages.declined(s, code.name(), RETRYABLE.contains(code), now), now));
        }
        return changed;
    }

    /** PROCESSING → APPROVED. 바뀌었고 saga 결제면 {@code payment.approved} */
    @Transactional
    public boolean approve(String paymentId, int amount, String pgTransactionId, Instant approvedAt, Instant now,
                           boolean notify) {
        boolean changed = paymentState.approve(paymentId, pgTransactionId, approvedAt, now);
        if (changed && notify) {
            saga(paymentId).ifPresent(s -> publish(s,
                messages.approved(s, amount, approvedAt, pgTransactionId, now), now));
        }
        return changed;
    }

    /** 포트원에서 이미 취소된 결제 — 승인 → 취소로 기록을 맞춘다. 돈은 돌아갔으므로 확정에 쓰지 않게 {@code declined(CANCELED)} */
    @Transactional
    public boolean cancelledAtPortOne(String paymentId, String pgTransactionId, Instant approvedAt, Instant now,
                                      boolean notify) {
        boolean changed = paymentState.approve(paymentId, pgTransactionId, approvedAt, now);
        paymentState.cancel(paymentId, PaymentCancelReason.STALE_PAYMENT, now);
        if (changed && notify) {
            saga(paymentId).ifPresent(s -> publish(s, messages.declined(s, "CANCELED", false, now), now));
        }
        return changed;
    }

    private Optional<SagaPayment> saga(String paymentId) {
        return sagaPayments.findById(paymentId);
    }

    private void publish(SagaPayment saga, SagaMessages.Reply reply, Instant now) {
        // 결제마다 상태 전이는 한 번(조건부 UPDATE)이라 결과 종류별로 한 행 — 키 「payment:{paymentId}」(44자)
        outbox.save(ReplyOutbox.pending(reply, saga.getBookingId(), "payment:" + saga.getPaymentId(), now));
        log.info("saga 결제를 payment가 닫음 — 결과 이벤트 type={} bookingId={} checkoutId={}",
            reply.type(), saga.getBookingId(), saga.getCheckoutId());
    }
}

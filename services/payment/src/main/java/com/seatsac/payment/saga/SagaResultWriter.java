package com.seatsac.payment.saga;

import com.seatsac.payment.enums.PaymentCancelReason;
import com.seatsac.payment.service.PaymentStateService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 명령 처리의 마지막 한 트랜잭션(D27): inbox INSERT + saga 표시 + (해제면 상태 전이) + 결과 outbox INSERT.
 *
 * <p>inbox를 먼저 따로 커밋하지 않는 이유: 그 뒤 처리 중 죽으면 재배달이 inbox에 막혀 결과가 영영 나가지 않는다.
 * 여기서 한 번에 커밋하고, 실패하면 전부 롤백 → 메시지를 지우지 않아 재배달된다. 같은 메시지가 동시에 두 번 처리되면
 * inbox PK(또는 outbox 유일 키)가 한쪽을 막는다({@link org.springframework.dao.DataIntegrityViolationException}).
 */
@Service
@RequiredArgsConstructor
public class SagaResultWriter {

    private final ProcessedMessageRepository inbox;
    private final SagaPaymentRepository sagaPayments;
    private final ReplyOutboxRepository outbox;
    private final PaymentStateService paymentState;

    /** 준비·청구·확인 — saga 결제 표시(처음 한 번)와 결과 */
    @Transactional
    public void record(SagaMessages.Command cause, boolean markSagaPayment, SagaMessages.Reply reply, Instant now) {
        inbox.saveAndFlush(ProcessedMessage.of(cause.eventId(), cause.type(), now));
        if (markSagaPayment && !sagaPayments.existsById(cause.paymentId())) {
            sagaPayments.save(SagaPayment.of(cause.paymentId(), cause.checkoutId(), cause.bookingId(), now));
        }
        if (reply != null) {
            outbox.save(ReplyOutbox.pending(reply, cause.bookingId(), cause.eventId(), now));
        }
    }

    /**
     * 해제(D16) — 결제 행 상태와 상관없이 「예매 없음」을 표시한다. 이미 승인된 결제는 같은 트랜잭션에서 환불 대상으로 바꾼다:
     * PORTONE은 FAILED(BOOKING_EXPIRED) + 기존 {@code pg_transaction_id}(스윕이 포트원 전액 취소), MOCK은 돈이 없어 바로 CANCELED.
     * 포트원 취소를 여기서 부르지 않는다(멱등 키가 없다 — 보내는 곳은 스윕 하나, D6)
     *
     * @return 승인된 결제를 이번에 놓았으면 true (결과 {@code payment.declined{BOOKING_EXPIRED}}를 낸다)
     */
    @Transactional
    public boolean recordRelease(SagaMessages.Command cause, boolean portOne, boolean approved,
                                 java.util.function.Function<Boolean, SagaMessages.Reply> replyWhenReleased,
                                 Instant now) {
        inbox.saveAndFlush(ProcessedMessage.of(cause.eventId(), cause.type(), now));
        if (sagaPayments.existsById(cause.paymentId())) {
            sagaPayments.markReleased(cause.paymentId(), now);
        } else {
            // 해제가 준비·청구 명령보다 먼저 왔다(표준 큐 순서 역전). 표시를 먼저 남겨 늦은 명령이 결제를 만들지 않게 한다
            SagaPayment row = SagaPayment.of(cause.paymentId(), cause.checkoutId(), cause.bookingId(), now);
            sagaPayments.saveAndFlush(row);
            sagaPayments.markReleased(cause.paymentId(), now);
        }
        boolean releasedApproved = approved && (portOne
            ? paymentState.releaseApprovedPortOne(cause.paymentId(), now)
            : paymentState.cancel(cause.paymentId(), PaymentCancelReason.HOLD_EXPIRED, now));
        SagaMessages.Reply reply = replyWhenReleased.apply(releasedApproved);
        if (reply != null) {
            outbox.save(ReplyOutbox.pending(reply, cause.bookingId(), cause.eventId(), now));
        }
        return releasedApproved;
    }
}

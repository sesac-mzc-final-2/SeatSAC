package com.seatsac.booking.checkout;

import com.seatsac.booking.checkout.coupon.CouponPort;
import java.time.Instant;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결과·스윕 처리의 마지막 한 트랜잭션(D27) — inbox + saga 조건부 전이 + (쿠폰·해제) 명령.
 * 예매 반영(확정·좌석 해제)은 기존 메서드가 이보다 먼저 자기 트랜잭션으로 끝낸다 — 둘 다 조건부라 재배달 재처리가 안전하다.
 *
 * <p>{@code messageId}가 null이면 스윕이 부른 것이다(inbox 없음).
 */
@Service
@RequiredArgsConstructor
public class CheckoutResultTx {

    private final ProcessedMessageRepository inbox;
    private final CheckoutSagaRepository sagas;
    private final CheckoutCommands commands;
    private final CouponPort couponPort;

    private void received(String messageId, String messageType, Instant now) {
        if (messageId != null) {
            inbox.saveAndFlush(ProcessedMessage.of(messageId, messageType, now));
        }
    }

    /** 결과를 받기만 했다(늦은 결과·상관없는 상태) — 같은 메시지를 다시 처리하지 않게 inbox만 남긴다 */
    @Transactional
    public void ignore(String messageId, String messageType, Instant now) {
        received(messageId, messageType, now);
    }

    @Transactional
    public boolean readyToPay(String messageId, String messageType, CheckoutSaga saga, String checkoutJson,
                              Instant deadline, Instant now) {
        received(messageId, messageType, now);
        return sagas.readyToPay(saga.getCheckoutId(), checkoutJson, deadline, now) == 1;
    }

    /** 확정 — 쿠폰 사용 명령을 함께 */
    @Transactional
    public boolean confirmed(String messageId, String messageType, CheckoutSaga saga,
                             Collection<CheckoutState> expected, Instant now) {
        received(messageId, messageType, now);
        if (sagas.finish(saga.getCheckoutId(), expected, CheckoutState.CONFIRMED, "applied", null, null, null, now) == 0) {
            return false;
        }
        if (saga.getCouponIssueId() != null) {
            couponPort.use(saga.getCheckoutId(), saga.getBookingId(), saga.getCouponIssueId());
        }
        return true;
    }

    /**
     * 끝(FAILED·PAYMENT_RETRYABLE·EXPIRED). 쿠폰은 예약 여부를 몰라도 반환 명령을 보낸다(멱등).
     *
     * @param releasePayment 결제가 붙어 있으면 payment에 해제 명령(만료·돈 전 단계 시간 초과) — 늦은 PAID는 payment가 환불한다
     */
    @Transactional
    public boolean finish(String messageId, String messageType, CheckoutSaga saga, Collection<CheckoutState> expected,
                          CheckoutState next, String step, String failureCode, String failureReason,
                          String refundNotice, boolean releasePayment, Instant now) {
        received(messageId, messageType, now);
        if (sagas.finish(saga.getCheckoutId(), expected, next, step, failureCode, failureReason, refundNotice, now) == 0) {
            return false;
        }
        if (saga.getCouponIssueId() != null) {
            couponPort.release(saga.getCheckoutId(), saga.getBookingId(), saga.getCouponIssueId());
        }
        if (releasePayment && saga.getPaymentId() != null) {
            commands.release(saga, saga.getPaymentId(), "expire", now);
        }
        return true;
    }

    /** 처리권을 잡는다(진행 중 → COMPENSATING). 1행인 쪽만 보상을 이어간다 */
    @Transactional
    public boolean claimCompensation(CheckoutSaga saga, Collection<CheckoutState> expected, Instant now) {
        return sagas.advance(saga.getCheckoutId(), expected, CheckoutState.COMPENSATING, "compensating", null, now) == 1;
    }

    @Transactional
    public void noteUnknown(String messageId, String messageType, CheckoutSaga saga, String reason, Instant deadline,
                            Instant now) {
        received(messageId, messageType, now);
        sagas.noteUnknown(saga.getCheckoutId(), CheckoutState.MONEY_MAY_MOVE, reason, deadline, now);
    }

    /**
     * 만료된 saga에 늦은 결과 — 환불 안내를 바꾼다. {@code releasePaymentId}가 있으면 그 결제에 해제 명령을 한 번 더
     * (payment가 해제 명령을 못 받고 승인한 경우, D16). 키는 이 결과의 eventId라 서로 다른 늦은 승인마다 한 번씩 나간다
     */
    @Transactional
    public void refundInProgress(String messageId, String messageType, CheckoutSaga saga, String releasePaymentId,
                                 Instant now) {
        received(messageId, messageType, now);
        sagas.noteRefund(saga.getCheckoutId(), "REFUND_IN_PROGRESS", now);
        if (releasePaymentId != null) {
            commands.release(saga, releasePaymentId, messageId, now);
        }
    }

    /**
     * 끝난 saga(실패·재결제 대기 등)에 온 승인 — 확정에 쓰지 않는 돈이다. 해제 명령을 보내 payment가 환불하게 한다(06 5장).
     * 환불 안내는 만료 화면에만 있어 바꾸지 않는다
     */
    @Transactional
    public void releaseLate(String messageId, String messageType, CheckoutSaga saga, String paymentId, Instant now) {
        received(messageId, messageType, now);
        commands.release(saga, paymentId, messageId, now);
    }

    /** 스윕 재확인 — 처리권을 잡은 파드만 다음 확인 명령을 보낸다 */
    @Transactional
    public boolean retryVerification(CheckoutSaga saga, Instant deadline, Instant now) {
        int next = saga.getVerifyAttempt() + 1;
        if (sagas.claimVerifyRetry(saga.getCheckoutId(), saga.getState(), saga.getVerifyAttempt(),
            saga.getStepDeadlineAt(), deadline, now) == 0) {
            return false;
        }
        commands.verification(saga, next, now);
        return true;
    }
}

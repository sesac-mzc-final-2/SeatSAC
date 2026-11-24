package com.seatsac.booking.checkout;

import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.booking.service.BookingPaymentResultApplier;
import com.seatsac.booking.service.BookingRepositoryReader;
import com.seatsac.common.core.trace.TraceIds;
import com.seatsac.common.core.trace.TraceKeys;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * 체크아웃 스윕(설계 01 5-4) — 1초마다. preview 파드에서도 돈다(모든 전이가 조건부라 겹쳐도 한 번).
 *
 * <ol>
 *   <li>진행 중인데 예매가 PENDING을 벗어났다 → 예매 상태를 따라 saga를 닫는다(D23). 만료면 EXPIRED + 환불 안내 +
 *       쿠폰 반환 + {@code payment.release-requested}(결제가 실제로 됐는지와 환불은 payment가 맡는다)</li>
 *   <li>돈이 움직이기 전 단계(쿠폰 예약·결제 준비)의 기한 10초 초과 → 보상 FAILED(STEP_TIMEOUT)</li>
 *   <li>CHARGING·VERIFYING 재확인 기한 → 확인 명령 재발송(D21 간격, 선점 만료까지). 보상하지 않는다(D4)</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckoutSweeper {

    private static final int BATCH = 50;
    private static final Set<CheckoutState> BEFORE_MONEY = EnumSet.of(CheckoutState.COUPON_RESERVING,
        CheckoutState.PAYMENT_PREPARING);
    private static final Set<CheckoutState> DUE = EnumSet.of(CheckoutState.COUPON_RESERVING,
        CheckoutState.PAYMENT_PREPARING, CheckoutState.CHARGING, CheckoutState.VERIFYING);

    private final CheckoutSagaRepository sagas;
    private final CheckoutResultTx tx;
    private final BookingRepositoryReader bookingReader;
    private final BookingPaymentResultApplier resultApplier;
    private final CheckoutIntervals intervals;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    /** 한 바퀴 — 예외를 밖으로 내지 않는다(스케줄 주기가 멈추지 않게). 건별로 감싼다 */
    public int sweepOnce() {
        MDC.put(TraceKeys.MDC_TRACE_ID, TraceIds.newId());
        int handled = 0;
        try {
            for (CheckoutSaga saga : sagas.findActiveWithClosedBooking(CheckoutState.ACTIVE, PageRequest.of(0, BATCH))) {
                handled += safely(saga, () -> followBooking(saga));
            }
            for (CheckoutSaga saga : sagas.findDue(DUE, clock.instant(), PageRequest.of(0, BATCH))) {
                handled += safely(saga, () -> due(saga));
            }
        } catch (RuntimeException e) {
            log.warn("체크아웃 스윕 실패 — 다음 주기에 다시 본다: {}", e.getMessage(), e);
        } finally {
            MDC.remove(TraceKeys.MDC_TRACE_ID);
        }
        return handled;
    }

    private int safely(CheckoutSaga saga, java.util.function.BooleanSupplier work) {
        try {
            return work.getAsBoolean() ? 1 : 0;
        } catch (RuntimeException e) {
            log.error("체크아웃 스윕 한 건 실패 — 다음 건으로 checkoutId={}", saga.getCheckoutId(), e);
            return 0;
        }
    }

    /** D23 — 예매는 이미 닫혔다. saga만 그 상태를 따라 닫는다 */
    private boolean followBooking(CheckoutSaga saga) {
        Booking booking = bookingReader.findById(saga.getBookingId()).orElse(null);
        if (booking == null) {
            return false;
        }
        Instant now = clock.instant();
        return switch (booking.getStatus()) {
            case CONFIRMED -> tx.confirmed(null, null, saga, CheckoutState.ACTIVE, now);
            case PAYMENT_FAILED -> tx.finish(null, null, saga, CheckoutState.ACTIVE, CheckoutState.FAILED,
                "payment_declined", "PAYMENT_DECLINED", null, null, false, now);
            case EXPIRED, CANCELED -> {
                boolean paying = saga.getPaymentId() != null;
                boolean closed = tx.finish(null, null, saga, CheckoutState.ACTIVE, CheckoutState.EXPIRED, "expired",
                    "HOLD_EXPIRED", paying ? "PAYMENT_RESULT_UNKNOWN" : null, paying ? "WILL_REFUND_IF_PAID" : null,
                    true, now);
                if (closed) {
                    meterRegistry.counter("booking_checkout_total", "result", "expired").increment();
                    meterRegistry.counter("booking_checkout_compensation_total", "reason", "hold_expired").increment();
                    log.info("선점 만료로 체크아웃을 닫았다 checkoutId={} bookingId={} 해제명령={}", saga.getCheckoutId(),
                        saga.getBookingId(), paying);
                }
                yield closed;
            }
            case PENDING -> false;
        };
    }

    private boolean due(CheckoutSaga saga) {
        Booking booking = bookingReader.findById(saga.getBookingId()).orElse(null);
        if (booking == null || booking.getStatus() != BookingStatus.PENDING) {
            return false;   // 위 followBooking이 닫는다
        }
        Instant now = clock.instant();
        if (BEFORE_MONEY.contains(saga.getState())) {
            return stepTimeout(saga, booking, now);
        }
        if (!now.isBefore(booking.getExpiresAt())) {
            return false;   // 선점 만료 — 만료 처리가 곧 예매를 닫고 followBooking이 saga를 닫는다
        }
        Instant next = intervals.nextVerify(saga.getVerifyAttempt() + 1, saga.getLastUnknown(), booking.getExpiresAt(),
            now);
        if (tx.retryVerification(saga, next, now)) {
            meterRegistry.counter("booking_checkout_verify_retry_total").increment();
            return true;
        }
        return false;
    }

    /** 돈이 움직이기 전 단계의 시간 초과 — 처리권 → 좌석 해제 → FAILED(STEP_TIMEOUT) */
    private boolean stepTimeout(CheckoutSaga saga, Booking booking, Instant now) {
        if (!tx.claimCompensation(saga, EnumSet.of(saga.getState()), now)) {
            return false;
        }
        resultApplier.applyDeclined(booking);
        boolean done = tx.finish(null, null, saga, EnumSet.of(CheckoutState.COMPENSATING), CheckoutState.FAILED,
            "step_timeout", "STEP_TIMEOUT", saga.getStep(), null, true, clock.instant());
        if (done) {
            meterRegistry.counter("booking_checkout_total", "result", "failed").increment();
            meterRegistry.counter("booking_checkout_compensation_total", "reason", "step_timeout").increment();
            log.warn("체크아웃 단계 시간 초과 — 보상했다 checkoutId={} step={}", saga.getCheckoutId(), saga.getStep());
        }
        return done;
    }

    /** 테스트·로그용 */
    List<CheckoutSaga> dueNow() {
        return sagas.findDue(DUE, clock.instant(), PageRequest.of(0, BATCH));
    }
}

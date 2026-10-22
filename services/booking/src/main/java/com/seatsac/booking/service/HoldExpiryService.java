package com.seatsac.booking.service;

import com.seatsac.booking.component.SeatEventPublisher;
import com.seatsac.booking.component.SeatHoldStrategy;
import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.SeatEventType;
import com.seatsac.booking.service.BookingStateService.ExpiredHold;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * BKG-04 선점 만료 — 세 경로(keyspace 빠른 경로 · 만료 장부 1초 · RDS 10초)가 모두 이곳을 지난다.
 *
 * <p>순서가 핵심이다 — <b>조건부 UPDATE(1행일 때만) → 발행 → {@code release.lua} → 장부에서 삭제</b>.
 * 장부 삭제를 마지막에 두는 이유(C13): 먼저 지우면 그 직후 파드가 죽었을 때 아무 경로도 그 선점을
 * 다시 찾지 못해 영원히 {@code PENDING}으로 남는다. 반대로 순서를 지키면 어느 지점에서 멈춰도
 * 다음 주기가 이어받고, 조건부 UPDATE가 중복 처리를 막는다.
 *
 * <p>결제를 요청한 예매({@code current_payment_id} 있음)는 이번 주기를 <b>보류</b>한다.
 * 결제 결과가 예매 상태를 정하므로 만료가 끼어들면 「승인된 결제 + 만료된 예매」가 생긴다.
 * Payment 상태를 조회해 승인된 결제를 취소(`STALE_PAYMENT`)하는 처리는 3.2.6에서 붙인다.
 */
@Slf4j
@Service
public class HoldExpiryService {

    private final Clock clock;
    private final BookingRepositoryReader bookingReader;
    private final BookingStateService bookingState;
    private final SeatHoldStrategy seatHold;
    private final SeatEventPublisher eventPublisher;
    private final Timer expiryLag;

    public HoldExpiryService(Clock clock, BookingRepositoryReader bookingReader,
                             BookingStateService bookingState, SeatHoldStrategy seatHold,
                             SeatEventPublisher eventPublisher, MeterRegistry meterRegistry) {
        this.clock = clock;
        this.bookingReader = bookingReader;
        this.bookingState = bookingState;
        this.seatHold = seatHold;
        this.eventPublisher = eventPublisher;
        this.expiryLag = Timer.builder("booking_hold_expiry_lag")
            .description("만료 시각과 실제 반영 시각의 차이 (목표 2초 이내)")
            .publishPercentileHistogram()
            .register(meterRegistry);
    }

    /** 만료 시도 결과 — 부르는 쪽이 장부(`hold-expiry`)에서 멤버를 지울지 정하는 근거다 */
    public enum Outcome {

        /** 내가 만료시켰다 → 장부에서 지운다 */
        EXPIRED,

        /** 이미 다른 경로·파드가 처리했다(확정·해제·만료) → 장부에서 지운다 */
        ALREADY_SETTLED,

        /** 결제 중이라 보류했다 → <b>장부에 남겨 둔다</b>. 다음 주기에 다시 본다 */
        DEFERRED
    }

    /**
     * 예매 하나를 만료시킨다. 이미 다른 경로·파드가 처리했거나 결제 중이면 아무것도 하지 않는다.
     */
    public Outcome expire(Long bookingId) {
        Instant now = clock.instant();
        Optional<Booking> pending = bookingReader.findById(bookingId);
        if (pending.isEmpty()) {
            return Outcome.ALREADY_SETTLED;
        }
        if (pending.get().getCurrentPaymentId() != null) {
            // 결제 결과가 상태를 정한다. 장부에서 지우지 않아야 다음 주기에 다시 본다
            log.debug("결제 중인 예매는 만료를 보류한다 bookingId={}", bookingId);
            return Outcome.DEFERRED;
        }

        Optional<ExpiredHold> expired = bookingState.expireHold(bookingId, now);
        if (expired.isEmpty()) {
            return Outcome.ALREADY_SETTLED;
        }
        ExpiredHold hold = expired.get();

        // 커밋 후: 발행 → Valkey 정리 → (부르는 쪽이) 장부 삭제
        eventPublisher.publish(SeatEventType.EXPIRED, hold.screeningId(), hold.seatIds(), now);
        releaseKeys(hold);
        expiryLag.record(Duration.between(hold.expiresAt(), now).toMillis(), TimeUnit.MILLISECONDS);
        log.info("선점 만료 bookingId={} screeningId={} seats={} lagMs={}", hold.bookingId(),
            hold.screeningId(), hold.seatIds().size(), Duration.between(hold.expiresAt(), now).toMillis());
        return Outcome.EXPIRED;
    }

    /**
     * 좌석 키는 TTL 300초로 대개 이미 사라졌지만, RDS 스캔 경로에서는 시계 차이로 아직 살아 있을
     * 수 있다 — holdId가 내 것일 때만 지우는 {@code release.lua}로 정리한다.
     */
    private void releaseKeys(ExpiredHold hold) {
        try {
            seatHold.release(hold.screeningId(), hold.userId(), hold.holdId(), hold.seatIds());
        } catch (DataAccessException e) {
            // 예매는 이미 EXPIRED로 커밋됐다. 남은 키는 TTL이 치운다
            log.warn("만료 후 Valkey 정리 실패 bookingId={} screeningId={}",
                hold.bookingId(), hold.screeningId(), e);
        }
    }
}

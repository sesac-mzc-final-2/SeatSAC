package com.seatsac.coupon.saga;

import com.seatsac.coupon.entity.CouponEvent;
import com.seatsac.coupon.entity.CouponIssue;
import com.seatsac.coupon.entity.CouponIssueState;
import com.seatsac.coupon.entity.CouponReservation;
import com.seatsac.coupon.entity.ReservationState;
import com.seatsac.coupon.repository.CouponEventRepository;
import com.seatsac.coupon.repository.CouponIssueRepository;
import com.seatsac.coupon.repository.CouponReservationRepository;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 명령 하나 = 한 트랜잭션: inbox INSERT + 쿠폰·예약 행 전이 + (예약이면) 결과 outbox INSERT(22-4).
 * 표준 큐라 순서가 바뀌거나 두 번 온다 — {@code coupon_reservation}(체크아웃 1건 = 1행)이 도착 순서와 상관없이 결과를 같게 만든다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponCommandTx {

    /** 예약 거절 사유(계약 coupon.reservation-rejected reason) */
    static final String NOT_OWNER = "NOT_OWNER";
    static final String NOT_ISSUED = "NOT_ISSUED";
    static final String EXPIRED = "EXPIRED";
    static final String ALREADY_RELEASED = "ALREADY_RELEASED";

    private final CouponMessages messages;
    private final ProcessedMessageRepository inbox;
    private final ReplyOutboxRepository outbox;
    private final CouponReservationRepository reservations;
    private final CouponIssueRepository issues;
    private final CouponEventRepository events;

    /** 처리 결과(지표 라벨 rejected를 가르려고) */
    public enum Outcome { OK, REJECTED }

    /**
     * 예약 — 같은 checkoutId에 이미 결과가 있으면 그 결과를 다시 보낸다(쿠폰을 두 번 잡지 않음). 없으면 본인·ISSUED·만료 전
     * 조건부 UPDATE → RESERVED, 아니면 거절. 거절도 행으로 남긴다.
     * <p>같은 예매의 이전 체크아웃이 이 쿠폰을 아직 RESERVED로 잡고 있으면 넘겨받는다 — 재결제(PAYMENT_RETRYABLE) 뒤 이전 체크아웃의
     * 반환 명령보다 새 예약이 먼저 처리돼도 NOT_ISSUED로 거절하지 않게(#206 리뷰). booking은 예매당 진행 중 체크아웃이 1건이라
     * (UK active_booking_id) 새 체크아웃이 왔다면 이전 것은 끝났다. 늦게 온 이전 체크아웃의 반환은 그 행이 RELEASED라 무시된다.
     */
    @Transactional
    public Outcome reserve(CouponMessages.Command command, Instant now) {
        received(command, now);
        Optional<CouponReservation> existing = reservations.findById(command.checkoutId());
        if (existing.isPresent()) {
            return replyFor(command, existing.get(), now);
        }
        CouponIssue issue = issues.findById(command.couponIssueId()).orElse(null);
        if (issue == null || !issue.getUserId().equals(command.userId())) {
            return reject(command, NOT_OWNER, now);
        }
        if (takeOverFromSameBooking(command, issue, now)) {
            return Outcome.OK;
        }
        if (issues.reserve(issue.getCouponIssueId(), command.userId(), now) == 0) {
            // 조건에 안 맞았다 — 이미 사용·예약 중(다른 체크아웃과 동시 예약이면 하나만 성공)이거나 종료
            boolean expired = issue.getState() == CouponIssueState.EXPIRED || !now.isBefore(issue.getExpiresAt());
            return reject(command, expired ? EXPIRED : NOT_ISSUED, now);
        }
        CouponEvent event = events.findById(issue.getEventId()).orElseThrow(
            () -> new IllegalStateException("쿠폰의 이벤트가 없다 eventId=" + issue.getEventId()));
        reservations.saveAndFlush(CouponReservation.reserved(command.checkoutId(), issue, event.getDiscountRate(),
            command.bookingId(), now));
        publish(command, messages.reserved(command, issue.getCouponIssueId(), event.getDiscountRate(),
            event.getEventId(), now), now);
        log.info("쿠폰 예약 checkoutId={} couponIssueId={} bookingId={}", command.checkoutId(),
            issue.getCouponIssueId(), command.bookingId());
        return Outcome.OK;
    }

    /** 사용 — RESERVED → USED. 결과 메시지 없음. 이미 USED·예약 기록 없음·반환됨이면 무시 */
    @Transactional
    public void use(CouponMessages.Command command, Instant now) {
        received(command, now);
        CouponReservation row = reservations.findById(command.checkoutId()).orElse(null);
        if (row == null || row.getState() != ReservationState.RESERVED) {
            if (row == null || row.getState() != ReservationState.USED) {
                log.warn("사용 명령 — 예약 행이 RESERVED가 아니다, 무시 checkoutId={} state={}", command.checkoutId(),
                    row == null ? null : row.getState());
            }
            return;
        }
        if (reservations.moveFromReserved(row.getCheckoutId(), ReservationState.USED, now) == 0) {
            // 다른 경로(동시 반환·사용)가 먼저 옮겼다 — 쿠폰 행은 그쪽 결과를 따른다(남의 예약을 건드리지 않게)
            log.info("사용 명령 — 예약 행이 이미 옮겨졌다, 무시 checkoutId={}", row.getCheckoutId());
            return;
        }
        if (issues.use(row.getCouponIssueId(), now) == 0) {
            log.warn("사용 명령 — 쿠폰이 RESERVED가 아니다 checkoutId={} couponIssueId={}", row.getCheckoutId(),
                row.getCouponIssueId());
        }
        log.info("쿠폰 사용 checkoutId={} couponIssueId={}", row.getCheckoutId(), row.getCouponIssueId());
    }

    /**
     * 반환 — 예약 행이 RESERVED면 쿠폰 ISSUED(종료 뒤면 EXPIRED) + 행 RELEASED. 행이 없으면 RELEASED를 먼저 기록한다
     * (늦게 온 예약을 거절하려고, 쿠폰은 그대로). 이미 RELEASED·거절이면 무시, USED면 무시 + 경고. 결과 메시지 없음
     */
    @Transactional
    public void release(CouponMessages.Command command, Instant now) {
        received(command, now);
        CouponReservation row = reservations.findById(command.checkoutId()).orElse(null);
        if (row == null) {
            reservations.saveAndFlush(CouponReservation.releasedFirst(command.checkoutId(), command.couponIssueId(),
                command.bookingId(), now));
            log.info("반환이 예약보다 먼저 왔다 — RELEASED로 먼저 기록 checkoutId={}", command.checkoutId());
            return;
        }
        switch (row.getState()) {
            case RESERVED -> {
                if (reservations.moveFromReserved(row.getCheckoutId(), ReservationState.RELEASED, now) == 0) {
                    log.info("반환 명령 — 예약 행이 이미 옮겨졌다, 무시 checkoutId={}", row.getCheckoutId());
                    return;
                }
                issues.release(row.getCouponIssueId(), now);
                log.info("쿠폰 반환 checkoutId={} couponIssueId={} reason={}", row.getCheckoutId(),
                    row.getCouponIssueId(), command.text("reason"));
            }
            case USED -> log.warn("사용된 쿠폰에 반환 명령 — 무시 checkoutId={} couponIssueId={}", row.getCheckoutId(),
                row.getCouponIssueId());
            default -> log.info("이미 반환·거절된 체크아웃 — 무시 checkoutId={} state={}", row.getCheckoutId(),
                row.getState());
        }
    }

    /**
     * 같은 예매의 이전 체크아웃 예약을 넘겨받는다 — 이전 행 RESERVED → RELEASED, 새 행 RESERVED, 쿠폰은 RESERVED 그대로(한 트랜잭션).
     * 잠금 순서는 반환과 같다(예약 행 → 쿠폰). 반환이 먼저 이전 행을 옮겼으면(0행) 넘겨받지 않고 보통 예약으로 간다(쿠폰이 ISSUED면 잡힌다).
     * 종료가 지난 쿠폰은 넘겨받지 않는다(보통 경로가 EXPIRED로 거절). 다른 예매·다른 사용자의 예약은 건드리지 않는다
     */
    private boolean takeOverFromSameBooking(CouponMessages.Command command, CouponIssue issue, Instant now) {
        if (command.bookingId() == null || issue.getState() != CouponIssueState.RESERVED
            || !now.isBefore(issue.getExpiresAt())) {
            return false;
        }
        CouponReservation prior = reservations.findFirstByCouponIssueIdAndBookingIdAndState(issue.getCouponIssueId(),
            command.bookingId(), ReservationState.RESERVED).orElse(null);
        if (prior == null || prior.getCheckoutId().equals(command.checkoutId())) {
            return false;
        }
        if (reservations.moveFromReserved(prior.getCheckoutId(), ReservationState.RELEASED, now) == 0) {
            log.info("넘겨받기 — 이전 예약이 이미 옮겨졌다, 보통 예약으로 checkoutId={} prior={}", command.checkoutId(),
                prior.getCheckoutId());
            return false;
        }
        CouponEvent event = events.findById(issue.getEventId()).orElseThrow(
            () -> new IllegalStateException("쿠폰의 이벤트가 없다 eventId=" + issue.getEventId()));
        reservations.saveAndFlush(CouponReservation.reserved(command.checkoutId(), issue, event.getDiscountRate(),
            command.bookingId(), now));
        publish(command, messages.reserved(command, issue.getCouponIssueId(), event.getDiscountRate(),
            event.getEventId(), now), now);
        log.info("쿠폰 예약 넘겨받음 checkoutId={} prior={} couponIssueId={} bookingId={}", command.checkoutId(),
            prior.getCheckoutId(), issue.getCouponIssueId(), command.bookingId());
        return true;
    }

    private Outcome replyFor(CouponMessages.Command command, CouponReservation row, Instant now) {
        switch (row.getState()) {
            case RESERVED, USED -> {
                publish(command, messages.reserved(command, row.getCouponIssueId(), row.getDiscountRate(),
                    row.getEventId(), now), now);
                return Outcome.OK;
            }
            case RELEASED -> {
                publish(command, messages.rejected(command, ALREADY_RELEASED, now), now);
                return Outcome.REJECTED;
            }
            default -> {
                publish(command, messages.rejected(command, row.getRejectReason(), now), now);
                return Outcome.REJECTED;
            }
        }
    }

    private Outcome reject(CouponMessages.Command command, String reason, Instant now) {
        reservations.saveAndFlush(CouponReservation.rejected(command.checkoutId(), command.couponIssueId(), reason,
            command.bookingId(), now));
        publish(command, messages.rejected(command, reason, now), now);
        log.info("쿠폰 예약 거절 checkoutId={} reason={} bookingId={}", command.checkoutId(), reason, command.bookingId());
        return Outcome.REJECTED;
    }

    private void received(CouponMessages.Command command, Instant now) {
        inbox.saveAndFlush(ProcessedMessage.of(command.eventId(), command.type(), now));
    }

    /** 결과 행 키 — 결과를 낳은 명령의 eventId. 같은 명령의 결과는 한 행(유일 키) */
    private void publish(CouponMessages.Command command, CouponMessages.Reply reply, Instant now) {
        outbox.save(ReplyOutbox.pending(reply, command.bookingId(), command.eventId(), now));
    }
}

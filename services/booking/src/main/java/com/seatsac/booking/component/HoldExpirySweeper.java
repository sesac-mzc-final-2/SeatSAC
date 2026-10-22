package com.seatsac.booking.component;

import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.component.SeatHoldStrategy.ExpiringHold;
import com.seatsac.booking.service.HoldExpiryService;
import com.seatsac.booking.service.HoldExpiryService.Outcome;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * BKG-04 만료 안전망 둘 — 장부 스캔(1초)과 RDS 스캔(10초). 빠른 경로는
 * {@link SeatKeyExpiredListener}(keyspace 알림)다.
 *
 * <p>주기 실행은 {@link HoldExpiryScheduler}가 맡는다 — 스캔 자체는 시각을 받아 도는 메서드라
 * 테스트가 주기를 기다리지 않고 바로 부를 수 있다.
 *
 * <p>두 스캔은 <b>미리보기 파드에서도 돈다</b>(블루/그린). 조건부 UPDATE가 중복 처리를 막으므로
 * 겹쳐도 안전하고, 로직을 바꿀 때도 이전 버전과 함께 돌 수 있어야 한다.
 *
 * <p>한 주기 처리량을 {@link BookingPolicy#EXPIRY_SCAN_BATCH}로 묶는다. 노드 0대 구간(ADR-34)
 * 뒤 첫 스윕에서는 밀린 선점이 한꺼번에 걸리는데, 전부 한 번에 처리하면 Hikari 연결 5개를
 * 다 쓴다. 남은 건은 다음 주기가 이어받는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HoldExpirySweeper {

    private final Clock clock;
    private final SeatHoldStrategy seatHold;
    private final HoldExpiryService holdExpiry;
    private final BookingRepositoryReaderForExpiry expiredBookings;

    /**
     * 안전망 1 — 만료 장부에서 점수가 지난 멤버를 본다. keyspace 알림을 놓쳤을 때 2초 안에 잡는다.
     *
     * <p>멤버의 holdId로 예매를 찾지 못하면 <b>고아 멤버</b>다(B3) — 장부에서만 지운다.
     * 노드 0대 구간 뒤 첫 스윕에서 특히 많이 나온다: 좌석 키는 TTL 300초로 이미 사라졌고
     * 예매도 RDS 스캔이 먼저 정리했을 수 있다.
     */
    public void sweepLedger() {
        List<ExpiringHold> due;
        try {
            due = seatHold.dueHolds(clock.instant(), BookingPolicy.EXPIRY_SCAN_BATCH);
        } catch (DataAccessException e) {
            log.warn("만료 장부를 읽지 못했다 — 다음 주기에 다시 본다", e);
            return;
        }
        if (due.isEmpty()) {
            return;
        }

        int expired = 0;
        int orphans = 0;
        int deferred = 0;
        for (ExpiringHold hold : due) {
            Long bookingId = expiredBookings.findPendingBookingIdByHoldId(hold.holdId()).orElse(null);
            if (bookingId == null) {
                seatHold.forget(hold.screeningId(), hold.holdId());   // 고아 멤버 — 장부만 정리
                orphans++;
                continue;
            }

            Outcome outcome = holdExpiry.expire(bookingId);
            if (outcome == Outcome.DEFERRED) {
                // 결제 중이다. 장부에 남겨 둬야 결제가 끝난 뒤 다시 본다
                deferred++;
                continue;
            }
            if (outcome == Outcome.EXPIRED) {
                expired++;
            }
            seatHold.forget(hold.screeningId(), hold.holdId());
        }
        log.info("만료 장부 스캔 due={} expired={} orphans={} deferred={}",
            due.size(), expired, orphans, deferred);
    }

    /**
     * 안전망 2 — RDS를 직접 본다. Valkey가 통째로 비어도(노드 교체·플러시) 이 경로가 정리한다.
     * 좌석 키가 아직 살아 있을 수 있어 만료 흐름이 {@code release.lua}로 함께 치운다.
     */
    public void sweepDatabase() {
        List<Long> bookingIds;
        try {
            bookingIds = expiredBookings.findExpiredPendingIds(clock.instant(),
                PageRequest.ofSize(BookingPolicy.EXPIRY_SCAN_BATCH));
        } catch (DataAccessException e) {
            log.warn("만료 대상을 읽지 못했다 — 다음 주기에 다시 본다", e);
            return;
        }
        if (bookingIds.isEmpty()) {
            return;
        }

        int expired = 0;
        for (Long bookingId : bookingIds) {
            if (holdExpiry.expire(bookingId) == Outcome.EXPIRED) {
                expired++;
            }
        }
        log.info("만료 RDS 스캔 due={} expired={}", bookingIds.size(), expired);
    }
}

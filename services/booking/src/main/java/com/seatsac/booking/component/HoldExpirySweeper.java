package com.seatsac.booking.component;

import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.component.SeatHoldStrategy.ExpiringHold;
import com.seatsac.booking.service.HoldExpiryService;
import com.seatsac.booking.service.HoldExpiryService.Outcome;
import com.seatsac.common.core.trace.TraceIds;
import com.seatsac.common.core.trace.TraceKeys;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
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

    /** 한 번에 읽는 장부 멤버 수. 처리 상한({@link BookingPolicy#EXPIRY_SCAN_BATCH})과 다르다 */
    private static final int PAGE_SIZE = 100;

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
        String traceId = TraceIds.newId();   // 요청 밖 작업도 로그에 추적 ID를 남긴다 (명세 12.2)
        MDC.put(TraceKeys.MDC_TRACE_ID, traceId);
        try {
            sweepLedgerPages();
        } finally {
            MDC.remove(TraceKeys.MDC_TRACE_ID);
        }
    }

    /**
     * 쪽을 넘기며 돈다. 보류(결제 중)·고아 멤버는 장부에 남거나 지워지는데, 늘 앞에서부터만 읽으면
     * 보류 멤버가 첫 쪽을 채웠을 때 <b>뒤에 있는 만료 건을 영영 못 본다</b>. 한 주기에 처리하는 양은
     * {@link BookingPolicy#EXPIRY_SCAN_BATCH}로 묶되, 보류 멤버는 건너뛰고 다음 쪽을 본다.
     */
    private void sweepLedgerPages() {
        Instant now = clock.instant();
        int offset = 0;
        int handled = 0;
        int expired = 0;
        int orphans = 0;
        int deferred = 0;
        int failed = 0;

        while (handled < BookingPolicy.EXPIRY_SCAN_BATCH) {
            List<ExpiringHold> due;
            try {
                due = seatHold.dueHolds(now, offset, PAGE_SIZE);
            } catch (DataAccessException e) {
                log.warn("만료 장부를 읽지 못했다 — 다음 주기에 다시 본다", e);
                return;
            }
            if (due.isEmpty()) {
                break;
            }

            for (ExpiringHold hold : due) {
                // 멤버 하나가 실패해도 주기를 멈추지 않는다. 멈추면 그 멤버가 장부 앞에 남아
                // 다음 주기도 같은 자리에서 걸리고 뒤의 만료 건을 영영 처리하지 못한다
                try {
                    Long bookingId = expiredBookings.findPendingBookingIdByHoldId(hold.holdId())
                        .orElse(null);
                    if (bookingId == null) {
                        seatHold.forget(hold.screeningId(), hold.holdId());   // 고아 — 장부만 정리
                        orphans++;
                        handled++;
                        continue;
                    }

                    Outcome outcome = holdExpiry.expire(bookingId);
                    if (outcome == Outcome.DEFERRED) {
                        // 결제 중이다. 장부에 남겨 둬야 결제가 끝난 뒤 다시 본다 →
                        // 그만큼 offset을 밀어 다음 쪽에서 뒤 멤버를 본다
                        deferred++;
                        offset++;
                        continue;
                    }
                    if (outcome == Outcome.EXPIRED) {
                        expired++;
                    }
                    seatHold.forget(hold.screeningId(), hold.holdId());
                    handled++;
                } catch (RuntimeException e) {
                    // 이 멤버는 다음 주기에 다시 본다. offset을 밀어 뒤 멤버를 가리지 않게 한다
                    log.warn("만료 처리 실패 screeningId={} holdId={} — 건너뛴다",
                        hold.screeningId(), hold.holdId(), e);
                    failed++;
                    offset++;
                }
            }

            if (due.size() < PAGE_SIZE) {
                break;   // 더 없다
            }
        }

        if (expired + orphans + deferred + failed > 0) {
            log.info("만료 장부 스캔 expired={} orphans={} deferred={} failed={}",
                expired, orphans, deferred, failed);
        }
    }

    /**
     * 안전망 2 — RDS를 직접 본다. Valkey가 통째로 비어도(노드 교체·플러시) 이 경로가 정리한다.
     * 좌석 키가 아직 살아 있을 수 있어 만료 흐름이 {@code release.lua}로 함께 치운다.
     */
    public void sweepDatabase() {
        MDC.put(TraceKeys.MDC_TRACE_ID, TraceIds.newId());
        try {
            sweepExpiredBookings();
        } finally {
            MDC.remove(TraceKeys.MDC_TRACE_ID);
        }
    }

    private void sweepExpiredBookings() {
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
        int failed = 0;
        for (Long bookingId : bookingIds) {
            // 하나가 실패해도 다음 예매로 넘어간다 — 멈추면 그 예매가 계속 앞에 남아 뒤를 가린다
            try {
                if (holdExpiry.expire(bookingId) == Outcome.EXPIRED) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.warn("만료 처리 실패 bookingId={} — 다음 예매로 넘어간다", bookingId, e);
                failed++;
            }
        }
        log.info("만료 RDS 스캔 due={} expired={} failed={}", bookingIds.size(), expired, failed);
    }
}

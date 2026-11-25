package com.seatsac.coupon.service;

import com.seatsac.common.core.trace.TraceIds;
import com.seatsac.common.core.trace.TraceKeys;
import com.seatsac.coupon.config.CouponProperties;
import com.seatsac.coupon.entity.CouponIssue;
import com.seatsac.coupon.repository.CouponIssueRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 종료 스윕(22-4 규칙) — 이벤트가 끝난 <b>ISSUED만</b> EXPIRED로. RESERVED는 booking이 사용·반환을 보낼 때까지 그대로 둔다
 * (결제 결과를 모르는 동안 돈이 움직였을 수 있다). 종료 뒤 {@code APP_COUPON_RESERVED_ALARM_AFTER}(1시간) 넘게 RESERVED로
 * 남은 쿠폰은 ERROR 로그로 알린다(자동 반환하지 않는다). 여러 파드가 같이 돌아도 조건부 UPDATE라 한 번만 바뀐다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponExpirySweeper {

    static final int STALE_LOG_LIMIT = 20;

    private final CouponIssueRepository issues;
    private final CouponProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${app.coupon.sweep-interval:1m}", initialDelayString = "${app.coupon.sweep-interval:1m}")
    public void scheduled() {
        MDC.put(TraceKeys.MDC_TRACE_ID, TraceIds.newId());
        try {
            sweepOnce();
        } catch (RuntimeException e) {
            log.warn("쿠폰 종료 스윕 실패 — 다음 주기에 다시 본다: {}", e.getMessage());
        } finally {
            MDC.remove(TraceKeys.MDC_TRACE_ID);
        }
    }

    /** @return EXPIRED로 바꾼 수 */
    public int sweepOnce() {
        Instant now = clock.instant();
        Integer changed = transactions.execute(status -> issues.expireIssued(now));
        int expired = changed == null ? 0 : changed;
        if (expired > 0) {
            log.info("종료된 이벤트의 받은 쿠폰을 EXPIRED로 expired={}", expired);
        }
        List<CouponIssue> stale = issues.findStaleReserved(now.minus(properties.reservedAlarmAfter()),
            Pageable.ofSize(STALE_LOG_LIMIT));
        for (CouponIssue issue : stale) {
            log.error("종료 뒤 오래 RESERVED로 남은 쿠폰 — booking 체크아웃 결과와 대조 필요 couponIssueId={} eventId={}",
                issue.getCouponIssueId(), issue.getEventId());
        }
        return expired;
    }
}

package com.seatsac.coupon.service;

import com.seatsac.coupon.entity.CouponEvent;
import com.seatsac.coupon.entity.CouponIssue;
import com.seatsac.coupon.repository.CouponEventRepository;
import com.seatsac.coupon.repository.CouponIssueRepository;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발급 확정 트랜잭션 — 총수량 상한 확인(발급 수 &lt; 총수량일 때만 +1)과 {@code coupon_issue} INSERT를 한 트랜잭션에.
 * UK (event_id, user_id) 충돌이면 상한 +1까지 함께 롤백된다. 이 두 가지가 초과 발급·1인 2매의 최종 방어선이다.
 */
@Component
@RequiredArgsConstructor
public class ClaimTx {

    private final CouponEventRepository events;
    private final CouponIssueRepository issues;

    /**
     * @return 발급한 쿠폰. 상한에 걸렸으면(매진) 비어 있다
     * @throws org.springframework.dao.DataIntegrityViolationException 이미 받음(UK) — 롤백됨
     */
    @Transactional
    public Optional<CouponIssue> issue(CouponEvent event, String userId, String claimKey, Instant now) {
        if (events.incrementIssued(event.getEventId()) == 0) {
            return Optional.empty();
        }
        return Optional.of(issues.saveAndFlush(CouponIssue.issue(event, userId, claimKey, now)));
    }
}

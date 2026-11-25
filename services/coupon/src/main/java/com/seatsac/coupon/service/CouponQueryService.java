package com.seatsac.coupon.service;

import com.seatsac.coupon.common.CouponErrorCode;
import com.seatsac.coupon.dto.CouponIssueResponse;
import com.seatsac.coupon.dto.CurrentCouponEventResponse;
import com.seatsac.coupon.dto.MyCouponListResponse;
import com.seatsac.coupon.entity.CouponEvent;
import com.seatsac.coupon.entity.CouponIssue;
import com.seatsac.coupon.repository.CouponEventRepository;
import com.seatsac.coupon.repository.CouponIssueRepository;
import com.seatsac.coupon.stock.CouponStock;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CPN-01·03 조회 — 데이터를 바꾸지 않는다 */
@Service
@RequiredArgsConstructor
public class CouponQueryService {

    private final CouponEventRepository events;
    private final CouponIssueRepository issues;
    private final CouponStock stock;
    private final Clock clock;

    /** CPN-01 — 진행 중, 없으면 가장 가까운 진행 예정. NORMAL도 200(최종 판정은 claim) */
    @Transactional(readOnly = true)
    public CurrentCouponEventResponse current(String userId) {
        Instant now = clock.instant();
        CouponEvent event = events.findCurrent(now).orElseThrow(CouponErrorCode.NO_CURRENT_EVENT::exception);
        CouponIssue mine = issues.findByEventIdAndUserId(event.getEventId(), userId).orElse(null);
        return CurrentCouponEventResponse.of(event, stock.remaining(event), mine, now);
    }

    /** CPN-03 — 받은 시각 내림차순, 등급과 상관없이 */
    @Transactional(readOnly = true)
    public MyCouponListResponse mine(String userId) {
        Instant now = clock.instant();
        List<CouponIssue> mine = issues.findByUserIdOrderByIssuedAtDesc(userId);
        Map<Long, CouponEvent> byId = events.findAllById(mine.stream().map(CouponIssue::getEventId).distinct().toList())
            .stream().collect(Collectors.toMap(CouponEvent::getEventId, Function.identity()));
        return new MyCouponListResponse(mine.stream()
            .map(issue -> CouponIssueResponse.of(issue, byId.get(issue.getEventId()), now))
            .toList());
    }
}

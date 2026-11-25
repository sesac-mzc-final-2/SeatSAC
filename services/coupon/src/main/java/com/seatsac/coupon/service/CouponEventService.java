package com.seatsac.coupon.service;

import com.seatsac.common.core.error.ErrorDetail;
import com.seatsac.common.core.time.Times;
import com.seatsac.coupon.common.CouponErrorCode;
import com.seatsac.coupon.dto.CouponEventCreateRequest;
import com.seatsac.coupon.dto.CouponEventListResponse;
import com.seatsac.coupon.dto.CouponEventResponse;
import com.seatsac.coupon.entity.CouponEvent;
import com.seatsac.coupon.entity.CouponEventState;
import com.seatsac.coupon.repository.CouponEventRepository;
import com.seatsac.coupon.stock.CouponStock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 관리자 이벤트 생성·목록(22-3, 호출자 ops-api) */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponEventService {

    /** 「바로 시작」은 지금 시각을 보낸다 — 1분까지는 지난 시각도 받는다 */
    static final Duration START_GRACE = Duration.ofMinutes(1);

    private final CouponEventRepository events;
    private final CouponStock stock;
    private final Clock clock;

    @Transactional
    public CouponEventResponse create(CouponEventCreateRequest request) {
        Instant now = clock.instant();
        Instant startsAt = Times.toDb(request.startsAt());
        if (startsAt.isBefore(now.minus(START_GRACE))) {
            throw CouponErrorCode.STARTS_IN_PAST.exception(
                ErrorDetail.of("startsAt", request.startsAt().toString(), CouponErrorCode.STARTS_IN_PAST.name()));
        }
        Instant endsAt = startsAt.plus(Duration.ofMinutes(request.durationOrDefault()));
        // 진행 중 이벤트 1개 전제(CPN-01·coupon_stock_remaining) — 끝나지 않은 다른 이벤트와 겹치면 거절
        if (events.existsOverlap(startsAt, endsAt, now)) {
            throw CouponErrorCode.EVENT_OVERLAP.exception(
                ErrorDetail.of("startsAt", request.startsAt().toString(), CouponErrorCode.EVENT_OVERLAP.name()));
        }
        CouponEvent event = events.save(CouponEvent.create(request.title(), request.discountRate(),
            request.gradeOrDefault(), request.totalQuantity(), startsAt, endsAt, now));
        events.flush();
        // 커밋 뒤에 재고 키를 만든다(계약 22-3) — 롤백된 이벤트의 키가 남지 않게
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    stock.create(event);
                } catch (RuntimeException e) {
                    // 키가 없어도 claim이 total − 발급 수로 다시 만든다 — 이벤트 생성은 실패시키지 않는다
                    log.warn("재고 키를 만들지 못했다 — claim이 다시 만든다 eventId={}: {}", event.getEventId(),
                        e.getMessage());
                }
            }
        });
        log.info("쿠폰 이벤트 생성 eventId={} 수량={} 할인율={}", event.getEventId(), event.getTotalQuantity(),
            event.getDiscountRate());
        return CouponEventResponse.of(event, event.getTotalQuantity(), now);
    }

    /** startsAt 내림차순 최근 50개, {@code state}를 주면 그 상태만(서버 시각 기준) */
    @Transactional(readOnly = true)
    public CouponEventListResponse list(CouponEventState state) {
        Instant now = clock.instant();
        return new CouponEventListResponse(events.findTop50ByOrderByStartsAtDesc().stream()
            .filter(event -> state == null || event.stateAt(now) == state)
            .map(event -> CouponEventResponse.of(event, stock.remaining(event), now))
            .toList());
    }
}

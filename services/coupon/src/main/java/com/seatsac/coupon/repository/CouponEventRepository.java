package com.seatsac.coupon.repository;

import com.seatsac.coupon.entity.CouponEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CouponEventRepository extends JpaRepository<CouponEvent, Long> {

    /**
     * 총수량 상한 확인 + 발급 수 +1 을 한 문장으로(원자적). 0행이면 매진 — Valkey 재고 키가 틀려도 이 UPDATE가
     * {@code total_quantity}를 넘기지 않는다(coupon.openapi.yaml claim 「최종 방어선은 DB」)
     */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("""
        update CouponEvent e set e.issuedQuantity = e.issuedQuantity + 1
         where e.eventId = :eventId and e.issuedQuantity < e.totalQuantity
        """)
    int incrementIssued(@Param("eventId") Long eventId);

    /** 지금 보여 줄 이벤트 — 진행 중(종료 전이고 시작함)을 먼저, 없으면 시작이 가장 가까운 예정 */
    @Query("""
        select e from CouponEvent e where e.endsAt > :now
         order by case when e.startsAt <= :now then 0 else 1 end, e.startsAt asc
        """)
    List<CouponEvent> findNotEnded(@Param("now") Instant now, Pageable pageable);

    default Optional<CouponEvent> findCurrent(Instant now) {
        return findNotEnded(now, Pageable.ofSize(1)).stream().findFirst();
    }

    /** 기간이 겹치는, 아직 끝나지 않은 이벤트 수(EVENT_OVERLAP) */
    @Query("""
        select count(e) from CouponEvent e
         where e.endsAt > :now and e.startsAt < :endsAt and e.endsAt > :startsAt
        """)
    long countOverlap(@Param("startsAt") Instant startsAt, @Param("endsAt") Instant endsAt,
                      @Param("now") Instant now);

    default boolean existsOverlap(Instant startsAt, Instant endsAt, Instant now) {
        return countOverlap(startsAt, endsAt, now) > 0;
    }

    List<CouponEvent> findTop50ByOrderByStartsAtDesc();
}

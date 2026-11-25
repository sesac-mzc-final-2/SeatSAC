package com.seatsac.coupon.repository;

import com.seatsac.coupon.entity.CouponIssue;
import com.seatsac.coupon.entity.CouponIssueState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 쿠폰 상태 전이는 모두 현재 상태를 조건으로 건 UPDATE다 — 갱신 행이 0이면 다른 경로가 먼저 바꿨다 */
public interface CouponIssueRepository extends JpaRepository<CouponIssue, String> {

    Optional<CouponIssue> findByEventIdAndUserId(Long eventId, String userId);

    List<CouponIssue> findByUserIdOrderByIssuedAtDesc(String userId);

    long countByEventId(Long eventId);

    /** 예약: 본인·ISSUED·만료 전일 때만. 다른 체크아웃과 동시 예약이면 한쪽만 1 */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update CouponIssue c set c.state = com.seatsac.coupon.entity.CouponIssueState.RESERVED,
               c.reservedAt = :now, c.updatedAt = :now
         where c.couponIssueId = :id and c.userId = :userId
           and c.state = com.seatsac.coupon.entity.CouponIssueState.ISSUED and c.expiresAt > :now
        """)
    int reserve(@Param("id") String id, @Param("userId") String userId, @Param("now") Instant now);

    /** 사용: RESERVED → USED. 종료가 지났어도(예약 때 유효했고 결제가 끝났다) */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update CouponIssue c set c.state = com.seatsac.coupon.entity.CouponIssueState.USED,
               c.usedAt = :now, c.updatedAt = :now
         where c.couponIssueId = :id and c.state = com.seatsac.coupon.entity.CouponIssueState.RESERVED
        """)
    int use(@Param("id") String id, @Param("now") Instant now);

    /** 반환: RESERVED → ISSUED(종료 전) 또는 EXPIRED(종료 뒤) */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update CouponIssue c
           set c.state = case when c.expiresAt > :now then com.seatsac.coupon.entity.CouponIssueState.ISSUED
                              else com.seatsac.coupon.entity.CouponIssueState.EXPIRED end,
               c.reservedAt = null, c.updatedAt = :now
         where c.couponIssueId = :id and c.state = com.seatsac.coupon.entity.CouponIssueState.RESERVED
        """)
    int release(@Param("id") String id, @Param("now") Instant now);

    /** 종료 스윕: ISSUED만 EXPIRED로. RESERVED는 booking이 사용·반환을 보낼 때까지 그대로(22-4 규칙) */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update CouponIssue c set c.state = com.seatsac.coupon.entity.CouponIssueState.EXPIRED, c.updatedAt = :now
         where c.state = com.seatsac.coupon.entity.CouponIssueState.ISSUED and c.expiresAt <= :now
        """)
    int expireIssued(@Param("now") Instant now);

    /** 종료 뒤 오래 RESERVED로 남은 쿠폰 — 경보만(자동 반환하지 않는다) */
    @Query("""
        select c from CouponIssue c
         where c.state = com.seatsac.coupon.entity.CouponIssueState.RESERVED and c.expiresAt <= :before
         order by c.expiresAt asc
        """)
    List<CouponIssue> findStaleReserved(@Param("before") Instant before, Pageable pageable);

    long countByState(CouponIssueState state);
}

package com.seatsac.coupon.repository;

import com.seatsac.coupon.entity.CouponReservation;
import com.seatsac.coupon.entity.ReservationState;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponReservationRepository extends JpaRepository<CouponReservation, String> {

    /** 같은 예매의 다른 체크아웃이 이 쿠폰을 잡고 있는지(넘겨받기). INDEX (coupon_issue_id) */
    Optional<CouponReservation> findFirstByCouponIssueIdAndBookingIdAndState(String couponIssueId, Long bookingId,
                                                                              ReservationState state);

    /** RESERVED → USED·RELEASED. 0행이면 이미 다른 상태 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update CouponReservation r set r.state = :next, r.updatedAt = :now
         where r.checkoutId = :checkoutId and r.state = com.seatsac.coupon.entity.ReservationState.RESERVED
        """)
    int moveFromReserved(@Param("checkoutId") String checkoutId,
                         @Param("next") com.seatsac.coupon.entity.ReservationState next, @Param("now") Instant now);
}

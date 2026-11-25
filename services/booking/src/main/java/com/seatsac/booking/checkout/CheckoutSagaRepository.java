package com.seatsac.booking.checkout;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 상태 전이는 모두 <b>현재 상태를 조건으로 건</b> UPDATE다({@code state in :expected}). 0행이면 늦은 결과·중복이라
 * 부르는 쪽이 아무것도 하지 않는다(설계 01 5-2). 파드 여러 개·블루/그린 preview가 겹쳐도 한 번만 반영된다.
 */
public interface CheckoutSagaRepository extends JpaRepository<CheckoutSaga, String> {

    Optional<CheckoutSaga> findFirstByBookingIdOrderByCreatedAtDescCheckoutIdDesc(Long bookingId);

    boolean existsByBookingId(Long bookingId);

    long countByBookingId(Long bookingId);

    @Query("select count(s) > 0 from CheckoutSaga s where s.bookingId = :bookingId and s.state in :active")
    boolean existsActive(@Param("bookingId") Long bookingId, @Param("active") Collection<CheckoutState> active);

    /** 다음 단계로(기한 갱신). 쿠폰 예약 대기·결제 준비·청구로 넘어갈 때 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update CheckoutSaga s
           set s.state = :next, s.step = :step, s.stepDeadlineAt = :deadline, s.updatedAt = :now
         where s.checkoutId = :checkoutId and s.state in :expected
        """)
    int advance(@Param("checkoutId") String checkoutId, @Param("expected") Collection<CheckoutState> expected,
                @Param("next") CheckoutState next, @Param("step") String step,
                @Param("deadline") Instant deadline, @Param("now") Instant now);

    /** 결제 명령을 처음 보낼 때 — 금액 확정 + paymentId */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update CheckoutSaga s
           set s.state = :next, s.step = :step, s.stepDeadlineAt = :deadline, s.paymentId = :paymentId,
               s.discountKind = :discountKind, s.discountValue = :discountValue,
               s.discountAmount = :discountAmount, s.finalAmount = :finalAmount, s.updatedAt = :now
         where s.checkoutId = :checkoutId and s.state in :expected
        """)
    int startPayment(@Param("checkoutId") String checkoutId, @Param("expected") Collection<CheckoutState> expected,
                     @Param("next") CheckoutState next, @Param("step") String step,
                     @Param("deadline") Instant deadline, @Param("paymentId") String paymentId,
                     @Param("discountKind") String discountKind, @Param("discountValue") Integer discountValue,
                     @Param("discountAmount") int discountAmount, @Param("finalAmount") int finalAmount,
                     @Param("now") Instant now);

    /** 결제 준비 완료 → READY_TO_PAY(결제창 값 저장). 기한은 선점 만료 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update CheckoutSaga s
           set s.state = com.seatsac.booking.checkout.CheckoutState.READY_TO_PAY, s.step = 'payment_prepared',
               s.checkoutJson = :checkoutJson, s.stepDeadlineAt = :deadline, s.updatedAt = :now
         where s.checkoutId = :checkoutId and s.state = com.seatsac.booking.checkout.CheckoutState.PAYMENT_PREPARING
        """)
    int readyToPay(@Param("checkoutId") String checkoutId, @Param("checkoutJson") String checkoutJson,
                   @Param("deadline") Instant deadline, @Param("now") Instant now);

    /** 확인 요청 → VERIFYING, 첫 재확인 1회 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update CheckoutSaga s
           set s.state = com.seatsac.booking.checkout.CheckoutState.VERIFYING, s.step = 'verify_requested',
               s.verifyAttempt = 1, s.stepDeadlineAt = :deadline, s.updatedAt = :now
         where s.checkoutId = :checkoutId and s.state = com.seatsac.booking.checkout.CheckoutState.READY_TO_PAY
        """)
    int startVerify(@Param("checkoutId") String checkoutId, @Param("deadline") Instant deadline,
                    @Param("now") Instant now);

    /** 끝 상태로(CONFIRMED·FAILED·EXPIRED·COUPON_REJECTED·PAYMENT_RETRYABLE). 기한은 지운다 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update CheckoutSaga s
           set s.state = :next, s.step = :step, s.failureCode = :failureCode, s.failureReason = :failureReason,
               s.refundNotice = :refundNotice, s.stepDeadlineAt = null, s.updatedAt = :now
         where s.checkoutId = :checkoutId and s.state in :expected
        """)
    int finish(@Param("checkoutId") String checkoutId, @Param("expected") Collection<CheckoutState> expected,
               @Param("next") CheckoutState next, @Param("step") String step,
               @Param("failureCode") String failureCode, @Param("failureReason") String failureReason,
               @Param("refundNotice") String refundNotice, @Param("now") Instant now);

    /** 결과 불명 — 상태 그대로, 사유와 다음 재확인 기한만(D21). 재발송은 스윕이 한다 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update CheckoutSaga s
           set s.lastUnknown = :reason, s.stepDeadlineAt = :deadline, s.updatedAt = :now
         where s.checkoutId = :checkoutId and s.state in :expected
        """)
    int noteUnknown(@Param("checkoutId") String checkoutId, @Param("expected") Collection<CheckoutState> expected,
                    @Param("reason") String reason, @Param("deadline") Instant deadline, @Param("now") Instant now);

    /**
     * 재확인 처리권 — 스윕이 본 기한·횟수 그대로일 때만 다음 기한·횟수로 바꾼다(compare-and-set). 1행인 파드만 재발송한다.
     * 결과 불명 사유는 지운다 — 다음 결과가 오기 전까지는 「payment 무응답」으로 본다
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update CheckoutSaga s
           set s.verifyAttempt = s.verifyAttempt + 1, s.stepDeadlineAt = :deadline, s.lastUnknown = null,
               s.updatedAt = :now
         where s.checkoutId = :checkoutId and s.state = :state
           and s.verifyAttempt = :expectedAttempt and s.stepDeadlineAt = :expectedDeadline
        """)
    int claimVerifyRetry(@Param("checkoutId") String checkoutId, @Param("state") CheckoutState state,
                         @Param("expectedAttempt") int expectedAttempt, @Param("expectedDeadline") Instant expectedDeadline,
                         @Param("deadline") Instant deadline, @Param("now") Instant now);

    /** 만료된 saga의 환불 안내만 바꾼다(늦은 승인·BOOKING_EXPIRED 확인) */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update CheckoutSaga s set s.refundNotice = :notice, s.updatedAt = :now
         where s.checkoutId = :checkoutId and s.state = com.seatsac.booking.checkout.CheckoutState.EXPIRED
        """)
    int noteRefund(@Param("checkoutId") String checkoutId, @Param("notice") String notice, @Param("now") Instant now);

    /** 스윕 — 기한이 지난 진행 중 saga */
    @Query("""
        select s from CheckoutSaga s
         where s.state in :states and s.stepDeadlineAt <= :now
         order by s.stepDeadlineAt asc
        """)
    List<CheckoutSaga> findDue(@Param("states") Collection<CheckoutState> states, @Param("now") Instant now,
                               Pageable pageable);

    /** 스윕 — 진행 중인데 예매가 PENDING을 벗어났다(D23: 예매는 HoldExpiryService 등이 이미 닫았다) */
    @Query("""
        select s from CheckoutSaga s, com.seatsac.booking.entity.Booking b
         where b.bookingId = s.bookingId and s.state in :active
           and b.status <> com.seatsac.booking.enums.BookingStatus.PENDING
        """)
    List<CheckoutSaga> findActiveWithClosedBooking(@Param("active") Collection<CheckoutState> active, Pageable pageable);
}

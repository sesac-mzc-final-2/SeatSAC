package com.seatsac.payment.repository;

import com.seatsac.payment.entity.Payment;
import com.seatsac.payment.enums.PaymentCancelReason;
import com.seatsac.payment.enums.PaymentFailureCode;
import com.seatsac.payment.enums.PaymentStatus;
import com.seatsac.payment.enums.PgProvider;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 상태 전이는 모두 <b>현재 상태를 조건으로 건</b> UPDATE다 — 여러 파드·경로(결제 응답, 취소,
 * 기동 정리)가 겹쳐도 한 번만 반영된다. 갱신 행이 0이면 이미 다른 쪽이 처리한 것이다.
 */
public interface PaymentRepository extends JpaRepository<Payment, String> {

    /**
     * 4단계 — 판정 결과 반영. PROCESSING일 때만. PORTONE은 {@code pgTransactionId}(돈이 잡힌 증거)를 함께 적는다 —
     * FAILED(AMOUNT_MISMATCH)인데 transactionId가 있으면 환불 미완료 표식이다(ADR-112).
     * 재확인 백오프(D18)는 처음부터 — PROCESSING에서 미룬 횟수가 환불 재시도 간격으로 이어지지 않게(#190 리뷰)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Payment p
           set p.status = :status,
               p.failureCode = :failureCode,
               p.pgTransactionId = :pgTransactionId,
               p.approvedAt = :approvedAt,
               p.checkAttempt = 0,
               p.nextCheckAt = null,
               p.updatedAt = :now
         where p.paymentId = :paymentId
           and p.status = com.seatsac.payment.enums.PaymentStatus.PROCESSING
        """)
    int settleProcessing(@Param("paymentId") String paymentId,
                         @Param("status") PaymentStatus status,
                         @Param("failureCode") PaymentFailureCode failureCode,
                         @Param("pgTransactionId") String pgTransactionId,
                         @Param("approvedAt") Instant approvedAt,
                         @Param("now") Instant now);

    /**
     * 늦은 결제 — 이미 FAILED·CANCELED로 끝낸 행에 포트원 PAID가 왔다(웹훅·스윕). 돈이 잡혔다는 증거만 남겨
     * 환불 미완료로 만든다. 한 번만(transactionId가 비어 있을 때만). 재확인 백오프는 처음부터(환불은 새 일이다)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Payment p
           set p.pgTransactionId = :pgTransactionId,
               p.checkAttempt = 0,
               p.nextCheckAt = null,
               p.updatedAt = :now
         where p.paymentId = :paymentId
           and p.pgProvider = com.seatsac.payment.enums.PgProvider.PORTONE
           and p.status = com.seatsac.payment.enums.PaymentStatus.FAILED
           and p.pgTransactionId is null
        """)
    int markRefundOwed(@Param("paymentId") String paymentId,
                       @Param("pgTransactionId") String pgTransactionId,
                       @Param("now") Instant now);

    /** 환불 미완료 행의 환불이 포트원에서 확인됐다 — canceled_at만 채운다(status는 FAILED 그대로). 한 번만 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Payment p
           set p.canceledAt = :now,
               p.cancelReason = :reason,
               p.updatedAt = :now
         where p.paymentId = :paymentId
           and p.status = com.seatsac.payment.enums.PaymentStatus.FAILED
           and p.pgTransactionId is not null
           and p.canceledAt is null
        """)
    int confirmRefund(@Param("paymentId") String paymentId,
                      @Param("reason") PaymentCancelReason reason,
                      @Param("now") Instant now);

    /** 스윕이 다시 볼 때까지 손댄 시각만 올린다(환불 재시도 간격 60초의 기준) */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Payment p set p.updatedAt = :now where p.paymentId = :paymentId")
    int touch(@Param("paymentId") String paymentId, @Param("now") Instant now);

    /**
     * 환불 미완료(ADR-112) — FAILED인데 돈이 잡혀 있고 환불 확인이 없는 PORTONE 행. 처음에는 손댄 지 {@code threshold}가
     * 지난 행, 한 번 미룬 뒤로는 {@code next_check_at}이 된 행(D18 백오프)
     */
    @Query("""
        select p from Payment p
         where p.pgProvider = com.seatsac.payment.enums.PgProvider.PORTONE
           and p.status = com.seatsac.payment.enums.PaymentStatus.FAILED
           and p.pgTransactionId is not null
           and p.canceledAt is null
           and ((p.nextCheckAt is null and p.updatedAt <= :threshold) or p.nextCheckAt <= :now)
         order by coalesce(p.nextCheckAt, p.updatedAt) asc
        """)
    List<Payment> findRefundOwed(@Param("threshold") Instant threshold, @Param("now") Instant now, Pageable pageable);

    /**
     * 생성 뒤 {@code createdBefore}가 지나도 끝나지 않은 환불 미완료 — 경보용({@code payment_refund_unresolved_total}).
     * 백오프로 스윕이 매번 다시 보지 않아도 「남아 있는 행 × 분」으로 센다
     */
    @Query("""
        select p from Payment p
         where p.pgProvider = com.seatsac.payment.enums.PgProvider.PORTONE
           and p.status = com.seatsac.payment.enums.PaymentStatus.FAILED
           and p.pgTransactionId is not null
           and p.canceledAt is null
           and p.createdAt <= :createdBefore
         order by p.createdAt asc
        """)
    List<Payment> findRefundUnresolved(@Param("createdBefore") Instant createdBefore, Pageable pageable);

    /**
     * D18 — 이번에 포트원 확인을 끝내지 못했다. 다음 확인 시각을 미룬다(횟수 +1). 읽은 횟수({@code expected})를 조건으로 건다 —
     * 파드 둘이 같은 주기에 같은 행을 미뤄도 한 번만 오른다(#190 리뷰). 0행이면 다른 파드가 먼저 미뤘거나 상태가 바뀌었다
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Payment p set p.checkAttempt = :expected + 1, p.nextCheckAt = :next, p.updatedAt = :now
         where p.paymentId = :paymentId
           and p.checkAttempt = :expected
        """)
    int deferCheck(@Param("paymentId") String paymentId, @Param("expected") int expected,
                   @Param("next") Instant next, @Param("now") Instant now);

    /**
     * 늦은 결제 다시 보기(ADR-112) — 최근 FAILED로 닫혔고 돈이 잡힌 흔적이 없는 PORTONE 행. 닫은 뒤 사용자가 결제를
     * 끝냈는데 웹훅이 오지 않은 경우를 스윕이 포트원 조회로 찾는다
     */
    @Query("""
        select p from Payment p
         where p.pgProvider = com.seatsac.payment.enums.PgProvider.PORTONE
           and p.status = com.seatsac.payment.enums.PaymentStatus.FAILED
           and p.pgTransactionId is null
           and p.updatedAt >= :since
         order by p.updatedAt asc
        """)
    List<Payment> findRecentlyFailedWithoutTransaction(@Param("since") Instant since, Pageable pageable);

    /**
     * saga 해제(D16) — 이미 APPROVED인 PORTONE 결제를 booking이 놓았다(선점 만료). 승인을 쓰지 않기로 하고
     * FAILED(BOOKING_EXPIRED)로 바꾼다. {@code pg_transaction_id}는 그대로라 환불 미완료가 되어 스윕이 환불한다. 한 번만.
     * 재확인 백오프는 처음부터
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Payment p
           set p.status = com.seatsac.payment.enums.PaymentStatus.FAILED,
               p.failureCode = com.seatsac.payment.enums.PaymentFailureCode.BOOKING_EXPIRED,
               p.checkAttempt = 0,
               p.nextCheckAt = null,
               p.updatedAt = :now
         where p.paymentId = :paymentId
           and p.pgProvider = com.seatsac.payment.enums.PgProvider.PORTONE
           and p.status = com.seatsac.payment.enums.PaymentStatus.APPROVED
           and p.pgTransactionId is not null
        """)
    int releaseApprovedPortOne(@Param("paymentId") String paymentId, @Param("now") Instant now);

    /** PAY-03 — 승인된 결제만 취소한다. 이미 CANCELED면 0행이고 첫 취소 사유를 유지한다 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Payment p
           set p.status = com.seatsac.payment.enums.PaymentStatus.CANCELED,
               p.cancelReason = :reason,
               p.canceledAt = :now,
               p.updatedAt = :now
         where p.paymentId = :paymentId
           and p.status = com.seatsac.payment.enums.PaymentStatus.APPROVED
        """)
    int cancelApproved(@Param("paymentId") String paymentId,
                       @Param("reason") PaymentCancelReason reason,
                       @Param("now") Instant now);

    /**
     * 기동 시 정리 — 판정 중 파드가 죽어 남은 행. 외부 PG가 없어 결과를 다시 물을 곳이 없으므로
     * {@code FAILED(PROCESSING_TIMEOUT)}으로 닫는다 (명세 3·7장).
     */
    @Query("""
        select p.paymentId from Payment p
         where p.status = com.seatsac.payment.enums.PaymentStatus.PROCESSING
           and p.pgProvider = :provider
           and p.createdAt <= :threshold
         order by p.createdAt asc
        """)
    List<String> findStaleProcessingIds(@Param("provider") PgProvider provider,
                                        @Param("threshold") Instant threshold, Pageable pageable);

    /** 결제창 미완료 PORTONE 정리 — 생성 뒤 {@code threshold}가 지났고, 미룬 적이 있으면 {@code next_check_at}이 된 행(D18) */
    @Query("""
        select p.paymentId from Payment p
         where p.status = com.seatsac.payment.enums.PaymentStatus.PROCESSING
           and p.pgProvider = com.seatsac.payment.enums.PgProvider.PORTONE
           and p.createdAt <= :threshold
           and (p.nextCheckAt is null or p.nextCheckAt <= :now)
         order by p.createdAt asc
        """)
    List<String> findAbandonedPortOneIds(@Param("threshold") Instant threshold, @Param("now") Instant now,
                                         Pageable pageable);
}

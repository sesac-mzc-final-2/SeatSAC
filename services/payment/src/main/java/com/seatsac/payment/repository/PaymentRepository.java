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
     * FAILED(AMOUNT_MISMATCH)인데 transactionId가 있으면 환불 미완료 표식이다(ADR-112)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Payment p
           set p.status = :status,
               p.failureCode = :failureCode,
               p.pgTransactionId = :pgTransactionId,
               p.approvedAt = :approvedAt,
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
     * 환불 미완료로 만든다. 한 번만(transactionId가 비어 있을 때만)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Payment p
           set p.pgTransactionId = :pgTransactionId,
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

    /** 환불 미완료(ADR-112) — FAILED인데 돈이 잡혀 있고 환불 확인이 없는 PORTONE 행. 손댄 지 오래된 것부터 */
    @Query("""
        select p from Payment p
         where p.pgProvider = com.seatsac.payment.enums.PgProvider.PORTONE
           and p.status = com.seatsac.payment.enums.PaymentStatus.FAILED
           and p.pgTransactionId is not null
           and p.canceledAt is null
           and p.updatedAt <= :threshold
         order by p.updatedAt asc
        """)
    List<Payment> findRefundOwed(@Param("threshold") Instant threshold, Pageable pageable);

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
}

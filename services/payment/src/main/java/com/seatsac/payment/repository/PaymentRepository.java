package com.seatsac.payment.repository;

import com.seatsac.payment.entity.Payment;
import com.seatsac.payment.enums.PaymentCancelReason;
import com.seatsac.payment.enums.PaymentFailureCode;
import com.seatsac.payment.enums.PaymentStatus;
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

    /** 4단계 — 모의 판정 결과 반영. PROCESSING일 때만 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Payment p
           set p.status = :status,
               p.failureCode = :failureCode,
               p.approvedAt = :approvedAt,
               p.updatedAt = :now
         where p.paymentId = :paymentId
           and p.status = com.seatsac.payment.enums.PaymentStatus.PROCESSING
        """)
    int settleProcessing(@Param("paymentId") String paymentId,
                         @Param("status") PaymentStatus status,
                         @Param("failureCode") PaymentFailureCode failureCode,
                         @Param("approvedAt") Instant approvedAt,
                         @Param("now") Instant now);

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
           and p.createdAt <= :threshold
         order by p.createdAt asc
        """)
    List<String> findStaleProcessingIds(@Param("threshold") Instant threshold, Pageable pageable);
}

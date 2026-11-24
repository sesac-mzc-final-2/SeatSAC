package com.seatsac.payment.saga;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SagaPaymentRepository extends JpaRepository<SagaPayment, String> {

    /** booking이 놓은 결제인가 — 포트원 PAID를 승인으로 쓸지 정할 때 본다(D16) */
    @Query("select count(s) > 0 from SagaPayment s where s.paymentId = :paymentId and s.releasedAt is not null")
    boolean isReleased(@Param("paymentId") String paymentId);

    /** 해제 표시. 처음 한 번만(이미 있으면 첫 시각 유지) */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SagaPayment s set s.releasedAt = :now where s.paymentId = :paymentId and s.releasedAt is null")
    int markReleased(@Param("paymentId") String paymentId, @Param("now") Instant now);
}

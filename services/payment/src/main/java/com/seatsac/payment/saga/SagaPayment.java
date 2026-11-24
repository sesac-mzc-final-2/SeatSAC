package com.seatsac.payment.saga;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * saga 명령으로 만든 결제 표시(설계 02 5장, D7) — 동기 경로 결제는 행이 없다.
 *
 * <p>{@code releasedAt}: booking이 선점 만료로 이 결제를 놓은 시각({@code payment.release-requested}, D16).
 * 값이 있으면 이후 포트원 PAID는 승인이 아니라 환불 대상이다({@link com.seatsac.payment.enums.PaymentFailureCode#BOOKING_EXPIRED}).
 */
@Entity
@Table(name = "saga_payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SagaPayment {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "payment_id", length = 36, nullable = false, updatable = false)
    private String paymentId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "checkout_id", length = 36, nullable = false, updatable = false)
    private String checkoutId;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private Long bookingId;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static SagaPayment of(String paymentId, String checkoutId, Long bookingId, Instant now) {
        SagaPayment row = new SagaPayment();
        row.paymentId = Objects.requireNonNull(paymentId, "paymentId");
        row.checkoutId = Objects.requireNonNull(checkoutId, "checkoutId");
        row.bookingId = Objects.requireNonNull(bookingId, "bookingId");
        row.createdAt = Objects.requireNonNull(now, "now");
        return row;
    }

    public boolean isReleased() {
        return releasedAt != null;
    }
}

package com.seatsac.payment.entity;

import com.seatsac.payment.common.PaymentPolicy;
import com.seatsac.payment.enums.PaymentCancelReason;
import com.seatsac.payment.enums.PaymentFailureCode;
import com.seatsac.payment.enums.PaymentMethod;
import com.seatsac.payment.enums.PaymentStatus;
import com.seatsac.payment.enums.PgProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 모의 결제 — 명세 6장. 사용자 ID는 받지도 저장하지도 않는다(추적은 {@code bookingId}·{@code traceId}).
 *
 * <p>카드는 <b>끝 4자리만</b> 담는다. 전체 번호·{@code expiry}·{@code holder}는 저장도 로그도 하지 않는다.
 *
 * <p>상태 전이는 전부 조건부 UPDATE(리포지토리)로 한다 — 「{@code PROCESSING}일 때만 판정 반영」,
 * 「{@code APPROVED}일 때만 취소」. 여러 파드·경로가 겹쳐도 한 번만 반영된다.
 */
@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "payment_id", length = 36, nullable = false, updatable = false)
    private String paymentId;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private Long bookingId;

    @Column(name = "amount", nullable = false, updatable = false)
    private Integer amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", length = 20, nullable = false, updatable = false)
    private PaymentMethod method;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_last4", length = 4, updatable = false)
    private String cardLast4;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", length = 40)
    private PaymentFailureCode failureCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason", length = 40)
    private PaymentCancelReason cancelReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "pg_provider", length = 20, nullable = false, updatable = false)
    private PgProvider pgProvider;

    @Column(name = "pg_order_id", length = 64, updatable = false)
    private String pgOrderId;

    @Column(name = "pg_transaction_id", length = 64)
    private String pgTransactionId;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 판정 전 행 — 명세 4장 2단계. <b>먼저 INSERT하고 커밋</b>한 뒤 판정은 트랜잭션 밖에서 한다.
     * 지연 카드({@code 9999})를 기다리는 동안 DB 커넥션을 쥐지 않고, 같은 결제 ID로 재호출이 와도
     * 이 행을 보고 {@code PROCESSING}을 돌려줄 수 있다.
     */
    public static Payment processing(String paymentId, Long bookingId, Integer amount,
                                     PaymentMethod method, String cardLast4, Instant now) {
        Payment payment = new Payment();
        payment.paymentId = Objects.requireNonNull(paymentId, "paymentId");
        payment.bookingId = Objects.requireNonNull(bookingId, "bookingId");
        payment.amount = Objects.requireNonNull(amount, "amount");
        payment.method = Objects.requireNonNull(method, "method");
        payment.cardLast4 = cardLast4;
        payment.status = PaymentStatus.PROCESSING;
        payment.pgProvider = PgProvider.MOCK;
        payment.createdAt = Objects.requireNonNull(now, "now");
        payment.updatedAt = now;
        return payment;
    }

    /** 재호출이 같은 요청인지 — 결제 ID가 같아도 예매·금액이 다르면 409다 (명세 4장) */
    public boolean matches(Long bookingId, Integer amount) {
        return this.bookingId.equals(bookingId) && this.amount.equals(amount);
    }

    public boolean isApproved() {
        return status == PaymentStatus.APPROVED;
    }

    public boolean isCanceled() {
        return status == PaymentStatus.CANCELED;
    }

    /** 카드 번호에서 저장할 부분만 뽑는다 — 끝 4자리 */
    public static String last4Of(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < PaymentPolicy.CARD_LAST4_LENGTH) {
            return null;
        }
        return cardNumber.substring(cardNumber.length() - PaymentPolicy.CARD_LAST4_LENGTH);
    }
}

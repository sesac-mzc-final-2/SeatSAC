package com.seatsac.booking.checkout;

import com.seatsac.booking.enums.PaymentMethod;
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
 * 체크아웃 saga 한 건(설계 01 3장). 상태 전이는 모두 {@link CheckoutSagaRepository}의 조건부 UPDATE로 한다
 * ({@code WHERE state = :expected}) — 여러 파드·늦은 결과·중복 메시지가 겹쳐도 한 번만 반영된다.
 *
 * <p>생성 열 {@code active_booking_id}는 매핑하지 않는다(DB가 계산, 진행 중 1건 유일 키).
 * {@code booking.amount}는 원가로 불변이고 할인·최종 금액은 여기에만 있다(D11).
 */
@Entity
@Table(name = "checkout_saga")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CheckoutSaga {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "checkout_id", length = 36, nullable = false, updatable = false)
    private String checkoutId;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private Long bookingId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", length = 36, nullable = false, updatable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", length = 20, nullable = false, updatable = false)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 30, nullable = false)
    private CheckoutState state;

    @Column(name = "step", length = 40, nullable = false)
    private String step;

    @Column(name = "attempt", nullable = false, updatable = false)
    private Integer attempt;

    @Column(name = "coupon_issue_id", length = 64, updatable = false)
    private String couponIssueId;

    /** 모의 카드(CARD)만 — 쿠폰 예약 결과가 온 뒤 청구 명령에 넣는다. 끝 4자리만 */
    @Column(name = "card_last4", length = 4, updatable = false)
    private String cardLast4;

    @Column(name = "discount_kind", length = 10)
    private String discountKind;

    @Column(name = "discount_value")
    private Integer discountValue;

    @Column(name = "original_amount", nullable = false, updatable = false)
    private Integer originalAmount;

    @Column(name = "discount_amount", nullable = false)
    private Integer discountAmount;

    @Column(name = "final_amount", nullable = false)
    private Integer finalAmount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "payment_id", length = 36)
    private String paymentId;

    @Column(name = "checkout_json", columnDefinition = "json")
    private String checkoutJson;

    @Column(name = "failure_code", length = 40)
    private String failureCode;

    @Column(name = "failure_reason", length = 100)
    private String failureReason;

    @Column(name = "step_deadline_at")
    private Instant stepDeadlineAt;

    @Column(name = "verify_attempt", nullable = false)
    private Integer verifyAttempt;

    @Column(name = "last_unknown", length = 30)
    private String lastUnknown;

    @Column(name = "refund_notice", length = 30)
    private String refundNotice;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "trace_id", length = 32, updatable = false)
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 시작 행(STARTED). 금액은 원가로 시작하고 쿠폰이 예약되면 조건부 UPDATE로 바꾼다 */
    static CheckoutSaga started(String checkoutId, Long bookingId, String userId, PaymentMethod method, int attempt,
                                String couponIssueId, String cardLast4, int originalAmount, String traceId,
                                Instant now) {
        CheckoutSaga saga = new CheckoutSaga();
        saga.checkoutId = Objects.requireNonNull(checkoutId, "checkoutId");
        saga.bookingId = Objects.requireNonNull(bookingId, "bookingId");
        saga.userId = Objects.requireNonNull(userId, "userId");
        saga.method = Objects.requireNonNull(method, "method");
        saga.state = CheckoutState.STARTED;
        saga.step = "accepted";
        saga.attempt = attempt;
        saga.couponIssueId = couponIssueId;
        saga.cardLast4 = cardLast4;
        saga.originalAmount = originalAmount;
        saga.discountAmount = 0;
        saga.finalAmount = originalAmount;
        saga.verifyAttempt = 0;
        saga.traceId = traceId;
        saga.createdAt = Objects.requireNonNull(now, "now");
        saga.updatedAt = now;
        return saga;
    }
}

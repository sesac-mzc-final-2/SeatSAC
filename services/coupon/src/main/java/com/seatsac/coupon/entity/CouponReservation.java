package com.seatsac.coupon.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 체크아웃 1건 = 1행(22-4). 명령이 어떤 순서로 와도 결과가 같게 만드는 근거다 — 반환이 예약보다 먼저 오면 RELEASED 행을
 * 먼저 만들어 늦은 예약을 거절하고, 같은 checkoutId의 예약 재시도에는 이 행의 결과를 그대로 다시 보낸다.
 */
@Entity
@Table(name = "coupon_reservation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponReservation {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "checkout_id", length = 36, nullable = false, updatable = false)
    private String checkoutId;

    @Column(name = "coupon_issue_id", length = 64, nullable = false, updatable = false)
    private String couponIssueId;

    @Column(name = "booking_id")
    private Long bookingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 20, nullable = false)
    private ReservationState state;

    @Column(name = "reject_reason", length = 40)
    private String rejectReason;

    @Column(name = "discount_rate")
    private Integer discountRate;

    @Column(name = "event_id")
    private Long eventId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static CouponReservation reserved(String checkoutId, CouponIssue issue, int discountRate, Long bookingId,
                                             Instant now) {
        CouponReservation row = base(checkoutId, issue.getCouponIssueId(), bookingId, ReservationState.RESERVED, now);
        row.discountRate = discountRate;
        row.eventId = issue.getEventId();
        return row;
    }

    public static CouponReservation rejected(String checkoutId, String couponIssueId, String reason, Long bookingId,
                                             Instant now) {
        CouponReservation row = base(checkoutId, couponIssueId, bookingId, ReservationState.REJECTED, now);
        row.rejectReason = reason;
        return row;
    }

    /** 반환이 예약보다 먼저 왔다 — 늦게 올 예약을 거절하려고 먼저 기록한다(쿠폰은 그대로) */
    public static CouponReservation releasedFirst(String checkoutId, String couponIssueId, Long bookingId, Instant now) {
        return base(checkoutId, couponIssueId, bookingId, ReservationState.RELEASED, now);
    }

    private static CouponReservation base(String checkoutId, String couponIssueId, Long bookingId,
                                          ReservationState state, Instant now) {
        CouponReservation row = new CouponReservation();
        row.checkoutId = checkoutId;
        row.couponIssueId = couponIssueId;
        row.bookingId = bookingId;
        row.state = state;
        row.createdAt = now;
        row.updatedAt = now;
        return row;
    }
}

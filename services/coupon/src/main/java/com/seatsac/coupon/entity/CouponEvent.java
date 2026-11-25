package com.seatsac.coupon.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 쿠폰 이벤트(22-3). 발급 수 {@code issuedQuantity}는 발급 트랜잭션의 조건부 UPDATE만 바꾼다 */
@Entity
@Table(name = "coupon_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long eventId;

    @Column(name = "title", length = 100, nullable = false)
    private String title;

    @Column(name = "discount_rate", nullable = false)
    private int discountRate;

    @Column(name = "eligible_grade", length = 20, nullable = false)
    private String eligibleGrade;

    @Column(name = "total_quantity", nullable = false)
    private int totalQuantity;

    @Column(name = "issued_quantity", nullable = false)
    private int issuedQuantity;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static CouponEvent create(String title, int discountRate, String eligibleGrade, int totalQuantity,
                                     Instant startsAt, Instant endsAt, Instant now) {
        CouponEvent event = new CouponEvent();
        event.title = title;
        event.discountRate = discountRate;
        event.eligibleGrade = eligibleGrade;
        event.totalQuantity = totalQuantity;
        event.issuedQuantity = 0;
        event.startsAt = startsAt;
        event.endsAt = endsAt;
        event.createdAt = now;
        return event;
    }

    public CouponEventState stateAt(Instant now) {
        return CouponEventState.of(startsAt, endsAt, now);
    }
}

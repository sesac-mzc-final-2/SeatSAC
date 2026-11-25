package com.seatsac.coupon.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 받은 쿠폰 한 장. 상태 전이는 모두 {@code CouponIssueRepository}의 조건부 UPDATE로 한다 */
@Entity
@Table(name = "coupon_issue")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponIssue {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "coupon_issue_id", length = 36, nullable = false, updatable = false)
    private String couponIssueId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private Long eventId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", length = 36, nullable = false, updatable = false)
    private String userId;

    /** 발급한 요청의 Idempotency-Key — 멱등 기록이 없어져도 같은 키 재요청을 첫 201로 알아본다 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "claim_key", length = 36, updatable = false)
    private String claimKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 20, nullable = false)
    private CouponIssueState state;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "reserved_at")
    private Instant reservedAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static CouponIssue issue(CouponEvent event, String userId, Instant now) {
        return issue(event, userId, null, now);
    }

    public static CouponIssue issue(CouponEvent event, String userId, String claimKey, Instant now) {
        CouponIssue issue = new CouponIssue();
        issue.claimKey = claimKey;
        issue.couponIssueId = UUID.randomUUID().toString();
        issue.eventId = event.getEventId();
        issue.userId = userId;
        issue.state = CouponIssueState.ISSUED;
        issue.expiresAt = event.getEndsAt();
        issue.issuedAt = now;
        issue.updatedAt = now;
        return issue;
    }

    /** 이 쿠폰을 발급한 요청의 키인가 */
    public boolean issuedBy(String key) {
        return claimKey != null && claimKey.equals(key);
    }

    /** 응답용 상태 — 저장값이 ISSUED라도 종료가 지났으면 EXPIRED(종료 스윕 전이라도, CPN-03) */
    public CouponIssueState visibleState(Instant now) {
        return state == CouponIssueState.ISSUED && !now.isBefore(expiresAt) ? CouponIssueState.EXPIRED : state;
    }
}

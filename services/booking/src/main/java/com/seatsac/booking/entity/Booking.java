package com.seatsac.booking.entity;

import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.enums.BookingStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 예매 — ERD 3.3 / 명세 7장. 저장 시각은 모두 UTC, 응답은 common {@code Times.toApi}로 +09:00.
 * setter 없음. 생성은 {@link #pending}, 상태 전이는 트랜잭션 서비스의 조건부 UPDATE로만 한다.
 */
@Entity
@Table(name = "booking")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "booking_id")
    private Long bookingId;

    /** Cognito sub. DDL이 CHAR(36)이라 JDBC 타입을 CHAR로 맞춘다 (ddl-auto=validate) */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", length = 36, nullable = false, updatable = false)
    private String userId;

    /** catalog.screening 참조 (FK 없음) */
    @Column(name = "screening_id", nullable = false, updatable = false)
    private Long screeningId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private BookingStatus status;

    /** 선점 ID(UUID). Valkey 선점 키의 저장 값. 어떤 응답에도 넣지 않는다 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "hold_id", length = 36, nullable = false, unique = true, updatable = false)
    private String holdId;

    /** 결제 ID(UUID). 예매당 1회 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "current_payment_id", length = 36, unique = true)
    private String currentPaymentId;

    @Column(name = "seat_count", nullable = false, updatable = false)
    private Integer seatCount;

    @Column(name = "amount", nullable = false, updatable = false)
    private Integer amount;

    /** 좌석 번호 목록 (["J7","J8"]). 생성 후 변경하지 않는다 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "seat_labels", nullable = false, updatable = false)
    private List<String> seatLabels;

    /** 선점 만료 (UTC) */
    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 선점 성공 직후의 PENDING 예매. amount는 좌석 수로 계산하고, 저장 뒤에는 세지 않는다.
     *
     * @param now        흐름에서 Clock으로 한 번 구한 값
     * @param expiresAt  {@code now + }{@link BookingPolicy#HOLD_TTL}
     * @throws IllegalArgumentException 좌석 수가 1~4가 아니거나 {@code seatLabels} 크기와 다를 때
     *                                  (사용자 입력 검증은 흐름 서비스가 먼저 하고 400으로 돌려준다)
     */
    public static Booking pending(String userId, Long screeningId, String holdId, List<String> seatLabels,
                                  int seatCount, Instant expiresAt, Instant now) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(screeningId, "screeningId");
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(seatLabels, "seatLabels");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(now, "now");
        if (!BookingPolicy.isAllowedSeatCount(seatCount)) {
            throw new IllegalArgumentException("seatCount=" + seatCount);
        }
        if (seatLabels.size() != seatCount) {
            // 좌석 수와 번호 목록이 어긋나면 amount가 틀어진다. DB에 CHECK 제약이 없어 여기서 막는다
            throw new IllegalArgumentException("seatLabels=" + seatLabels.size() + " seatCount=" + seatCount);
        }

        Booking booking = new Booking();
        booking.userId = userId;
        booking.screeningId = screeningId;
        booking.status = BookingStatus.PENDING;
        booking.holdId = holdId;
        booking.seatCount = seatCount;
        booking.amount = BookingPolicy.amountOf(seatCount);
        booking.seatLabels = List.copyOf(seatLabels);
        booking.expiresAt = expiresAt;
        booking.createdAt = now;
        booking.updatedAt = now;
        return booking;
    }

    /** 방어 복사 — JSON 컬럼 값을 바깥에서 바꾸지 못하게 한다 (@Getter 대신 직접 둔다) */
    public List<String> getSeatLabels() {
        return seatLabels == null ? List.of() : List.copyOf(seatLabels);
    }

    /** 본인 예매인지 — 문자열 비교 (명세 2장) */
    public boolean isOwnedBy(String candidateUserId) {
        return userId.equals(candidateUserId);
    }

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isConfirmed() {
        return status == BookingStatus.CONFIRMED;
    }

    public boolean isCanceled() {
        return status == BookingStatus.CANCELED;
    }
}

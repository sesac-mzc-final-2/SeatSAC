package com.seatsac.booking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 점유 중(PENDING·CONFIRMED)인 좌석 — ERD 3.3. 점유가 끝나면 같은 트랜잭션에서 삭제한다.
 * {@code UNIQUE (screening_id, seat_id)}가 이중 예매의 최종 방어선이다.
 */
@Entity
@Table(name = "booking_seat")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BookingSeat {

    @EmbeddedId
    private Pk id;

    /** catalog.screening 참조 (FK 없음). 유일 제약의 앞 컬럼 */
    @Column(name = "screening_id", nullable = false, updatable = false)
    private Long screeningId;

    public static BookingSeat of(Long bookingId, Long screeningId, Long seatId) {
        BookingSeat seat = new BookingSeat();
        seat.id = new Pk(bookingId, seatId);
        seat.screeningId = screeningId;
        return seat;
    }

    public Long getBookingId() {
        return id.bookingId();
    }

    public Long getSeatId() {
        return id.seatId();
    }

    /** 복합 PK (booking_id, seat_id) */
    @Embeddable
    public record Pk(
        @Column(name = "booking_id", nullable = false, updatable = false) Long bookingId,
        @Column(name = "seat_id", nullable = false, updatable = false) Long seatId
    ) implements Serializable {
    }
}

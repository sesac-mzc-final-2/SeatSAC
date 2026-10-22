package com.seatsac.booking.repository;

import com.seatsac.booking.entity.BookingSeat;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 점유 좌석 — 유일 제약 (screening_id, seat_id) 위반이 이중 예매의 최종 방어선이다. */
public interface BookingSeatRepository extends JpaRepository<BookingSeat, BookingSeat.Pk> {

    List<BookingSeat> findByScreeningId(Long screeningId);

    long deleteByIdBookingId(Long bookingId);
}

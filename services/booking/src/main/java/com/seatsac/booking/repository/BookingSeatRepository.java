package com.seatsac.booking.repository;

import com.seatsac.booking.entity.BookingSeat;
import com.seatsac.booking.enums.BookingStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 점유 좌석 — 유일 제약 (screening_id, seat_id) 위반이 이중 예매의 최종 방어선이다. */
public interface BookingSeatRepository extends JpaRepository<BookingSeat, BookingSeat.Pk> {

    List<BookingSeat> findByScreeningId(Long screeningId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from BookingSeat bs where bs.id.bookingId = :bookingId")
    int deleteByBookingId(@Param("bookingId") Long bookingId);

    @Query("select bs.id.seatId from BookingSeat bs where bs.id.bookingId = :bookingId")
    List<Long> findSeatIdsByBookingId(@Param("bookingId") Long bookingId);

    /** 좌석 현황·409 reason 판정용 — uk_booking_seat_screening_seat 범위만 읽는다 */
    @Query("""
        select bs.id.seatId
          from BookingSeat bs
          join Booking b on b.bookingId = bs.id.bookingId
         where bs.screeningId = :screeningId
           and bs.id.seatId in :seatIds
           and b.status = :status
        """)
    List<Long> findSeatIdsByScreeningIdAndSeatIdInAndStatus(@Param("screeningId") Long screeningId,
                                                            @Param("seatIds") List<Long> seatIds,
                                                            @Param("status") BookingStatus status);

    /** BKG-01 좌석 현황 — 점유 중(PENDING·CONFIRMED)인 좌석과 그 상태 */
    @Query("""
        select bs.id.seatId, b.status
          from BookingSeat bs
          join Booking b on b.bookingId = bs.id.bookingId
         where bs.screeningId = :screeningId
        """)
    List<Object[]> findSeatStatusesByScreeningId(@Param("screeningId") Long screeningId);
}

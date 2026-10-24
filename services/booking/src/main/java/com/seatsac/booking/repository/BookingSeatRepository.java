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

    /**
     * 회차별 선점·확정 좌석 수 (내부 API, 호출자 catalog). <b>한 번의 그룹 집계</b>로 끝낸다 —
     * catalog는 300ms 안에 받아야 하고, 넘기면 잔여석을 총 좌석 수로 대체한다.
     *
     * <p>{@code uk_booking_seat_screening_seat} 범위만 읽고, 예매가 없는 회차는 결과에 안 나온다
     * (부르는 쪽이 0으로 채운다).
     *
     * @return {@code [screeningId, status, count]} 행들
     */
    @Query("""
        select bs.screeningId, b.status, count(bs)
          from BookingSeat bs
          join Booking b on b.bookingId = bs.id.bookingId
         where bs.screeningId in :screeningIds
           and b.status in :statuses
         group by bs.screeningId, b.status
        """)
    List<Object[]> countSeatsByScreeningIdInAndStatusIn(
        @Param("screeningIds") List<Long> screeningIds,
        @Param("statuses") List<BookingStatus> statuses);
}

package com.seatsac.booking.repository;

import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.BookingStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 예매 — 상태 전이는 모두 <b>조건부 UPDATE</b>로만 한다(지침 4장). 영향 행이 0이면 다른 경로가
 * 먼저 처리한 것으로 보고, 시각은 앱 {@code Clock}으로 구한 {@code :now}를 넘긴다(SQL NOW() 금지).
 */
public interface BookingRepository extends JpaRepository<Booking, Long> {

    /** 고아 선점 키 확인 — uk_booking_hold_id */
    Optional<Booking> findByHoldId(String holdId);

    /**
     * BKG-03 선점 해제. 결제를 요청한 예매({@code current_payment_id} 있음)는 해제하지 않는다.
     *
     * @return 1이면 내가 처리, 0이면 이미 다른 경로(확정·만료)가 처리했다
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Booking b
           set b.status = com.seatsac.booking.enums.BookingStatus.CANCELED,
               b.canceledAt = :now,
               b.updatedAt = :now
         where b.bookingId = :bookingId
           and b.status = com.seatsac.booking.enums.BookingStatus.PENDING
           and b.currentPaymentId is null
        """)
    int cancelPendingHold(@Param("bookingId") Long bookingId, @Param("now") Instant now);

    /**
     * BKG-04 만료. 조건부 UPDATE라 세 경로(keyspace·장부 스캔·RDS 스캔)와 여러 파드가 겹쳐도
     * 한 번만 처리된다. 확정과 경쟁해도 먼저 커밋한 쪽만 이긴다.
     *
     * <p>{@code expiresAt <= :now}도 함께 본다 — 장부 점수가 틀어져 아직 만료가 아닌 선점이
     * 들어와도 지우지 않는다.
     *
     * @return 1이면 내가 처리, 0이면 이미 다른 경로가 처리했거나 아직 만료가 아니다
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Booking b
           set b.status = com.seatsac.booking.enums.BookingStatus.EXPIRED,
               b.updatedAt = :now
         where b.bookingId = :bookingId
           and b.status = com.seatsac.booking.enums.BookingStatus.PENDING
           and b.expiresAt <= :now
        """)
    int expirePendingHold(@Param("bookingId") Long bookingId, @Param("now") Instant now);

    /**
     * 안전망 2 — 만료 시각이 지난 PENDING 예매. {@code idx_booking_status_expires_at} 범위만 읽는다.
     *
     * <p>한 번에 가져오는 양을 묶어 둔다. 노드 0대 구간(ADR-34) 뒤 첫 스윕에서는 밀린 예매가
     * 한꺼번에 걸리는데, 전부 한 주기에 처리하면 Hikari 연결 5개를 다 쓴다.
     */
    @Query("""
        select b.bookingId from Booking b
         where b.status = com.seatsac.booking.enums.BookingStatus.PENDING
           and b.expiresAt <= :now
         order by b.expiresAt asc
        """)
    List<Long> findExpiredPendingIds(@Param("now") Instant now, Pageable pageable);
     * BKG-14 사용자 예매 내역 (호출자 user). 최신순, 페이징.
     *
     * <p>{@code statuses}로 거른다 — 생략 시 호출자가 「PENDING 제외 전부」를 넘긴다.
     * {@code booking} 테이블만 읽는다: 좌석 번호는 {@code seat_labels}에 있고, 끝난 예매는
     * {@code booking_seat} 행이 없다.
     */
    @Query("""
        select b from Booking b
         where b.userId = :userId
           and b.status in :statuses
         order by b.createdAt desc, b.bookingId desc
        """)
    Page<Booking> findByUserIdAndStatusIn(@Param("userId") String userId,
                                          @Param("statuses") List<BookingStatus> statuses,
                                          Pageable pageable);
}

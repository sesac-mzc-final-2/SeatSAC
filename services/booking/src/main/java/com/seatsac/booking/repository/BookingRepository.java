package com.seatsac.booking.repository;

import com.seatsac.booking.entity.Booking;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 예매 — 상태 전이는 모두 조건부 UPDATE로만 한다(지침 4장). 전이 쿼리는 쓰는 단계(3.2.2~)에서
 * {@code @Modifying @Query}로 추가하고, 조건·시각 값은 {@code :now} 파라미터로 넘긴다.
 */
public interface BookingRepository extends JpaRepository<Booking, Long> {

    /** 고아 선점 키 확인 — uk_booking_hold_id */
    Optional<Booking> findByHoldId(String holdId);
}

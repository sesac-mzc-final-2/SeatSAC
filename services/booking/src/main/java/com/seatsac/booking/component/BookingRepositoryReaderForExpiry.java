package com.seatsac.booking.component;

import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.booking.repository.BookingRepository;
import com.seatsac.booking.repository.BookingSeatRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 만료 스캔이 쓰는 읽기 전용 조회. 스캔은 요청 밖에서 돌기 때문에 트랜잭션 경계를
 * 조회 단위로 짧게 끊어 Hikari 연결(5개)을 오래 붙잡지 않는다.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BookingRepositoryReaderForExpiry {

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;

    /** 장부 멤버의 holdId → PENDING 예매. 없으면 고아 멤버다 */
    public Optional<Long> findPendingBookingIdByHoldId(String holdId) {
        return bookingRepository.findByHoldId(holdId)
            .filter(booking -> booking.getStatus() == BookingStatus.PENDING)
            .map(Booking::getBookingId);
    }

    public List<Long> findExpiredPendingIds(Instant now, Pageable pageable) {
        return bookingRepository.findExpiredPendingIds(now, pageable);
    }

    /** keyspace 빠른 경로 — 만료된 좌석 키로 예매를 찾는다 */
    public Optional<Long> findBookingIdBySeat(Long screeningId, Long seatId) {
        return bookingSeatRepository.findBookingIdByScreeningIdAndSeatId(screeningId, seatId);
    }
}

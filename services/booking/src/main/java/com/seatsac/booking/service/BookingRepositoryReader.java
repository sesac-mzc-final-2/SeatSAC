package com.seatsac.booking.service;

import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.booking.repository.BookingRepository;
import com.seatsac.booking.repository.BookingSeatRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 읽기 전용 조회 — 흐름 서비스가 판단에 쓰는 SQL만 모은다(지침 4장의 트랜잭션 전용 서비스와 같은 층).
 * 상태를 바꾸지 않으므로 {@code readOnly = true}다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BookingRepositoryReader {

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;

    /**
     * 고아 선점 키 판별 — {@code user-hold}의 holdId로 아직 유효한 PENDING 예매를 찾는다.
     * 없으면 고아 키다(명세 5장 B3).
     */
    public Optional<Booking> findPendingByHoldId(String holdId, Instant now) {
        return bookingRepository.findByHoldId(holdId)
            .filter(booking -> booking.getStatus() == BookingStatus.PENDING)
            .filter(booking -> !booking.isExpiredAt(now));
    }

    /** 요청 좌석 중 해당 상태로 점유된 좌석 — SEAT_UNAVAILABLE의 reason(HELD·RESERVED) 판정 */
    public List<Long> findSeatIdsByStatus(Long screeningId, List<Long> seatIds, BookingStatus status) {
        if (seatIds.isEmpty()) {
            return List.of();
        }
        return bookingSeatRepository.findSeatIdsByScreeningIdAndSeatIdInAndStatus(screeningId, seatIds, status);
    }

    public Optional<Booking> findById(Long bookingId) {
        return bookingRepository.findById(bookingId);
    }

    /** 점유 중인 좌석 ID (응답용). 끝난 예매는 행이 없어 빈 목록 */
    public List<Long> findOccupiedSeatIds(Long bookingId) {
        return bookingSeatRepository.findSeatIdsByBookingId(bookingId);
    }
}

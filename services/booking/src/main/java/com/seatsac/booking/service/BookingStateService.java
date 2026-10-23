package com.seatsac.booking.service;

import com.seatsac.booking.common.BookingErrorCode;
import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.entity.BookingSeat;
import com.seatsac.booking.repository.BookingRepository;
import com.seatsac.booking.repository.BookingSeatRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 트랜잭션 전용 서비스 — SQL만 한다 (지침 4장).
 * 이 안에서 Valkey·HTTP·SQS를 부르지 않는다. Valkey 선점·발행 순서는 흐름 서비스(SeatHoldService 등)가 맡는다.
 * 상태 전이(해제·확정+아웃박스·실패·만료)는 3.2.2 이후 같은 클래스에 조건부 UPDATE로 추가한다.
 */
@Service
@RequiredArgsConstructor
public class BookingStateService {

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;

    /**
     * BKG-02 6단계 — PENDING 예매와 점유 좌석을 한 트랜잭션으로 저장한다 (명세 5장).
     * 유일 제약 (screening_id, seat_id) 위반은 다른 사용자가 먼저 잡은 것이므로 409 SEAT_UNAVAILABLE로 바꾼다.
     * 부르는 쪽은 이 예외를 받으면 {@code release.lua}로 Valkey 키를 정리하고 이벤트를 발행하지 않는다.
     *
     * @param now 흐름에서 Clock으로 한 번 구한 값. {@code expiresAt = now + HOLD_TTL}
     */
    @Transactional
    public Booking createPending(PendingBooking command, Instant now) {
        Booking booking = bookingRepository.saveAndFlush(Booking.pending(
            command.userId(), command.screeningId(), command.holdId(),
            command.seatLabels(), command.seatIds().size(), command.expiresAt(), now));

        List<BookingSeat> seats = command.seatIds().stream()
            .map(seatId -> BookingSeat.of(booking.getBookingId(), command.screeningId(), seatId))
            .toList();
        try {
            bookingSeatRepository.saveAll(seats);
            bookingSeatRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // 어느 좌석이 걸렸는지(HELD·RESERVED)는 여기서 알 수 없다. details의 좌석별 reason은
            // 좌석 상태를 아는 흐름 서비스가 3.2.2에서 채운다.
            throw BookingErrorCode.SEAT_UNAVAILABLE.exception();
        }
        return booking;
    }

    /**
     * BKG-03 3단계 — 조건부 UPDATE로 CANCELED로 바꾸고 점유 좌석을 지운다 (명세 5장).
     * 0행이면 그 사이 확정·만료가 먼저 처리한 것이므로 부르는 쪽이 현재 상태를 읽어 409를 돌려준다.
     *
     * @return 처리했으면 true
     */
    @Transactional
    public boolean releaseHold(Long bookingId, Instant now) {
        if (bookingRepository.cancelPendingHold(bookingId, now) == 0) {
            return false;
        }
        bookingSeatRepository.deleteByBookingId(bookingId);
        return true;
    }

    /**
     * 선점 저장 입력. 좌석 수·금액은 {@code seatIds}에서 계산하고 이후 다시 세지 않는다.
     *
     * @param seatLabels Pod 캐시의 좌석 배치에서 찾은 좌석 번호 (seatIds와 같은 순서)
     */
    public record PendingBooking(
        String userId,
        Long screeningId,
        String holdId,
        List<Long> seatIds,
        List<String> seatLabels,
        Instant expiresAt
    ) {
        public PendingBooking {
            seatIds = List.copyOf(seatIds);
            seatLabels = List.copyOf(seatLabels);
        }
    }
}

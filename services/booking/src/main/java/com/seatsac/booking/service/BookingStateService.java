package com.seatsac.booking.service;

import com.seatsac.booking.common.BookingErrorCode;
import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.entity.BookingSeat;
import com.seatsac.booking.entity.OutboxMessage;
import com.seatsac.booking.enums.OutboxEventType;
import com.seatsac.booking.repository.BookingRepository;
import com.seatsac.booking.repository.BookingSeatRepository;
import com.seatsac.booking.repository.OutboxMessageRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
    private final OutboxMessageRepository outboxMessageRepository;

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

    /**
     * BKG-10 1단계 — 결제 ID를 붙인다. 예매당 1회(ADR-24)를 조건부 UPDATE로 막는다.
     *
     * @return 붙였으면 true. false면 이미 결제를 요청했거나 PENDING이 아니거나 선점이 만료됐다
     */
    @Transactional
    public boolean attachPayment(Long bookingId, String paymentId, Instant now) {
        return bookingRepository.attachPayment(bookingId, paymentId, now) == 1;
    }

    /**
     * BKG-06 확정 — <b>한 트랜잭션</b>에서 상태 전이와 아웃박스 INSERT를 함께 한다.
     * 확정만 커밋되고 알림 행이 없으면 알림이 영영 안 가고, 반대면 없는 예매의 알림이 간다.
     *
     * <p>{@code expiresAt > :now}라 승인을 받았는데 그 사이 만료됐으면 0행이다 —
     * 그때는 아웃박스도 쓰지 않고 부르는 쪽이 결제를 취소(환불)한다.
     *
     * @param payload 알림 본문 JSON (Catalog 조회 실패 시 이름·시각이 null인 채로 들어온다)
     * @return 확정했으면 true
     */
    @Transactional
    public boolean confirmWithOutbox(Long bookingId, String messageKey, String payload,
                                     String traceId, Instant now) {
        if (bookingRepository.confirmPending(bookingId, now) == 0) {
            return false;
        }
        outboxMessageRepository.save(OutboxMessage.pending(messageKey,
            OutboxEventType.BOOKING_CONFIRMED, bookingId, payload, traceId, now));
        return true;
    }

    /**
     * BKG-10 결제 거절 → {@code PAYMENT_FAILED}. 좌석 행을 지워 다른 사용자가 바로 잡을 수 있게 한다.
     *
     * @return 처리했으면 true (0행이면 이미 만료·취소가 먼저 끝났다)
     */
    @Transactional
    public boolean failPayment(Long bookingId, Instant now) {
        if (bookingRepository.failPending(bookingId, now) == 0) {
            return false;
        }
        bookingSeatRepository.deleteByBookingId(bookingId);
        return true;
    }

    /**
     * BKG-07 확정 예매 취소 → {@code CANCELED} + 좌석 행 삭제.
     * 이미 취소됐으면 0행이라 부르는 쪽이 멱등하게 응답한다.
     */
    @Transactional
    public boolean cancelConfirmed(Long bookingId, Instant now) {
        if (bookingRepository.cancelConfirmed(bookingId, now) == 0) {
            return false;
        }
        bookingSeatRepository.deleteByBookingId(bookingId);
        return true;
    }


    /**
     * BKG-04 1단계 — 조건부 UPDATE로 EXPIRED로 바꾸고 점유 좌석을 지운다.
     * 좌석 ID는 지우기 <b>전에</b> 읽어 돌려준다 — 커밋 후 발행·Valkey 정리에 쓴다.
     *
     * @return 내가 처리했으면 만료된 선점 정보, 이미 다른 경로가 처리했으면 비어 있음
     */
    @Transactional
    public Optional<ExpiredHold> expireHold(Long bookingId, Instant now) {
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null) {
            return Optional.empty();
        }
        List<Long> seatIds = bookingSeatRepository.findSeatIdsByBookingId(bookingId);
        if (bookingRepository.expirePendingHold(bookingId, now) == 0) {
            return Optional.empty();
        }
        bookingSeatRepository.deleteByBookingId(bookingId);
        return Optional.of(new ExpiredHold(bookingId, booking.getScreeningId(), booking.getHoldId(),
            booking.getUserId(), seatIds, booking.getExpiresAt()));
    }

    /** 만료 처리한 선점 — 커밋 후 할 일(발행·Valkey 정리·지연 기록)에 필요한 값만 */
    public record ExpiredHold(
        Long bookingId,
        Long screeningId,
        String holdId,
        String userId,
        List<Long> seatIds,
        Instant expiresAt
    ) {
        public ExpiredHold {
            seatIds = List.copyOf(seatIds);
        }
    }
}

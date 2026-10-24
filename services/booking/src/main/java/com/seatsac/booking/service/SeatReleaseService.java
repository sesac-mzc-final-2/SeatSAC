package com.seatsac.booking.service;

import com.seatsac.booking.common.BookingErrorCode;
import com.seatsac.booking.component.SeatEventPublisher;
import com.seatsac.booking.component.SeatHoldStrategy;
import com.seatsac.booking.dto.response.BookingCancelResponse;
import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.booking.enums.SeatEventType;
import com.seatsac.common.core.error.ErrorDetail;
import com.seatsac.common.core.time.Times;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * BKG-03 선점 해제 흐름 — 명세 5장. 트랜잭션 없음.
 *
 * <pre>
 * 1. 본인 예매 확인 (user_id = X-User-Id)
 * 2. 결제를 요청했으면 409 PAYMENT_IN_PROGRESS
 * 3. tx  조건부 UPDATE CANCELED + booking_seat 삭제 → 커밋. 0행이면 현재 상태로 409
 * 4. 커밋 후  release.lua → INCR seat-seq → PUBLISH RELEASED
 * </pre>
 *
 * 조건부 UPDATE라 같은 요청이 여러 번 와도 결과가 같다(멱등 키는 선택).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatReleaseService {

    private final Clock clock;
    private final BookingRepositoryReader bookingReader;
    private final BookingStateService bookingState;
    private final SeatHoldStrategy seatHold;
    private final SeatEventPublisher eventPublisher;

    public BookingCancelResponse release(String userId, Long bookingId) {
        Instant now = clock.instant();
        Booking booking = bookingReader.findById(bookingId)
            .orElseThrow(() -> BookingErrorCode.BOOKING_NOT_FOUND.exception());

        if (!booking.isOwnedBy(userId)) {
            throw BookingErrorCode.NOT_OWNER.exception();
        }
        if (booking.getCurrentPaymentId() != null) {
            throw BookingErrorCode.PAYMENT_IN_PROGRESS.exception(ErrorDetail.of("status",
                booking.getStatus().name(), BookingErrorCode.PAYMENT_IN_PROGRESS.name()));
        }

        List<Long> seatIds = bookingReader.findOccupiedSeatIds(bookingId);
        if (!bookingState.releaseHold(bookingId, now)) {
            // 그 사이 확정·만료·취소가 먼저 끝났다. 현재 상태를 읽어 알려 준다
            BookingStatus current = bookingReader.findById(bookingId)
                .map(Booking::getStatus).orElse(booking.getStatus());
            throw BookingErrorCode.BOOKING_NOT_PENDING.exception(ErrorDetail.of("status",
                current.name(), BookingErrorCode.BOOKING_NOT_PENDING.name()));
        }

        // 커밋 후: Valkey 정리 → 발행. 이미 CANCELED로 커밋됐으니 정리 실패로 500을 내지 않는다
        // (좌석 키는 TTL 300초로, 장부 멤버는 만료 스캔이 치운다)
        try {
            seatHold.release(booking.getScreeningId(), userId, booking.getHoldId(), seatIds);
        } catch (DataAccessException e) {
            log.warn("해제 후 Valkey 정리 실패 — TTL·만료 스캔에 맡긴다 bookingId={} screeningId={} seats={}",
                bookingId, booking.getScreeningId(), seatIds, e);
        }
        eventPublisher.publish(SeatEventType.RELEASED, booking.getScreeningId(), seatIds, now);

        return bookingReader.findById(bookingId).map(BookingCancelResponse::ofHoldRelease)
            .orElseGet(() -> BookingCancelResponse.ofHoldRelease(bookingId, Times.toApi(now)));
    }
}

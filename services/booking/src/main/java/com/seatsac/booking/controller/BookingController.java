package com.seatsac.booking.controller;

import com.seatsac.booking.dto.response.HoldReleaseResponse;
import com.seatsac.booking.service.SeatReleaseService;
import com.seatsac.common.web.user.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예매 API — `contracts/openapi/booking.openapi.yaml`.
 * 결제(BKG-10)·취소(BKG-07)·단건 조회는 3.2.6에서 붙인다.
 */
@RestController
@RequiredArgsConstructor
public class BookingController {

    private final SeatReleaseService seatReleaseService;

    /** BKG-03 선점 해제. 조건부 UPDATE라 자체로 멱등하므로 {@code Idempotency-Key}는 선택 */
    @DeleteMapping("/bookings/{bookingId}/hold")
    public HoldReleaseResponse releaseHold(@CurrentUser String userId, @PathVariable Long bookingId) {
        return seatReleaseService.release(userId, bookingId);
    }
}

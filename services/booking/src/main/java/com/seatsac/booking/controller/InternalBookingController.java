package com.seatsac.booking.controller;

import com.seatsac.booking.common.BookingErrorCode;
import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.dto.response.SeatCountsResponse;
import com.seatsac.booking.service.SeatCountService;
import com.seatsac.common.core.error.ErrorDetail;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내부 API — `contracts/openapi/internal/booking.internal.openapi.yaml`.
 *
 * <p>Gateway는 {@code /api/internal/**}을 404로 막고 NetworkPolicy가 호출자를 제한한다
 * (catalog·user만). 사용자 헤더를 보지 않는다 — 호출자는 서비스다.
 */
@RestController
@RequiredArgsConstructor
public class InternalBookingController {

    private final SeatCountService seatCountService;

    /**
     * 회차별 선점·확정 좌석 수 (호출자 catalog, CAT-03 2차). 요청한 회차는 예매가 없어도 0으로 담는다.
     *
     * <p>catalog의 예산이 <b>300ms</b>라 집계는 한 번의 그룹 쿼리로 끝낸다. 상한(100개)을 넘기면
     * 400 {@code VALIDATION_FAILED}다 — 잘라서 처리하면 catalog가 일부만 받은 것을 알 수 없다.
     */
    @GetMapping("/internal/screenings/seat-counts")
    public SeatCountsResponse seatCounts(@RequestParam("ids") List<Long> ids) {
        validate(ids);
        return seatCountService.countFor(ids);
    }

    private static void validate(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw BookingErrorCode.INVALID_IDS.exception(
                ErrorDetail.of("ids", null, "REQUIRED"));
        }
        if (ids.size() > BookingPolicy.MAX_SEAT_COUNT_IDS) {
            throw BookingErrorCode.INVALID_IDS.exception(
                ErrorDetail.of("ids", ids.size(), "TOO_MANY"));
        }
        if (ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw BookingErrorCode.INVALID_IDS.exception(
                ErrorDetail.of("ids", null, "NOT_POSITIVE"));
        }
    }
}

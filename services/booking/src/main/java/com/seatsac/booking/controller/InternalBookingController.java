package com.seatsac.booking.controller;

import com.seatsac.booking.common.BookingErrorCode;
import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.dto.response.BookingSummaryResponse;
import com.seatsac.booking.dto.response.SeatCountsResponse;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.booking.service.BookingHistoryService;
import com.seatsac.booking.service.SeatCountService;
import com.seatsac.common.core.PageResponse;
import com.seatsac.common.core.error.ErrorDetail;
import java.util.List;
import java.util.regex.Pattern;
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

    /** Cognito sub (UUID). user 서비스가 넘기는 값이지만 형식은 우리가 다시 본다 */
    private static final Pattern USER_ID_FORMAT = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final SeatCountService seatCountService;
    private final BookingHistoryService bookingHistoryService;

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

    /**
     * BKG-14 사용자 예매 내역 (호출자 user, USR-04). 최신순 페이징.
     *
     * <p>{@code status}를 생략하면 <b>{@code PENDING}을 뺀 전부</b>다 — 선점 중인 예매는 내역이
     * 아니라 진행 중인 결제이고 5분 뒤 사라질 수도 있다. {@code PENDING}을 콕 집어 요청하면 400이다.
     */
    @GetMapping("/internal/bookings")
    public PageResponse<BookingSummaryResponse> bookingsByUser(
        @RequestParam("userId") String userId,
        @RequestParam(value = "status", required = false) BookingStatus status,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "20") int size) {

        validateUserId(userId);
        validateStatus(status);
        validatePaging(page, size);
        return bookingHistoryService.findByUser(userId, status, page, size);
    }

    /**
     * 내부 API는 인증을 쓰지 않는다 — {@code userId}는 호출자가 넘기는 <b>쿼리 파라미터</b>다.
     * 형식이 틀리면 인증 실패(401)가 아니라 400 {@code VALIDATION_FAILED}다(계약의 400 설명).
     */
    private static void validateUserId(String userId) {
        if (userId == null || !USER_ID_FORMAT.matcher(userId).matches()) {
            throw BookingErrorCode.INVALID_USER_ID.exception(
                ErrorDetail.of("userId", null, BookingErrorCode.INVALID_USER_ID.name()));
        }
    }

    /** 내역에 없는 상태({@code PENDING})를 요청하면 거절한다 — 조용히 빈 목록을 주면 오해한다 */
    private static void validateStatus(BookingStatus status) {
        if (status != null && !BookingHistoryService.isHistoryStatus(status)) {
            throw BookingErrorCode.INVALID_STATUS.exception(
                ErrorDetail.of("status", status.name(), "NOT_IN_HISTORY"));
        }
    }

    private static void validatePaging(int page, int size) {
        if (page < 0) {
            throw BookingErrorCode.INVALID_PAGE.exception(ErrorDetail.of("page", page, "NEGATIVE"));
        }
        if (size < 1 || size > BookingPolicy.MAX_PAGE_SIZE) {
            throw BookingErrorCode.INVALID_PAGE.exception(
                ErrorDetail.of("size", size, "OUT_OF_RANGE"));
        }
    }
}

package com.seatsac.user.web;

import com.seatsac.common.core.PageResponse;
import com.seatsac.common.web.user.CurrentUser;
import com.seatsac.user.dto.BookingHistoryStatus;
import com.seatsac.user.dto.MyBooking;
import com.seatsac.user.service.BookingHistoryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** USR-04 — contracts/openapi/user.openapi.yaml {@code /users/me/bookings}. 메서드 이름 = operationId */
@RestController
public class MyBookingController {

    private final BookingHistoryService bookingHistoryService;

    public MyBookingController(BookingHistoryService bookingHistoryService) {
        this.bookingHistoryService = bookingHistoryService;
    }

    @GetMapping("/users/me/bookings")
    public PageResponse<MyBooking> listMyBookings(
        @CurrentUser String userId,
        @RequestParam(required = false) BookingHistoryStatus status,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return bookingHistoryService.listMyBookings(userId, status, page, size);
    }
}

package com.seatsac.booking.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatsac.booking.common.BookingErrorCode;
import com.seatsac.booking.dto.response.BookingCancelResponse;
import com.seatsac.booking.idempotency.IdempotencyStore;
import com.seatsac.booking.service.SeatReleaseService;
import com.seatsac.common.core.error.ErrorDetail;
import com.seatsac.common.core.time.Times;
import com.seatsac.common.test.WithCurrentUser;
import com.seatsac.common.web.CommonWebAutoConfiguration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * BKG-03 선점 해제의 HTTP 층. 응답은 계약의 {@code CancelResult}이고, 결제가 없는 해제라
 * {@code payment}는 본문에서 빠져야 한다(BKG-07 취소에만 담긴다).
 */
@WebMvcTest(controllers = BookingController.class)
@ImportAutoConfiguration(CommonWebAutoConfiguration.class)
class BookingControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-20T03:01:10Z");   // KST 12:01:10

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SeatReleaseService releaseService;

    /**
     * {@code @ControllerAdvice}인 멱등 advice가 함께 올라오는데 이 슬라이스에는 Valkey가 없다.
     * 멱등 동작 자체는 {@code SeatHoldIdempotencyTest}가 본다.
     */
    @MockitoBean
    IdempotencyStore idempotencyStore;

    @Test
    void releaseReturnsCanceledWithoutPaymentField() throws Exception {
        when(releaseService.release(WithCurrentUser.DEFAULT_USER, 5001L))
            .thenReturn(BookingCancelResponse.ofHoldRelease(5001L, Times.toApi(NOW)));

        mvc.perform(delete("/bookings/5001/hold").with(WithCurrentUser.user()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bookingId").value(5001))
            .andExpect(jsonPath("$.status").value("CANCELED"))
            .andExpect(jsonPath("$.canceledAt").value("2026-10-20T12:01:10+09:00"))
            .andExpect(jsonPath("$.payment").doesNotExist());
    }

    @Test
    void releaseWithoutUserHeaderIsUnauthorized() throws Exception {
        mvc.perform(delete("/bookings/5001/hold"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void otherUsersBookingIsForbidden() throws Exception {
        when(releaseService.release(anyString(), anyLong()))
            .thenThrow(BookingErrorCode.NOT_OWNER.exception());

        mvc.perform(delete("/bookings/5001/hold").with(WithCurrentUser.user()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void unknownBookingIsNotFound() throws Exception {
        when(releaseService.release(anyString(), anyLong()))
            .thenThrow(BookingErrorCode.BOOKING_NOT_FOUND.exception());

        mvc.perform(delete("/bookings/5001/hold").with(WithCurrentUser.user()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /** 결제가 진행 중이면 해제하지 않는다 — 409 BOOKING_STATE_CONFLICT */
    @Test
    void paymentInProgressIsConflict() throws Exception {
        when(releaseService.release(anyString(), anyLong())).thenThrow(
            BookingErrorCode.PAYMENT_IN_PROGRESS.exception(
                ErrorDetail.of("status", "PENDING", "PAYMENT_IN_PROGRESS")));

        mvc.perform(delete("/bookings/5001/hold").with(WithCurrentUser.user()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("BOOKING_STATE_CONFLICT"))
            .andExpect(jsonPath("$.details[0].reason").value("PAYMENT_IN_PROGRESS"));
    }

    @Test
    void bookingIdMustBeNumeric() throws Exception {
        mvc.perform(delete("/bookings/abc/hold").with(WithCurrentUser.user()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details[0].field").value("bookingId"));
    }
}

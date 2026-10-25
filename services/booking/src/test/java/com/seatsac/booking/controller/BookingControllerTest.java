package com.seatsac.booking.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatsac.booking.common.BookingErrorCode;
import com.seatsac.booking.dto.response.BookingCancelResponse;
import com.seatsac.booking.idempotency.IdempotencyStore;
import com.seatsac.booking.dto.response.BookingPaymentResponse;
import com.seatsac.booking.dto.response.PaymentView;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.booking.service.BookingCancelService;
import com.seatsac.booking.service.BookingDetailService;
import com.seatsac.booking.service.BookingPaymentService;
import com.seatsac.booking.service.SeatReleaseService;
import com.seatsac.common.core.error.ErrorDetail;
import com.seatsac.common.core.http.Headers;
import com.seatsac.common.core.time.Times;
import com.seatsac.common.test.WithCurrentUser;
import com.seatsac.common.web.CommonWebAutoConfiguration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * BKG-03 선점 해제의 HTTP 층. 응답은 계약의 {@code CancelResult}이고, 결제가 없는 해제라
 * {@code payment}는 본문에서 빠져야 한다(BKG-07 취소에만 담긴다).
 */
@WebMvcTest(controllers = BookingController.class)
@ImportAutoConfiguration(CommonWebAutoConfiguration.class)
class BookingControllerTest {

    /** 멱등 키로 보내는 UUID. 비밀 값이 아니라 요청 식별자다 */
    private static final String HOLD_REQUEST = "11111111-1111-4111-8111-222222222222";

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
    /** 3.2.6 엔드포인트의 흐름은 {@code BookingPaymentFlowTest}가 본다 — 여기서는 HTTP 층만 */
    @MockitoBean
    BookingDetailService bookingDetailService;
    @MockitoBean
    BookingPaymentService bookingPaymentService;
    @MockitoBean
    BookingCancelService bookingCancelService;

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

    // --- BKG-10 결제 (3.2.6) ---

    private static final String PAY_BODY = """
        {"method":"CARD","card":{"number":"4111111111111112","expiry":"12/28","holder":"KIM"}}
        """;

    @Test
    void payReturnsConfirmedWithoutLeakingTheCardNumber() throws Exception {
        when(bookingPaymentService.pay(anyString(), anyLong(), any()))
            .thenReturn(BookingPaymentResponse.builder()
                .bookingId(5001L).status(BookingStatus.CONFIRMED)
                .confirmedAt(Times.toApi(NOW)).seatLabels(List.of("J7", "J8"))
                .payment(PaymentView.of("3f2b8c1e-7a4d-4e9b-9c61-2d5f0a8e7b13", "APPROVED",
                    "1112", null, NOW, null))
                .build());

        mvc.perform(post("/bookings/{id}/pay", 5001L).contentType(MediaType.APPLICATION_JSON)
                .content(PAY_BODY).with(WithCurrentUser.user())
                .with(WithCurrentUser.idempotencyKey(HOLD_REQUEST)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.payment.cardLast4").value("1112"))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("4111111111111112"))));
    }

    /** 거절도 200이다 — 화면은 status로 분기한다 */
    @Test
    void declinedPaymentIsAlsoTwoHundred() throws Exception {
        when(bookingPaymentService.pay(anyString(), anyLong(), any()))
            .thenReturn(BookingPaymentResponse.builder()
                .bookingId(5001L).status(BookingStatus.PAYMENT_FAILED)
                .seatLabels(List.of("J7"))
                .payment(PaymentView.of("3f2b8c1e-7a4d-4e9b-9c61-2d5f0a8e7b13", "FAILED",
                    "0000", "CARD_DECLINED", null, null))
                .build());

        mvc.perform(post("/bookings/{id}/pay", 5001L).contentType(MediaType.APPLICATION_JSON)
                .content(PAY_BODY).with(WithCurrentUser.user())
                .with(WithCurrentUser.idempotencyKey(HOLD_REQUEST)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PAYMENT_FAILED"))
            .andExpect(jsonPath("$.payment.failureCode").value("CARD_DECLINED"));
    }

    /** 멱등 키는 필수다 (명세 2장) */
    @Test
    void payWithoutIdempotencyKeyIsRejected() throws Exception {
        mvc.perform(post("/bookings/{id}/pay", 5001L).contentType(MediaType.APPLICATION_JSON)
                .content(PAY_BODY).with(WithCurrentUser.user()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details[0].field").value(Headers.IDEMPOTENCY_KEY));

        verify(bookingPaymentService, never()).pay(anyString(), anyLong(), any());
    }

    /** 결과를 모르면 503 — 화면은 단건 조회로 재확인한다 */
    @Test
    void unknownPaymentResultIsServiceUnavailable() throws Exception {
        when(bookingPaymentService.pay(anyString(), anyLong(), any()))
            .thenThrow(BookingErrorCode.PAYMENT_RESULT_UNKNOWN.exception(
                ErrorDetail.of("paymentId", "3f2b8c1e-7a4d-4e9b-9c61-2d5f0a8e7b13")));

        mvc.perform(post("/bookings/{id}/pay", 5001L).contentType(MediaType.APPLICATION_JSON)
                .content(PAY_BODY).with(WithCurrentUser.user())
                .with(WithCurrentUser.idempotencyKey(HOLD_REQUEST)))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("PAYMENT_RESULT_UNKNOWN"))
            .andExpect(jsonPath("$.details[0].field").value("paymentId"));
    }

    @Test
    void cardFormatIsValidatedBeforeService() throws Exception {
        mvc.perform(post("/bookings/{id}/pay", 5001L).contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"CARD\",\"card\":{\"number\":\"4111\",\"expiry\":\"12/28\",\"holder\":\"KIM\"}}")
                .with(WithCurrentUser.user()).with(WithCurrentUser.idempotencyKey(HOLD_REQUEST)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(bookingPaymentService, never()).pay(anyString(), anyLong(), any());
    }

    // --- BKG-07 취소 · 단건 조회 ---

    @Test
    void cancelReturnsCanceledWithRefund() throws Exception {
        when(bookingCancelService.cancel(anyString(), anyLong()))
            .thenReturn(BookingCancelResponse.builder()
                .bookingId(5001L).status(BookingStatus.CANCELED).canceledAt(Times.toApi(NOW))
                .payment(PaymentView.of("3f2b8c1e-7a4d-4e9b-9c61-2d5f0a8e7b13", "CANCELED",
                    "1112", null, NOW, NOW))
                .build());

        mvc.perform(post("/bookings/{id}/cancel", 5001L).with(WithCurrentUser.user()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELED"))
            .andExpect(jsonPath("$.payment.status").value("CANCELED"));
    }

    @Test
    void detailRequiresOwnership() throws Exception {
        when(bookingDetailService.get(anyString(), anyLong()))
            .thenThrow(BookingErrorCode.NOT_OWNER.exception());

        mvc.perform(get("/bookings/{id}", 5001L).with(WithCurrentUser.user()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void bookingIdMustBeNumeric() throws Exception {
        mvc.perform(delete("/bookings/abc/hold").with(WithCurrentUser.user()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details[0].field").value("bookingId"));
    }
}

package com.seatsac.booking.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatsac.booking.common.BookingErrorCode;
import com.seatsac.booking.dto.request.SeatHoldRequest;
import com.seatsac.booking.dto.response.SeatHoldResponse;
import com.seatsac.booking.dto.response.SeatStatusResponse;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.booking.enums.SeatStatus;
import com.seatsac.booking.idempotency.IdempotencyStore;
import com.seatsac.booking.service.SeatHoldService;
import com.seatsac.booking.service.SeatSnapshotService;
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
 * BKG-01·02의 HTTP 층 — 상태 코드, 오류 본문(code·details 배열), 인증 헤더, 멱등 키 필수,
 * 추적 ID 전파. 흐름(Valkey·RDS)은 {@code SeatHoldFlowTest}가 보므로 여기서는 서비스를 가짜로 둔다.
 */
@WebMvcTest(controllers = SeatController.class)
@ImportAutoConfiguration(CommonWebAutoConfiguration.class)
class SeatControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-20T03:01:10Z");   // KST 12:01:10
    /** 멱등 키로 보내는 UUID. 비밀 값이 아니라 요청 식별자다 */
    private static final String HOLD_REQUEST = "11111111-1111-4111-8111-222222222222";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SeatSnapshotService snapshotService;

    @MockitoBean
    SeatHoldService holdService;

    /**
     * {@code @ControllerAdvice}인 멱등 advice가 함께 올라오는데 이 슬라이스에는 Valkey가 없다.
     * 멱등 동작 자체는 {@code SeatHoldIdempotencyTest}가 본다.
     */
    @MockitoBean
    IdempotencyStore idempotencyStore;

    // --- BKG-01 좌석 현황 (인증 불필요) ---

    @Test
    void seatsSnapshotNeedsNoAuth() throws Exception {
        when(snapshotService.snapshot(42L)).thenReturn(new SeatStatusResponse(42L, 3, 419L,
            Times.toApi(NOW), List.of(new SeatStatusResponse.SeatView(101L, SeatStatus.AVAILABLE),
            new SeatStatusResponse.SeatView(102L, SeatStatus.HELD))));

        mvc.perform(get("/screenings/42/seats"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.screeningId").value(42))
            .andExpect(jsonPath("$.layoutVersion").value(3))
            .andExpect(jsonPath("$.seq").value(419))
            .andExpect(jsonPath("$.generatedAt").value("2026-10-20T12:01:10+09:00"))
            .andExpect(jsonPath("$.seats[1].status").value("HELD"));
    }

    @Test
    void unknownScreeningIsNotFound() throws Exception {
        when(snapshotService.snapshot(9L)).thenThrow(BookingErrorCode.SCREENING_NOT_FOUND.exception());

        mvc.perform(get("/screenings/9/seats"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void screeningIdMustBeNumeric() throws Exception {
        mvc.perform(get("/screenings/abc/seats"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details[0].field").value("screeningId"));
    }

    // --- BKG-02 선점 ---

    @Test
    void holdReturnsCreatedWithPendingBooking() throws Exception {
        when(holdService.hold(eq(WithCurrentUser.DEFAULT_USER), any(SeatHoldRequest.class)))
            .thenReturn(SeatHoldResponse.builder()
                .bookingId(5001L).screeningId(42L).status(BookingStatus.PENDING)
                .seatIds(List.of(101L, 102L)).seatLabels(List.of("J7", "J8"))
                .seatCount(2).amount(30_000)
                .expiresAt(Times.toApi(NOW.plusSeconds(300))).createdAt(Times.toApi(NOW))
                .build());

        mvc.perform(post("/seats/hold").contentType(MediaType.APPLICATION_JSON)
                .content("{\"screeningId\":42,\"seatIds\":[101,102]}")
                .with(WithCurrentUser.user()).with(WithCurrentUser.idempotencyKey(HOLD_REQUEST)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.bookingId").value(5001))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.seatLabels[0]").value("J7"))
            .andExpect(jsonPath("$.amount").value(30000))
            .andExpect(jsonPath("$.expiresAt").value("2026-10-20T12:06:10+09:00"))
            .andExpect(header().string(Headers.TRACE_ID, WithCurrentUser.DEFAULT_TRACE));
    }

    /** 멱등 키는 필수다. 3.2.4에서 키를 실제로 쓰기 전에도 없으면 받지 않는다 */
    @Test
    void holdWithoutIdempotencyKeyIsRejected() throws Exception {
        mvc.perform(post("/seats/hold").contentType(MediaType.APPLICATION_JSON)
                .content("{\"screeningId\":42,\"seatIds\":[101]}")
                .with(WithCurrentUser.user()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details[0].field").value(Headers.IDEMPOTENCY_KEY))
            .andExpect(jsonPath("$.details[0].reason").value("REQUIRED"));

        verify(holdService, never()).hold(anyString(), any());
    }

    @Test
    void holdWithoutUserHeaderIsUnauthorized() throws Exception {
        mvc.perform(post("/seats/hold").contentType(MediaType.APPLICATION_JSON)
                .content("{\"screeningId\":42,\"seatIds\":[101]}")
                .with(WithCurrentUser.idempotencyKey(HOLD_REQUEST)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.details").isArray());

        verify(holdService, never()).hold(anyString(), any());
    }

    @Test
    void holdOverFourSeatsIsRejectedBeforeService() throws Exception {
        mvc.perform(post("/seats/hold").contentType(MediaType.APPLICATION_JSON)
                .content("{\"screeningId\":42,\"seatIds\":[101,102,103,104,105]}")
                .with(WithCurrentUser.user()).with(WithCurrentUser.idempotencyKey(HOLD_REQUEST)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details[0].field").value("seatIds"));

        verify(holdService, never()).hold(anyString(), any());
    }

    @Test
    void takenSeatIsConflictWithSeatDetail() throws Exception {
        when(holdService.hold(anyString(), any())).thenThrow(
            BookingErrorCode.SEAT_UNAVAILABLE.exception(ErrorDetail.of("seatIds", 101L, "HELD")));

        mvc.perform(post("/seats/hold").contentType(MediaType.APPLICATION_JSON)
                .content("{\"screeningId\":42,\"seatIds\":[101]}")
                .with(WithCurrentUser.user()).with(WithCurrentUser.idempotencyKey(HOLD_REQUEST)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SEAT_UNAVAILABLE"))
            .andExpect(jsonPath("$.details[0].value").value(101))
            .andExpect(jsonPath("$.details[0].reason").value("HELD"));
    }

    @Test
    void catalogDownIsServiceUnavailable() throws Exception {
        when(holdService.hold(anyString(), any()))
            .thenThrow(BookingErrorCode.CATALOG_UNAVAILABLE.exception());

        mvc.perform(post("/seats/hold").contentType(MediaType.APPLICATION_JSON)
                .content("{\"screeningId\":42,\"seatIds\":[101]}")
                .with(WithCurrentUser.user()).with(WithCurrentUser.idempotencyKey(HOLD_REQUEST)))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }
}

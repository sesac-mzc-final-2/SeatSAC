package com.seatsac.booking.controller;

import com.seatsac.booking.dto.request.BookingPayRequest;
import com.seatsac.booking.dto.response.BookingCancelResponse;
import com.seatsac.booking.dto.response.BookingDetailResponse;
import com.seatsac.booking.dto.response.BookingPaymentResponse;
import com.seatsac.booking.service.BookingCancelService;
import com.seatsac.booking.service.BookingDetailService;
import com.seatsac.booking.service.BookingPaymentService;
import com.seatsac.booking.service.SeatReleaseService;
import com.seatsac.common.core.http.Headers;
import com.seatsac.common.web.user.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예매 API — `contracts/openapi/booking.openapi.yaml`.
 * 선점 해제(BKG-03)·단건 조회·결제(BKG-10)·취소(BKG-07).
 */
@RestController
@RequiredArgsConstructor
public class BookingController {

    private final SeatReleaseService seatReleaseService;
    private final BookingDetailService bookingDetailService;
    private final BookingPaymentService bookingPaymentService;
    private final BookingCancelService bookingCancelService;

    /** BKG-03 선점 해제. 조건부 UPDATE라 자체로 멱등하므로 {@code Idempotency-Key}는 선택 */
    @DeleteMapping("/bookings/{bookingId}/hold")
    public BookingCancelResponse releaseHold(@CurrentUser String userId, @PathVariable Long bookingId) {
        return seatReleaseService.release(userId, bookingId);
    }

    /**
     * 예매 단건 조회 — 새로고침·결제 결과 재확인. {@code PENDING}이고 결제 요청이 있으면
     * 응답 전에 Payment를 조회해 결과를 반영한다(지연 카드 경로, 명세 5장).
     */
    @GetMapping("/bookings/{bookingId}")
    public BookingDetailResponse get(@CurrentUser String userId, @PathVariable Long bookingId) {
        return bookingDetailService.get(userId, bookingId);
    }

    /**
     * BKG-10 결제 요청 → BKG-06 확정. <b>거절도 200</b>이고 {@code status}로 구분한다.
     * 결과를 끝까지 알 수 없으면 503 {@code PAYMENT_RESULT_UNKNOWN}이고 예매는 {@code PENDING}이다.
     */
    // TODO(3.2.4 머지 후): @Idempotent를 붙인다. PR #31의 멱등 처리가 들어오면 같은 키 재요청이
    // 저장된 첫 응답을 받는다. 지금은 헤더만 필수(없으면 400)이고 재생은 아직 동작하지 않는다
    @PostMapping("/bookings/{bookingId}/pay")
    public BookingPaymentResponse pay(@CurrentUser String userId, @PathVariable Long bookingId,
                                      @RequestHeader(Headers.IDEMPOTENCY_KEY) String idempotencyKey,
                                      @Valid @RequestBody BookingPayRequest request) {
        return bookingPaymentService.pay(userId, bookingId, request);
    }

    /** BKG-07 확정 예매 취소 + 환불. 이미 취소된 예매도 200이다(멱등) */
    @PostMapping("/bookings/{bookingId}/cancel")
    public BookingCancelResponse cancel(@CurrentUser String userId, @PathVariable Long bookingId) {
        return bookingCancelService.cancel(userId, bookingId);
    }
}

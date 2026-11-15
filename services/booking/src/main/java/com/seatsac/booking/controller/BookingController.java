package com.seatsac.booking.controller;

import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.dto.request.BookingPayRequest;
import com.seatsac.booking.dto.request.PaymentPrepareRequest;
import com.seatsac.booking.idempotency.Idempotent;
import com.seatsac.booking.dto.response.BookingCancelResponse;
import com.seatsac.booking.dto.response.BookingDetailResponse;
import com.seatsac.booking.dto.response.BookingPaymentResponse;
import com.seatsac.booking.dto.response.PaymentPrepareResponse;
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
 * 선점 해제(BKG-03)·단건 조회·결제 준비·결제(BKG-10)·취소(BKG-07).
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
     * TOSSPAY 결제 준비(ADR-112) — paymentId와 포트원 결제창 값을 돌려준다. 돈은 움직이지 않는다.
     * 결제를 붙이는 요청이라 {@code @Idempotent}로 같은 키의 재요청에 첫 응답을 돌려준다.
     * <b>완료({@code /pay})는 다른 {@code Idempotency-Key}를 쓴다</b>(본문이 달라 같은 키면 409).
     */
    @Idempotent
    @PostMapping("/bookings/{bookingId}/payments")
    public PaymentPrepareResponse preparePayment(@CurrentUser String userId, @PathVariable Long bookingId,
                                                 @RequestHeader(Headers.IDEMPOTENCY_KEY) String idempotencyKey,
                                                 @Valid @RequestBody PaymentPrepareRequest request) {
        return bookingPaymentService.prepare(userId, bookingId, request);
    }

    /**
     * BKG-10 결제 요청 → BKG-06 확정. <b>거절도 200</b>이고 {@code status}로 구분한다.
     * {@code TOSSPAY}는 준비에서 받은 {@code paymentId}로 완료를 확인하고, {@code CARD}(모의)는
     * {@code X-Payment-Mock-Key}가 맞을 때만 받는다(k6·분석 Job).
     * 결과를 끝까지 알 수 없으면 503 {@code PAYMENT_RESULT_UNKNOWN}이고 예매는 {@code PENDING}이다.
     *
     * <p>{@code @Idempotent}가 같은 {@code Idempotency-Key}의 재요청에 <b>첫 응답을 그대로</b>
     * 돌려준다(ADR-19). 결제는 예매당 1회(ADR-24)라 두 번 긁히면 안 되는데, 화면이
     * 503·타임아웃 뒤 재시도하는 경로가 있어 여기에는 반드시 필요하다. 저장된 응답이
     * 없고 처리 중이면 409 {@code IDEMPOTENCY_IN_PROGRESS}다.
     */
    @Idempotent
    @PostMapping("/bookings/{bookingId}/pay")
    public BookingPaymentResponse pay(@CurrentUser String userId, @PathVariable Long bookingId,
                                      @RequestHeader(Headers.IDEMPOTENCY_KEY) String idempotencyKey,
                                      @RequestHeader(value = BookingPolicy.MOCK_PAYMENT_KEY_HEADER,
                                          required = false) String mockKey,
                                      @Valid @RequestBody BookingPayRequest request) {
        return bookingPaymentService.pay(userId, mockKey, bookingId, request);
    }

    /** BKG-07 확정 예매 취소 + 환불. 이미 취소된 예매도 200이다(멱등) */
    @PostMapping("/bookings/{bookingId}/cancel")
    public BookingCancelResponse cancel(@CurrentUser String userId, @PathVariable Long bookingId) {
        return bookingCancelService.cancel(userId, bookingId);
    }
}

package com.seatsac.booking.checkout.dto;

import com.seatsac.booking.dto.request.BookingPayRequest;
import com.seatsac.booking.enums.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /bookings/{id}/checkout} 요청 — 계약 {@code CheckoutRequest}(#176).
 *
 * @param couponIssueId {@code GET /coupons/me}가 준 쿠폰 발급 ID 그대로. booking은 해석하지 않는다(coupon이 판정)
 * @param card          CARD(모의)만. 명령에는 끝 4자리만 싣는다
 */
public record CheckoutRequest(
    @NotNull PaymentMethod method,
    @Size(max = 64) String couponIssueId,
    @Valid BookingPayRequest.Card card
) {
    @Override
    public String toString() {
        return "CheckoutRequest(method=" + method + ", couponIssueId=" + couponIssueId + ", card=****)";
    }
}

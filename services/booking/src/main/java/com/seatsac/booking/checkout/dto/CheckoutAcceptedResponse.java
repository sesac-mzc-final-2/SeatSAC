package com.seatsac.booking.checkout.dto;

import com.seatsac.booking.checkout.CheckoutState;

/**
 * 202 — 계약 {@code CheckoutAccepted}. {@code pollAfterMs}는 계약상 필수 정수라 항상 1000이다 — 쿠폰이 즉시 거절돼
 * 이미 끝난 상태여도 화면은 1초 뒤 조회로 결과를 본다(조회 응답 {@code Checkout.pollAfterMs}는 끝난 상태면 null)
 */
public record CheckoutAcceptedResponse(String checkoutId, Long bookingId, CheckoutState state, Long pollAfterMs) {
}

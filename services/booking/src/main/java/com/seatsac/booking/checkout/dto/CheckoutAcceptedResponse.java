package com.seatsac.booking.checkout.dto;

import com.seatsac.booking.checkout.CheckoutState;

/** 202 — 계약 {@code CheckoutAccepted}. {@code pollAfterMs}는 진행 중 1000, 끝난 상태(쿠폰 즉시 거절) null */
public record CheckoutAcceptedResponse(String checkoutId, Long bookingId, CheckoutState state, Long pollAfterMs) {
}

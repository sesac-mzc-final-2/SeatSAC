package com.seatsac.booking.checkout.dto;

import com.seatsac.booking.dto.request.BookingPayRequest;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** {@code POST /bookings/{id}/checkout/confirm} 요청 — 결제창에 넘긴 paymentId 그대로 */
public record CheckoutConfirmRequest(@NotNull @Pattern(regexp = BookingPayRequest.UUID_PATTERN) String paymentId) {
}

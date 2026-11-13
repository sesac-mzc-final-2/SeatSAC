package com.seatsac.booking.dto.request;

import com.seatsac.booking.enums.PaymentMethod;
import jakarta.validation.constraints.NotNull;

/**
 * 결제 준비 요청 — {@code POST /bookings/{bookingId}/payments} (ADR-112).
 * {@code TOSSPAY}만 받는다(모의 결제 {@code CARD}는 준비 단계 없이 {@code /pay}로 바로 간다).
 */
public record PaymentPrepareRequest(@NotNull PaymentMethod method) {
}

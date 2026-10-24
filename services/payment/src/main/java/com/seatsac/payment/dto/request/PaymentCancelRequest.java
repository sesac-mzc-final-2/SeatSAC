package com.seatsac.payment.dto.request;

import com.seatsac.payment.enums.PaymentCancelReason;
import jakarta.validation.constraints.NotNull;

/**
 * PAY-03 요청 — 왜 취소하는지 (명세 4장). 값이 비거나 enum 밖이면 400
 * {@code VALIDATION_FAILED}({@code reason: INVALID_CANCEL_REASON})다.
 */
public record PaymentCancelRequest(@NotNull PaymentCancelReason reason) {
}

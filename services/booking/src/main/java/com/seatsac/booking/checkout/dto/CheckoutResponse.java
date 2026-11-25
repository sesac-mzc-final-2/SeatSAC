package com.seatsac.booking.checkout.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.booking.checkout.CheckoutSaga;
import com.seatsac.booking.checkout.CheckoutState;
import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.entity.Booking;
import com.seatsac.common.core.time.Times;
import java.time.OffsetDateTime;

/**
 * {@code GET /bookings/{id}/checkout} 200 — 계약 {@code Checkout}(#176). 모든 필드가 항상 있고 해당 없으면 null.
 * 쿠폰 상태는 저장하지 않고 saga 상태에서 유도한다(booking은 쿠폰 상태를 모른다).
 */
public record CheckoutResponse(
    String checkoutId,
    Long bookingId,
    CheckoutState state,
    String step,
    String method,
    Integer attempt,
    boolean retryable,
    Amount amount,
    Coupon coupon,
    Payment payment,
    Failure failure,
    String refundNotice,
    OffsetDateTime expiresAt,
    OffsetDateTime updatedAt,
    Long pollAfterMs
) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Amount(int original, int discount, @JsonProperty("final") int finalAmount) {
    }

    public record Coupon(String couponIssueId, Discount discount, String state) {
    }

    public record Discount(String kind, int value) {
    }

    public record Payment(String paymentId, PortOne portone) {
    }

    /** 포트원 requestPayment에 그대로 넘기는 공개 값 — READY_TO_PAY일 때만 */
    public record PortOne(String storeId, String channelKey, String paymentId, String orderName, int totalAmount,
                          String currency, String payMethod) {
    }

    public record Failure(String code, String reason) {
    }

    public static CheckoutResponse of(CheckoutSaga saga, Booking booking, boolean retryable, Long pollAfterMs) {
        return new CheckoutResponse(
            saga.getCheckoutId(),
            saga.getBookingId(),
            saga.getState(),
            saga.getStep(),
            saga.getMethod().name(),
            saga.getAttempt(),
            retryable,
            new Amount(saga.getOriginalAmount(), saga.getDiscountAmount(), saga.getFinalAmount()),
            couponOf(saga),
            paymentOf(saga, booking),
            saga.getFailureCode() == null ? null : new Failure(saga.getFailureCode(), saga.getFailureReason()),
            saga.getRefundNotice(),
            Times.toApi(booking.getExpiresAt()),
            Times.toApi(saga.getUpdatedAt()),
            pollAfterMs);
    }

    private static Coupon couponOf(CheckoutSaga saga) {
        if (saga.getCouponIssueId() == null) {
            return null;
        }
        Discount discount = saga.getDiscountKind() == null ? null
            : new Discount(saga.getDiscountKind(), saga.getDiscountValue());
        String state = switch (saga.getState()) {
            case STARTED, COUPON_RESERVING -> "RESERVING";
            case COUPON_REJECTED -> "REJECTED";
            case CONFIRMED -> "USED";
            case FAILED, EXPIRED, PAYMENT_RETRYABLE, COMPENSATING -> "RELEASED";
            default -> "RESERVED";
        };
        return new Coupon(saga.getCouponIssueId(), discount, state);
    }

    private static Payment paymentOf(CheckoutSaga saga, Booking booking) {
        if (saga.getPaymentId() == null) {
            return null;
        }
        PortOne portone = null;
        if (saga.getState() == CheckoutState.READY_TO_PAY && saga.getCheckoutJson() != null) {
            JsonNode checkout = parse(saga.getCheckoutJson());
            portone = new PortOne(checkout.path("storeId").asText(null), checkout.path("channelKey").asText(null),
                saga.getPaymentId(), BookingPolicy.orderNameOf(booking.getSeatCount()), saga.getFinalAmount(),
                checkout.path("currency").asText("KRW"), checkout.path("payMethod").asText("EASY_PAY"));
        }
        return new Payment(saga.getPaymentId(), portone);
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("저장된 결제창 값을 읽지 못했다", e);
        }
    }
}

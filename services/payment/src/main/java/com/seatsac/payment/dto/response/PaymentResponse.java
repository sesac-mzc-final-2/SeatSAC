package com.seatsac.payment.dto.response;

import com.seatsac.common.core.time.Times;
import com.seatsac.payment.entity.Payment;
import com.seatsac.payment.enums.PaymentCancelReason;
import com.seatsac.payment.enums.PaymentFailureCode;
import com.seatsac.payment.enums.PaymentMethod;
import com.seatsac.payment.enums.PaymentStatus;
import com.seatsac.payment.enums.PgProvider;
import java.time.OffsetDateTime;
import lombok.Builder;

/**
 * 결제 공통 응답 — 네 API가 같은 모양을 쓴다 (명세 4장, 계약 {@code Payment}).
 * 승인·거절·{@code PROCESSING} 모두 200이고 {@code status}로 구분한다.
 *
 * <p>시각은 UTC 저장값을 {@code +09:00}으로 바꿔 담는다. 카드는 끝 4자리만 나간다(TOSSPAY는 null).
 * {@code checkout}은 TOSSPAY PROCESSING 응답에만 — 화면이 포트원 SDK {@code requestPayment}에 그대로 넘기는 공개 값이다.
 */
@Builder
public record PaymentResponse(
    String paymentId,
    Long bookingId,
    Integer amount,
    PaymentMethod method,
    PgProvider pgProvider,
    PaymentStatus status,
    String cardLast4,
    String pgTransactionId,
    Checkout checkout,
    PaymentFailureCode failureCode,
    PaymentCancelReason cancelReason,
    OffsetDateTime approvedAt,
    OffsetDateTime canceledAt,
    OffsetDateTime createdAt
) {
    /** 포트원 결제창 값(비밀 아님) — 계약 {@code Payment.checkout} */
    public record Checkout(String provider, String storeId, String channelKey, String payMethod, String currency) {
        public static Checkout portOneTossPay(String storeId, String channelKey) {
            return new Checkout("PORTONE", storeId, channelKey, "EASY_PAY", "KRW");
        }
    }

    public static PaymentResponse from(Payment payment) {
        return from(payment, null);
    }

    public static PaymentResponse from(Payment payment, Checkout checkout) {
        return PaymentResponse.builder()
            .paymentId(payment.getPaymentId())
            .bookingId(payment.getBookingId())
            .amount(payment.getAmount())
            .method(payment.getMethod())
            .pgProvider(payment.getPgProvider())
            .status(payment.getStatus())
            .cardLast4(payment.getCardLast4())
            .pgTransactionId(payment.getPgTransactionId())
            .checkout(checkout)
            .failureCode(payment.getFailureCode())
            .cancelReason(payment.getCancelReason())
            .approvedAt(Times.toApi(payment.getApprovedAt()))
            .canceledAt(Times.toApi(payment.getCanceledAt()))
            .createdAt(Times.toApi(payment.getCreatedAt()))
            .build();
    }
}

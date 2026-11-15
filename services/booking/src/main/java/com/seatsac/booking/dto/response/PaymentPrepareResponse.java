package com.seatsac.booking.dto.response;

import java.time.OffsetDateTime;
import lombok.Builder;

/**
 * 결제 준비 응답 — 계약 {@code PaymentPrepareResult} (ADR-112). 화면은 이 값으로
 * {@code PortOne.requestPayment}를 부르고, 끝나면 같은 {@code paymentId}로 {@code /pay}를 부른다.
 * 돈은 아직 움직이지 않았다.
 *
 * @param expiresAt 선점 만료 시각 — 결제창을 이 시각 전에 끝내야 한다
 */
@Builder
public record PaymentPrepareResponse(
    Long bookingId,
    String paymentId,
    Integer amount,
    String orderName,
    OffsetDateTime expiresAt,
    Checkout checkout
) {
    /** 포트원 결제창 호출 값 — storeId·channelKey는 공개 값이다(비밀 키 아님) */
    public record Checkout(String provider, String storeId, String channelKey, String payMethod,
                           String currency) {
    }
}

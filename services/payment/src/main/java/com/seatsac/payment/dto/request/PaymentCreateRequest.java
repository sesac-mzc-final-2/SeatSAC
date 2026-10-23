package com.seatsac.payment.dto.request;

import com.seatsac.payment.common.PaymentPolicy;
import com.seatsac.payment.enums.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * PAY-01 요청 — 명세 4장. {@code paymentId}는 Booking이 발급한 UUID다.
 *
 * <p>금액 배수 검증({@code amount % 15000 == 0})은 애너테이션으로 못 하므로 흐름 서비스가 본다.
 * 카드의 {@code expiry}·{@code holder}는 <b>형식만</b> 보고 저장하지 않는다.
 */
public record PaymentCreateRequest(

    @NotNull @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    String paymentId,

    @NotNull @Positive Long bookingId,

    @NotNull @Min(PaymentPolicy.MIN_AMOUNT) @Max(PaymentPolicy.MAX_AMOUNT) Integer amount,

    @NotNull PaymentMethod method,

    @NotNull @Valid Card card
) {

    /** 카드 정보 — 저장하는 것은 {@code number}의 끝 4자리뿐이다 */
    public record Card(
        @NotNull @Pattern(regexp = "^[0-9]{12,19}$") String number,
        @NotNull @Pattern(regexp = "^(0[1-9]|1[0-2])/[0-9]{2}$") String expiry,
        @NotNull @Size(min = 1, max = 50) String holder
    ) {
        /** 카드 번호·만료·소유자가 로그·오류 메시지에 섞이지 않게 가린다 */
        @Override
        public String toString() {
            return "Card(number=****, expiry=**/**, holder=***)";
        }
    }

    /** {@code card}가 통째로 로그에 찍히는 것을 막는다 */
    @Override
    public String toString() {
        return "PaymentCreateRequest(paymentId=" + paymentId + ", bookingId=" + bookingId
            + ", amount=" + amount + ", method=" + method + ", card=" + card + ")";
    }
}

package com.seatsac.booking.dto.request;

import com.seatsac.booking.enums.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;

/**
 * BKG-10 결제 요청 — 명세 5장. {@code POST /bookings/{bookingId}/pay}
 *
 * <p>카드 정보는 Payment로 <b>전달만</b> 하고 저장하지 않는다. 카드 번호 전체·{@code expiry}·
 * {@code holder}는 로그에 남기지 않는다(지침 4장) — 그래서 {@link Card}는 {@code toString}을 가린다.
 */
@Getter
@Builder
@Jacksonized
@ToString
public class BookingPayRequest {

    @NotNull
    private final PaymentMethod method;

    @NotNull
    @Valid
    private final Card card;

    /** 모의 카드 (Payment 명세). 숫자 16자리, 만료 {@code MM/YY}, 소유자 1~50자 */
    @Getter
    @Builder
    @Jacksonized
    public static class Card {

        @NotBlank
        @Pattern(regexp = "\\d{16}")
        private final String number;

        @NotBlank
        @Pattern(regexp = "(0[1-9]|1[0-2])/\\d{2}")
        private final String expiry;

        @NotBlank
        @Size(min = 1, max = 50)
        private final String holder;

        /** 끝 4자리만 Payment가 저장한다 */
        public String last4() {
            return number == null || number.length() < 4 ? null : number.substring(number.length() - 4);
        }

        /** 민감값이 로그·오류 메시지로 새지 않게 모든 필드를 가린다 */
        @Override
        public String toString() {
            return "Card(number=****, expiry=****, holder=****)";
        }
    }
}

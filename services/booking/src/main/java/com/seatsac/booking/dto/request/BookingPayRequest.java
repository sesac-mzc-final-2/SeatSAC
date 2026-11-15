package com.seatsac.booking.dto.request;

import com.seatsac.booking.enums.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * BKG-10 결제 요청 — 명세 5장. {@code POST /bookings/{bookingId}/pay}
 *
 * <ul>
 *   <li>{@code TOSSPAY} — 결제창(포트원) 완료 확인. 준비({@code POST /bookings/{id}/payments})에서 받은
 *       {@code paymentId}가 필요하고 {@code card}는 없다</li>
 *   <li>{@code CARD} — 모의 결제(테스트·분석 전용, ADR-112). {@code card}가 필요하다</li>
 * </ul>
 * 수단마다 필요한 칸은 서비스가 확인한다(둘 다 선택 필드).
 *
 * <p>카드 정보는 Payment로 <b>전달만</b> 하고 저장하지 않는다. 카드 번호 전체·{@code expiry}·
 * {@code holder}는 로그에 남기지 않는다(지침 4장) — 그래서 바깥과 {@link Card} 둘 다
 * {@code toString}을 직접 가린다(한쪽에만 두면 다른 쪽이 값을 흘린다).
 */
public record BookingPayRequest(

    @NotNull PaymentMethod method,

    @Pattern(regexp = UUID_PATTERN) String paymentId,

    @Valid Card card
) {
    /** 결제 ID(booking이 발급한 UUID v4) 형식 — 계약 {@code format: uuid} */
    public static final String UUID_PATTERN =
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    /** 카드가 섞이지 않게 바깥에서도 가린다 */
    @Override
    public String toString() {
        return "BookingPayRequest(method=" + method + ", paymentId=" + paymentId + ", card=****)";
    }

    /**
     * 모의 카드 (Payment 명세). 숫자 12~19자리, 만료 {@code MM/YY}, 소유자 1~50자.
     *
     * <p>자리수는 계약(`contracts/openapi/booking.openapi.yaml` PayRequest, `^[0-9]{12,19}$`)과 같게 둔다.
     * 16자리로 좁혀 두면 계약·화면·payment는 받아들이는 번호를 booking만 400으로 막는다.
     */
    public record Card(

        @NotBlank @Pattern(regexp = "^[0-9]{12,19}$") String number,

        @NotBlank @Pattern(regexp = "^(0[1-9]|1[0-2])/[0-9]{2}$") String expiry,

        @NotBlank @Size(min = 1, max = 50) String holder
    ) {
        /** 끝 4자리만 Payment가 저장한다 */
        public String last4() {
            return number == null || number.length() < 4 ? null : number.substring(number.length() - 4);
        }

        @Override
        public String toString() {
            return "Card(number=****, expiry=****, holder=****)";
        }
    }
}

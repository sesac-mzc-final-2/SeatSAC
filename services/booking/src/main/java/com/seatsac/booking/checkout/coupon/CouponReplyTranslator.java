package com.seatsac.booking.checkout.coupon;

import com.fasterxml.jackson.databind.JsonNode;
import com.seatsac.booking.checkout.coupon.CouponPort.CouponReply;
import java.util.Optional;

/**
 * coupon 결과 → saga 모델 번역(설계 03 3-2 표). 번역 규칙은 이곳 한 곳 — coupon이 필드 이름을 바꾸면 이 파일과 계약만 바뀐다.
 *
 * <ul>
 *   <li>{@code coupon.reserved {discountRate}} → {@link CouponPort.Reserved}({@code Discount(RATE)})</li>
 *   <li>{@code coupon.reservation-rejected {reason}} → {@link CouponPort.Rejected}(값 집합은 coupon 소유, 그대로 노출)</li>
 *   <li>할인 값이 없거나 범위 밖(비율 1~99) → empty — 금액을 정할 수 없는 결과라 형식 오류로 다룬다</li>
 * </ul>
 */
public final class CouponReplyTranslator {

    public static final String RESERVED = "coupon.reserved";
    public static final String REJECTED = "coupon.reservation-rejected";

    private CouponReplyTranslator() {
    }

    public static boolean handles(String type) {
        return RESERVED.equals(type) || REJECTED.equals(type);
    }

    public static Optional<CouponReply> translate(String type, String checkoutId, JsonNode payload) {
        return switch (type) {
            case RESERVED -> discount(payload).map(d -> new CouponPort.Reserved(checkoutId, d));
            case REJECTED -> Optional.of(new CouponPort.Rejected(checkoutId, text(payload, "reason")));
            default -> Optional.empty();
        };
    }

    private static Optional<Discount> discount(JsonNode payload) {
        JsonNode rate = payload.get("discountRate");
        if (rate != null && rate.isIntegralNumber() && rate.asInt() >= 1 && rate.asInt() <= 99) {
            return Optional.of(new Discount(Discount.Kind.RATE, rate.asInt()));
        }
        return Optional.empty();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}

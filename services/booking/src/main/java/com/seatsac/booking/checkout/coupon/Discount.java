package com.seatsac.booking.checkout.coupon;

/** 쿠폰 할인 — coupon이 비율(%)을 주든 정액(원)을 주든 금액 계산은 같은 코드다(설계 03, D12) */
public record Discount(Kind kind, int value) {

    public enum Kind { RATE, AMOUNT, NONE }

    public static final Discount NONE = new Discount(Kind.NONE, 0);

    /** 할인액(원). RATE는 내림(conventions 6장 금액), AMOUNT는 원가를 넘지 않는다 */
    public int apply(int original) {
        return switch (kind) {
            case RATE -> Math.floorDiv(original * value, 100);
            case AMOUNT -> Math.min(value, original);
            case NONE -> 0;
        };
    }
}

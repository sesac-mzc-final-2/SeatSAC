package com.seatsac.coupon.common;

/** coupon만 읽는 헤더 — 공통 {@code Headers}에 없는 것만 */
public final class CouponHeaders {

    /** Gateway가 토큰 {@code cognito:groups}로 만든 등급(ADR-118). 외부에서 보낸 값은 Gateway가 지운다 */
    public static final String USER_GRADE = "X-User-Grade";
    public static final String GRADE_VIP = "VIP";
    public static final String GRADE_NORMAL = "NORMAL";

    private CouponHeaders() {
    }

    /** 헤더가 없거나 모르는 값이면 NORMAL로 본다(혜택을 주지 않는 쪽) */
    public static String gradeOrNormal(String header) {
        return GRADE_VIP.equals(header) ? GRADE_VIP : GRADE_NORMAL;
    }
}

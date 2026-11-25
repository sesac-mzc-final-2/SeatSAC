package com.seatsac.user.common;

/**
 * 사용자 등급(6.3.1b, ADR-118) — 계약 {@code Profile.grade}. DB에 저장하지 않고 Gateway가 넣는 {@code X-User-Grade}를 그대로 쓴다.
 *
 * <p>Gateway는 외부에서 온 이 헤더를 지우고, 토큰 {@code cognito:groups}에 {@code VIP}가 있을 때만 {@code VIP}를 넣는다
 * (conventions 6장). 그래서 user는 값을 판정하지 않고 모양만 맞춘다: 정확히 {@code VIP}이면 VIP, 없거나 그 밖의 값이면 NORMAL
 * — 계약이 「모르는 값은 NORMAL」이라 응답에도 두 값만 내보낸다.
 */
public final class UserGrade {

    /** Gateway가 넣는 등급 헤더. 공통 {@code Headers}에 상수가 생기면 그쪽으로 옮긴다 */
    public static final String HEADER = "X-User-Grade";
    public static final String NORMAL = "NORMAL";
    public static final String VIP = "VIP";

    private UserGrade() {
    }

    /** 헤더 값 → 응답 값({@code NORMAL}·{@code VIP}) */
    public static String fromHeader(String headerValue) {
        return headerValue != null && VIP.equals(headerValue.strip()) ? VIP : NORMAL;
    }
}

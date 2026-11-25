package com.seatsac.gateway.filter;

import java.util.Collection;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 6.3.1a 사용자 등급(ADR-118, API 명세서 v1.5 4장 Gateway 규칙) — 헤더 {@code X-User-Grade}.
 * 검증된 Access Token의 {@code cognito:groups}에 {@code VIP}가 있으면 {@code VIP}, 아니면 {@code NORMAL}.
 * {@code ADMIN} 그룹은 Gateway에서 의미가 없다(관리자는 ops-api가 직접 본다). Gateway는 Cognito를 다시 조회하지 않으므로
 * VIP 지정은 토큰을 새로 받아야 반영된다. 헤더 이름은 {@code libs}의 {@code Headers}에 없어 여기 둔다(user의 {@code UserGrade.HEADER}와 같은 값).
 */
public final class UserGrade {

    public static final String HEADER = "X-User-Grade";
    public static final String VIP = "VIP";
    public static final String NORMAL = "NORMAL";

    static final String GROUPS_CLAIM = "cognito:groups";

    private UserGrade() {
    }

    /** 클레임이 없거나 모양이 예상과 달라도(배열이 아님) 예외 없이 NORMAL — 등급은 쿠폰 대상 판정에만 쓰이므로 좁게 */
    public static String fromToken(Jwt jwt) {
        Object groups = jwt.getClaims().get(GROUPS_CLAIM);
        if (groups instanceof Collection<?> values && values.contains(VIP)) {
            return VIP;
        }
        return NORMAL;
    }
}

package com.seatsac.common.web.user;

import com.seatsac.common.core.http.Headers;

/**
 * Gateway가 넣어 준 사용자. {@code userId}는 Cognito {@code sub}(UUID 문자열), {@code role}은 USER·ADMIN.
 * 컨트롤러 인자에 {@code @CurrentUser AuthUser user}로 받는다.
 */
public record AuthUser(String userId, String role) {

    public boolean isAdmin() {
        return Headers.ROLE_ADMIN.equals(role);
    }
}

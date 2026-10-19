package com.seatsac.common.web.user;

import com.seatsac.common.core.http.Headers;

/**
 * Gateway가 넣어 준 사용자 정보. userId는 Cognito sub(UUID 문자열).
 * 컨트롤러 인자에 {@code @CurrentUser CurrentUser user} 로 받는다. 헤더가 없으면 null.
 */
public record CurrentUser(String userId, String role) {

    public boolean isAdmin() {
        return Headers.ROLE_ADMIN.equals(role);
    }
}

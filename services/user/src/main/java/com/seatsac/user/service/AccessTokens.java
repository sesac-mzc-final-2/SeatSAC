package com.seatsac.user.service;

import com.seatsac.common.core.error.ErrorDetail;
import com.seatsac.user.common.UserErrorCode;
import java.util.Locale;
import org.springframework.http.HttpHeaders;

/** {@code Authorization: Bearer {Cognito Access Token}} — Gateway가 검증한 뒤 그대로 넘겨 준 헤더에서 토큰만 꺼낸다 */
final class AccessTokens {

    private static final String BEARER = "bearer ";

    private AccessTokens() {
    }

    /** 헤더가 없거나 Bearer 형식이 아니면 401 {@code TOKEN_REQUIRED} */
    static String bearer(String authorization) {
        if (authorization != null && authorization.length() > BEARER.length()
            && authorization.substring(0, BEARER.length()).toLowerCase(Locale.ROOT).equals(BEARER)) {
            String token = authorization.substring(BEARER.length()).strip();
            if (!token.isEmpty()) {
                return token;
            }
        }
        throw UserErrorCode.TOKEN_REQUIRED.exception(
            ErrorDetail.of(HttpHeaders.AUTHORIZATION, null, UserErrorCode.TOKEN_REQUIRED.name()));
    }
}

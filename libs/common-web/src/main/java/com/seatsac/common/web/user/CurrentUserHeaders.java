package com.seatsac.common.web.user;

import com.seatsac.common.core.http.Headers;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Gateway가 넣어 주는 사용자 헤더를 읽는 <b>한 곳</b>. {@link CurrentUserArgumentResolver}와,
 * 컨트롤러 인자 해석보다 먼저 도는 곳(예: 멱등 처리 어드바이스)이 같은 규칙을 쓰게 한다.
 *
 * <p>서비스 코드가 {@code X-User-Id}를 직접 읽으면 「형식 검증」 규칙이 조용히 갈라진다 —
 * 여기만 고치면 전부 같이 바뀐다.
 */
public final class CurrentUserHeaders {

    /** Cognito {@code sub} 형식(UUID). 틀린 값은 Gateway를 우회한 호출로 본다 */
    private static final Pattern SUB_FORMAT =
        Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private CurrentUserHeaders() {
    }

    /** 형식까지 맞는 사용자 ID. 헤더가 없거나 형식이 틀리면 비어 있다 */
    public static Optional<String> userId(String headerValue) {
        return headerValue == null || !SUB_FORMAT.matcher(headerValue).matches()
            ? Optional.empty() : Optional.of(headerValue);
    }

    public static boolean isValidUserId(String headerValue) {
        return userId(headerValue).isPresent();
    }

    /** 역할 헤더가 없으면 {@code USER}로 본다 */
    public static String roleOrUser(String headerValue) {
        return headerValue == null || headerValue.isBlank() ? Headers.ROLE_USER : headerValue;
    }
}

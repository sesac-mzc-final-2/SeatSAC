package com.seatsac.common.test;

import com.seatsac.common.core.http.Headers;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * MockMvc 요청에 Gateway가 넣어 주는 헤더를 붙인다.
 *
 * <pre>
 * mvc.perform(post("/seats/hold").with(WithCurrentUser.user()))
 * mvc.perform(get("/bookings/1").with(WithCurrentUser.user(otherSub)))
 * mvc.perform(get("/admin/x").with(WithCurrentUser.admin()))
 * </pre>
 */
public final class WithCurrentUser {

    /** Cognito sub 형식(UUID)이라야 CurrentUserArgumentResolver를 지나간다 */
    public static final String DEFAULT_USER = "11111111-1111-4111-8111-111111111111";

    /** 소문자 hex 32자리 (API 명세서 12.2). 형식이 틀리면 TraceIdFilter가 새 값으로 바꾼다 */
    public static final String DEFAULT_TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    private WithCurrentUser() {
    }

    public static RequestPostProcessor user() {
        return user(DEFAULT_USER);
    }

    public static RequestPostProcessor user(String userId) {
        return headers(userId, Headers.ROLE_USER, DEFAULT_TRACE);
    }

    public static RequestPostProcessor admin() {
        return admin(DEFAULT_USER);
    }

    public static RequestPostProcessor admin(String userId) {
        return headers(userId, Headers.ROLE_ADMIN, DEFAULT_TRACE);
    }

    /** 추적 ID까지 지정한다 (전파 테스트) */
    public static RequestPostProcessor headers(String userId, String role, String traceId) {
        return request -> {
            if (userId != null) {
                request.addHeader(Headers.USER_ID, userId);
            }
            if (role != null) {
                request.addHeader(Headers.USER_ROLE, role);
            }
            if (traceId != null) {
                request.addHeader(Headers.TRACE_ID, traceId);
            }
            return request;
        };
    }

    /** 멱등 키가 필요한 API용 (BKG-02·BKG-10) */
    public static RequestPostProcessor idempotencyKey(String key) {
        return request -> {
            request.addHeader(Headers.IDEMPOTENCY_KEY, key);
            return request;
        };
    }

    /** {@code .with(...)} 를 쓸 수 없는 자리에서 쓰는 빌더 형태 */
    public static MockHttpServletRequestBuilder apply(MockHttpServletRequestBuilder builder,
                                                      RequestPostProcessor processor) {
        return builder.with(processor);
    }
}

package com.seatsac.common.test;

import com.seatsac.common.core.http.Headers;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** MockMvc 요청에 Gateway 헤더를 넣는다: with(WithCurrentUser.user("sub-123")) 처럼 쓴다. */
public final class WithCurrentUser {

    public static final String DEFAULT_USER = "11111111-1111-4111-8111-111111111111";

    private WithCurrentUser() {
    }

    public static MockHttpServletRequestBuilder user(MockHttpServletRequestBuilder b, String userId) {
        return b.header(Headers.USER_ID, userId).header(Headers.USER_ROLE, Headers.ROLE_USER)
            .header(Headers.TRACE_ID, "test-trace");
    }

    public static MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b, String userId) {
        return b.header(Headers.USER_ID, userId).header(Headers.USER_ROLE, Headers.ROLE_ADMIN)
            .header(Headers.TRACE_ID, "test-trace");
    }
}

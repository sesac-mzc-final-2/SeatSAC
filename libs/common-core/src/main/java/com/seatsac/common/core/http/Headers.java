package com.seatsac.common.core.http;

/** 서비스 사이에서 쓰는 헤더 이름 — API 명세서 1-7. */
public final class Headers {
    public static final String USER_ID = "X-User-Id";        // Cognito sub
    public static final String USER_ROLE = "X-User-Role";    // USER · ADMIN
    public static final String TRACE_ID = "X-Trace-Id";
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String VIEWER_ADDRESS = "CloudFront-Viewer-Address";

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ADMIN = "ADMIN";

    private Headers() {
    }
}

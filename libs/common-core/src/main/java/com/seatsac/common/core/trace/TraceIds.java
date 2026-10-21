package com.seatsac.common.core.trace;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 추적 ID — API 명세서 12.2. 형식은 <b>소문자 16진수 32자리</b>로, UUID v4에서 하이픈을 뺀 값이다.
 * 생성 방식은 UUID 그대로이고 32자리를 전부 쓴다(잘라 쓰면 랜덤 비트가 절반으로 줄고
 * {@code outbox_message.trace_id CHAR(32)}와 길이가 맞지 않는다).
 */
public final class TraceIds {

    private static final Pattern FORMAT = Pattern.compile("^[0-9a-f]{32}$");

    private TraceIds() {
    }

    /** 새 추적 ID: UUID v4에서 '-' 제거 = 소문자 hex 32자리 */
    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 32자리 소문자 hex인지. 아니면 부르는 쪽이 {@link #newId()}로 새로 만든다 */
    public static boolean isValid(String traceId) {
        return traceId != null && FORMAT.matcher(traceId).matches();
    }
}

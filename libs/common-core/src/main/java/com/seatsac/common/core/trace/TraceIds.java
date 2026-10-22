package com.seatsac.common.core.trace;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * traceId 규약: 소문자 16진수 32자리(UUID 하이픈 제거). contracts/schemas/error.v1.json 의 pattern 과 같다.
 * docs/conventions.md 6장, ADR-55.
 */
public final class TraceIds {

    private static final Pattern VALID = Pattern.compile("^[0-9a-f]{32}$");

    private TraceIds() {
    }

    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static boolean isValid(String traceId) {
        return traceId != null && VALID.matcher(traceId).matches();
    }

    /** 형식이 맞으면 그대로, 아니면(없음·길이·대문자 등) 새로 만든다. */
    public static String orNew(String traceId) {
        return isValid(traceId) ? traceId : newId();
    }
}

package com.seatsac.booking.idempotency;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 멱등 키에 저장하는 값 — `redis-usage.md` 3장의 {@code {status, requestHash, response}}.
 *
 * @param status      {@code IN_PROGRESS} 처리 중 · {@code DONE} 첫 응답 저장 완료
 * @param requestHash 요청 본문 SHA-256. 같은 키로 다른 본문이 오면 409로 거절하는 근거
 * @param httpStatus  첫 응답의 상태 코드 (DONE일 때만)
 * @param body        첫 응답 본문 JSON 그대로 (DONE일 때만)
 */
public record IdempotencyRecord(String status, String requestHash, Integer httpStatus, String body) {

    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String DONE = "DONE";

    public static IdempotencyRecord inProgress(String requestHash) {
        return new IdempotencyRecord(IN_PROGRESS, requestHash, null, null);
    }

    public static IdempotencyRecord done(String requestHash, int httpStatus, String body) {
        return new IdempotencyRecord(DONE, requestHash, httpStatus, body);
    }

    @JsonIgnore
    public boolean isInProgress() {
        return IN_PROGRESS.equals(status);
    }

    /** 첫 응답을 돌려줄 수 있는 상태인지 — 저장이 끊겨 본문이 비어 있으면 재실행시킨다 */
    @JsonIgnore
    public boolean isReplayable() {
        return DONE.equals(status) && httpStatus != null && body != null;
    }
}

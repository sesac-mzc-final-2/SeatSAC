package com.seatsac.booking.idempotency;

/**
 * 같은 키·같은 본문으로 다시 온 요청 — 저장된 첫 응답을 그대로 돌려주려고 처리를 중단시킨다.
 * 오류가 아니라 흐름 제어라서 스택 트레이스를 만들지 않는다.
 */
public class IdempotentReplayException extends RuntimeException {

    private final int httpStatus;
    private final String body;

    public IdempotentReplayException(int httpStatus, String body) {
        super(null, null, false, false);
        this.httpStatus = httpStatus;
        this.body = body;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String body() {
        return body;
    }
}

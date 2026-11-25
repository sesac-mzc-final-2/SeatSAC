package com.seatsac.coupon.common;

/** coupon Valkey 키 — conventions 4장 표 그대로 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** 선착순 남은 수량 — TTL 이벤트 종료 + 1일 */
    public static String stock(long eventId) {
        return "coupon:stock:" + eventId;
    }

    /** 발급 요청 멱등 — TTL 24시간 */
    public static String idempotency(String userId, String idempotencyKey) {
        return "coupon:idem:" + userId + ":" + idempotencyKey;
    }
}

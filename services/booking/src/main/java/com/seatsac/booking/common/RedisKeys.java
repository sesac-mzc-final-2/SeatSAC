package com.seatsac.booking.common;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Valkey 키 이름 — `redis-usage.md` 3장. <b>키 이름은 이 클래스에서만 만든다.</b>
 *
 * <p>중괄호는 실제 문자로 넣는다(`seat:{42}:101`). 클러스터 모드로 가도 한 회차의 키가 같은 해시
 * 슬롯에 모여 Lua 스크립트를 쓸 수 있다. {@code KEYS seat:*} 같은 전체 검색은 쓰지 않는다.
 *
 * <p>TTL 없는 키는 {@link #holdExpiry()}와 {@link #seatSeq(Long)} 둘뿐이다(명세의 예외 2개).
 * 새 키는 TTL을 반드시 둔다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RedisKeys {

    /** 좌석 선점. 저장 값 = holdId, TTL 300초 */
    public static String seat(Long screeningId, Long seatId) {
        return "seat:{" + screeningId + "}:" + seatId;
    }

    /** 회차당 진행 중 선점 1건 제한. 저장 값 = holdId, TTL 300초 */
    public static String userHold(Long screeningId, String userId) {
        return "user-hold:{" + screeningId + "}:" + userId;
    }

    /** 만료 장부 (Sorted Set). 점수 = 만료 ms, 멤버 = {@link #holdExpiryMember}. TTL 없음(예외) */
    public static String holdExpiry() {
        return "hold-expiry";
    }

    /** {@code {회차ID}:{holdId}} — 만료 스캔이 이 값으로 예매를 찾는다 */
    public static String holdExpiryMember(Long screeningId, String holdId) {
        return screeningId + ":" + holdId;
    }

    /** 회차별 이벤트 일련번호. TTL 없음(예외) */
    public static String seatSeq(Long screeningId) {
        return "seat-seq:{" + screeningId + "}";
    }

    /** 좌석 이벤트 Pub/Sub 채널 */
    public static String seatEvents(Long screeningId) {
        return "seat-events:{" + screeningId + "}";
    }

    /** 멱등 키. 사용자 ID를 앞에 둬 남의 응답을 받지 못하게 한다. TTL 24시간 */
    public static String idempotency(String userId, String idempotencyKey) {
        return "idem:" + userId + ":" + idempotencyKey;
    }
}

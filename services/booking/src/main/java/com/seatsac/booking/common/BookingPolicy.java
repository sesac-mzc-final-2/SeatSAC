package com.seatsac.booking.common;

import java.time.Duration;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** booking 서비스의 고정 값 — 명세 1장, booking 지침 2장. 숫자는 여기에만 둔다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BookingPolicy {

    /** 좌석 1석 단가(원). amount = seatCount × SEAT_PRICE */
    public static final int SEAT_PRICE = 15_000;

    /** 1회 선점 최소·최대 좌석 수 */
    public static final int MIN_SEATS_PER_HOLD = 1;
    public static final int MAX_SEATS_PER_HOLD = 4;

    /** 선점 TTL. expires_at = now + 300초, Valkey 좌석 키·user-hold 키의 EX 값과 같다 */
    public static final Duration HOLD_TTL = Duration.ofSeconds(300);

    /**
     * 파드 캐시 TTL — 회차 정보 10분(명세 12.3).
     *
     * <p>좌석 배치는 명세 12.3이 1일로 적었지만 <b>10분</b>으로 줄였다(10/22, PR #7 리뷰).
     * 좌석 비활성화(LAY-07)는 {@code layoutVersion}을 올리지 않아 캐시 키가 그대로라,
     * 1일이면 최대 하루 동안 비활성 좌석을 선점 가능한 좌석으로 취급한다.
     * 선점마다 Catalog를 다시 부르면 p95 300ms를 못 맞추므로 TTL을 catalog 캐시와 같은 10분으로 맞추고,
     * 그 사이의 선점은 감수한다(이미 판매된 좌석은 CAT PATCH의 409 SEAT_SOLD와
     * booking_seat 유일 제약이 막는다).
     */
    public static final Duration SCREENING_CACHE_TTL = Duration.ofMinutes(10);
    public static final Duration LAYOUT_CACHE_TTL = Duration.ofMinutes(10);
    public static final int SCREENING_CACHE_SIZE = 2_000;
    public static final int LAYOUT_CACHE_SIZE = 200;

    /** 페이징 상한 (명세 1장: 기본 20 · 최대 50) */
    public static final int MAX_PAGE_SIZE = 50;

    /** 내부 좌석 수 조회의 회차 상한 (계약 {@code maxItems: 100}). catalog가 100개씩 나눠 보낸다 */
    public static final int MAX_SEAT_COUNT_IDS = 100;
    /**
     * 만료 스캔 한 주기에 처리하는 최대 건수. 노드 0대 구간(ADR-34) 뒤 첫 스윕에서는 밀린 선점이
     * 한꺼번에 걸리는데, 전부 한 주기에 처리하면 Hikari 연결 5개를 다 써 요청이 막힌다.
     * 남은 건은 다음 주기가 이어받는다(조건부 UPDATE라 겹쳐도 한 번만 처리된다).
     */
    public static final int EXPIRY_SCAN_BATCH = 100;

    /** 안전망 1(만료 장부) 주기 — 만료 반영 2초 목표의 근거 */
    public static final Duration EXPIRY_LEDGER_INTERVAL = Duration.ofSeconds(1);

    /** 안전망 2(RDS 스캔) 주기 — Valkey가 통째로 비어도 이 경로가 정리한다 */
    public static final Duration EXPIRY_DB_INTERVAL = Duration.ofSeconds(10);

    /**
     * 고아 키 판정을 유예하는 시간. {@code hold.lua}는 RDS 커밋 <b>전에</b> {@code user-hold}와
     * 만료 장부를 쓰므로, 같은 사용자의 두 번째 요청(더블클릭·타임아웃 재시도)이 그 사이에 오면
     * RDS에서 예매를 찾지 못한다. 이때 고아로 보고 지우면 첫 요청의 선점 1건 제한과 만료 장부가
     * 깨진다 — 선점이 만들어진 지 이 시간 안이면 「처리 중」으로 보고 409를 돌려준다.
     *
     * <p>트랜잭션은 보통 수 ms라 10초면 넉넉하다. 진짜 고아(파드가 죽은 경우)는 10초 뒤 다음
     * 요청에서 정리되고, 그 전에도 RDS 만료 스캔이 300초 안에 치운다.
     */
    public static final Duration HOLD_COMMIT_GRACE = Duration.ofSeconds(10);

    /**
     * 처리 중({@code IN_PROGRESS}) 멱등 키의 TTL. 요청이 끝나면 저장(DONE) 또는 삭제로 바뀌므로
     * 이 값이 쓰이는 경우는 <b>처리 도중 파드가 죽거나 요청이 아주 오래 걸리는 경우</b>뿐이다.
     * 24시간을 그대로 쓰면 그런 키로 들어오는 재시도가 하루 동안 409를 받는다.
     *
     * <p>{@link #HOLD_TTL}과 같은 값으로 둔다. 이 시간이 지나면 <b>선점 자체가 이미 만료</b>되어
     * 좌석이 풀리므로, 같은 키의 재시도가 새로 선점을 잡아도 앞 요청과 좌석을 다툴 일이 없다.
     * Gateway 응답 대기(15초)는 근거로 쓸 수 없다 — 클라이언트가 포기해도 서버 처리는 계속된다.
     *
     * <p>TTL이 끝난 뒤 늦게 끝난 요청이 그 사이 들어온 요청의 응답을 덮어쓰는 문제는 TTL로
     * 막지 않는다 — 저장·삭제를 <b>처리권(owner)이 같을 때만</b> 하도록 했다
     * ({@code idem-write.lua}).
     */
    public static final Duration IDEMPOTENCY_IN_PROGRESS_TTL = HOLD_TTL;

    /** 멱등 키 TTL 24시간 — 화면이 재시도할 수 있는 동안 첫 응답을 들고 있는다 (ADR-19) */
    public static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    /** SSE 하트비트 간격과 재연결 지시 값 */

    /**
     * 아웃박스 릴레이 — 한 주기에 집는 양. 노드 0대 구간(ADR-34) 뒤 첫 주기에는 밀린 알림이
     * 한꺼번에 걸리는데, 전부 보내려 하면 Hikari 연결 5개를 다 쓴다. 남은 건은 다음 주기가 받는다.
     */
    public static final int OUTBOX_RELAY_BATCH = 50;

    /**
     * 점유 시간. 파드가 전송 중에 죽으면 이 시간이 지난 뒤 다른 파드가 집는다.
     * SQS 호출(연결·응답 수 초)보다 넉넉하고, 재전송이 너무 늦지 않은 값이다.
     */
    public static final Duration OUTBOX_CLAIM_TIMEOUT = Duration.ofSeconds(30);

    /** 재시도 한도. 넘기면 FAILED로 두고 경보한다 (ERD 3.3) */
    public static final int OUTBOX_MAX_ATTEMPTS = 10;

    /** 재시도 간격 상한 — 1·2·4…최대 60초 (ERD 3.3) */
    public static final Duration OUTBOX_MAX_BACKOFF = Duration.ofSeconds(60);

    public static final Duration SSE_HEARTBEAT = Duration.ofSeconds(15);
    public static final long SSE_RETRY_MILLIS = 2_000L;

    public static int amountOf(int seatCount) {
        return seatCount * SEAT_PRICE;
    }

    public static boolean isAllowedSeatCount(int seatCount) {
        return seatCount >= MIN_SEATS_PER_HOLD && seatCount <= MAX_SEATS_PER_HOLD;
    }
}

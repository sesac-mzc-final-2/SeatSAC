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

    /**
     * 선점 TTL 기본값(초). 실제 값은 설정 {@code app.seat-hold.ttl-seconds}
     * ({@code APP_SEAT_HOLD_TTL_SECONDS})를 {@link com.seatsac.booking.config.SeatHoldProperties}로 읽는다 —
     * 데모에서 만료를 짧게 보여 줄 때 코드를 바꾸지 않고 ConfigMap만 바꾼다.
     */
    public static final int DEFAULT_HOLD_TTL_SECONDS = 300;

    /**
     * 예매 마감 — 상영 시작 5분 전부터 선점(BKG-02)·결제(BKG-10)를 받지 않는다.
     *
     * <p>결제 요청 하나는 호출·재시도·조회를 합쳐 계약상 약 12초면 끝나므로, 5분 전에 마감하면 결제가
     * 상영 시작 뒤에 승인되는 일이 없다 → 「상영 시작 뒤 취소 불가」(BKG-07)와 부딪혀 환불할 수 없는
     * 확정 예매가 생기지 않는다. 선점도 같이 막는다 — 선점만 열어 두면 결제할 수 없는 좌석이 TTL 동안 묶인다.
     * 취소는 이 값이 아니라 상영 시작 시각 기준이다.
     */
    public static final Duration BOOKING_CUTOFF = Duration.ofMinutes(5);

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
     * 요청에서 정리되고, 그 전에도 RDS 만료 스캔이 선점 TTL 안에 치운다.
     */
    public static final Duration HOLD_COMMIT_GRACE = Duration.ofSeconds(10);

    /** 멱등 키 TTL 24시간 — 화면이 재시도할 수 있는 동안 첫 응답을 들고 있는다 (ADR-19) */
    public static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

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

    /** SSE 하트비트 간격과 재연결 지시 값 */
    public static final Duration SSE_HEARTBEAT = Duration.ofSeconds(15);
    public static final long SSE_RETRY_MILLIS = 2_000L;

    /** 모의 결제(CARD)를 부르는 기계 경로(k6·분석 Job)의 키 헤더 — 값은 {@code app.payment.mock-key} (ADR-112) */
    public static final String MOCK_PAYMENT_KEY_HEADER = "X-Payment-Mock-Key";

    /** 포트원 결제창에 보이는 주문명 접두사 — 「SeatSAC 영화 예매 2석」 */
    private static final String ORDER_NAME_PREFIX = "SeatSAC 영화 예매 ";

    public static int amountOf(int seatCount) {
        return seatCount * SEAT_PRICE;
    }

    /** 결제 준비 응답과 payment에 보내는 값이 같아야 하므로 이곳 하나에서 만든다 */
    public static String orderNameOf(int seatCount) {
        return ORDER_NAME_PREFIX + seatCount + "석";
    }

    public static boolean isAllowedSeatCount(int seatCount) {
        return seatCount >= MIN_SEATS_PER_HOLD && seatCount <= MAX_SEATS_PER_HOLD;
    }
}

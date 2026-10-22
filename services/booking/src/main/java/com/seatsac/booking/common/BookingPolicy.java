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

    /** 멱등 키 TTL 24시간 — 화면이 재시도할 수 있는 동안 첫 응답을 들고 있는다 (ADR-19) */
    public static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    /** SSE 하트비트 간격과 재연결 지시 값 */
    public static final Duration SSE_HEARTBEAT = Duration.ofSeconds(15);
    public static final long SSE_RETRY_MILLIS = 2_000L;

    public static int amountOf(int seatCount) {
        return seatCount * SEAT_PRICE;
    }

    public static boolean isAllowedSeatCount(int seatCount) {
        return seatCount >= MIN_SEATS_PER_HOLD && seatCount <= MAX_SEATS_PER_HOLD;
    }
}

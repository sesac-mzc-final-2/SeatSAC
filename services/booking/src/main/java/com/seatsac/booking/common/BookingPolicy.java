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

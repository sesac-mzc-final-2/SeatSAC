package com.seatsac.booking.common;

import java.time.Duration;

/** booking 서비스의 고정 값 — 명세 1장, booking 지침 2장. 숫자는 여기에만 둔다. */
public final class BookingPolicy {

    /** 좌석 1석 단가(원). amount = seatCount × SEAT_PRICE */
    public static final int SEAT_PRICE = 15_000;

    /** 1회 선점 최소·최대 좌석 수 */
    public static final int MIN_SEATS_PER_HOLD = 1;
    public static final int MAX_SEATS_PER_HOLD = 4;

    /** 선점 TTL. expires_at = now + 300초, Valkey 좌석 키·user-hold 키의 EX 값과 같다 */
    public static final Duration HOLD_TTL = Duration.ofSeconds(300);

    /** SSE 하트비트 간격과 재연결 지시 값 */
    public static final Duration SSE_HEARTBEAT = Duration.ofSeconds(15);
    public static final long SSE_RETRY_MILLIS = 2_000L;

    private BookingPolicy() {
    }

    public static int amountOf(int seatCount) {
        return seatCount * SEAT_PRICE;
    }

    public static boolean isAllowedSeatCount(int seatCount) {
        return seatCount >= MIN_SEATS_PER_HOLD && seatCount <= MAX_SEATS_PER_HOLD;
    }
}

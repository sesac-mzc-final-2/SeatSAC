package com.seatsac.booking.dto.response;

import java.util.List;

/**
 * 회차별 사용 불가 좌석 수 (내부 API) — 명세 5장. Catalog(CAT-03 2차)가 <b>300ms 안</b>에 받아
 * {@code 잔여 = totalSeats − heldCount − reservedCount}로 계산한다.
 *
 * <p>요청한 회차는 예매가 없어도 모두 0으로 넣는다. RDS로 세고 Valkey 카운터는 두지 않는다.
 */
public record SeatCountsResponse(List<SeatCountView> items) {

    public SeatCountsResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** PENDING → heldCount, CONFIRMED → reservedCount */
    public record SeatCountView(Long screeningId, long heldCount, long reservedCount) {
    }
}

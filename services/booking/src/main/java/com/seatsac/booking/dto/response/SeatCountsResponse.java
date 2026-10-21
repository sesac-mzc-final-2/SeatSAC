package com.seatsac.booking.dto.response;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * 회차별 사용 불가 좌석 수 (내부 API) — 명세 5장. Catalog(CAT-03 2차)가 <b>300ms 안</b>에 받아
 * {@code 잔여 = totalSeats − heldCount − reservedCount}로 계산한다.
 *
 * <p>요청한 회차는 예매가 없어도 모두 0으로 넣는다. RDS로 세고 Valkey 카운터는 두지 않는다.
 */
@Getter
@Builder
public class SeatCountsResponse {

    private final List<SeatCountView> items;

    public static SeatCountsResponse of(List<SeatCountView> items) {
        return SeatCountsResponse.builder().items(items == null ? List.of() : List.copyOf(items)).build();
    }

    /** PENDING → heldCount, CONFIRMED → reservedCount */
    @Getter
    @Builder
    public static class SeatCountView {
        private final Long screeningId;
        private final long heldCount;
        private final long reservedCount;

        public static SeatCountView of(Long screeningId, long heldCount, long reservedCount) {
            return SeatCountView.builder()
                .screeningId(screeningId).heldCount(heldCount).reservedCount(reservedCount).build();
        }
    }
}

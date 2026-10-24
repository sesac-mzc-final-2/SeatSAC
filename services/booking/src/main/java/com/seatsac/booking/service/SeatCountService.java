package com.seatsac.booking.service;

import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.dto.response.SeatCountsResponse;
import com.seatsac.booking.dto.response.SeatCountsResponse.SeatCountView;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.booking.repository.BookingSeatRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회차별 선점·확정 좌석 수 (내부 API) — catalog가 CAT-03 2차 조회에서 부른다.
 *
 * <p><b>300ms 예산</b>이 전부다. catalog는 이 시간을 넘기면 잔여석을 총 좌석 수로 대체하고
 * {@code remainingSeatsSource: TOTAL}로 내려 보낸다(그러면 화면의 잔여석이 실제와 달라진다).
 * 그래서 회차를 하나씩 돌지 않고 <b>그룹 집계 한 번</b>으로 끝낸다.
 *
 * <p>요청한 회차는 예매가 없어도 모두 0으로 채워 돌려준다 — 빠진 회차를 catalog가 「조회 실패」와
 * 구분할 수 없기 때문이다. 순서도 요청 순서를 지킨다.
 */
@Service
@RequiredArgsConstructor
public class SeatCountService {

    /** 좌석을 쥐고 있는 상태. 나머지(EXPIRED·CANCELED·PAYMENT_FAILED)는 행이 이미 지워진다 */
    private static final List<BookingStatus> OCCUPYING =
        List.of(BookingStatus.PENDING, BookingStatus.CONFIRMED);

    private final BookingSeatRepository bookingSeatRepository;

    @Transactional(readOnly = true)
    public SeatCountsResponse countFor(List<Long> screeningIds) {
        Map<Long, long[]> counts = new LinkedHashMap<>();   // [held, reserved]
        screeningIds.forEach(id -> counts.putIfAbsent(id, new long[2]));

        for (Object[] row : bookingSeatRepository
            .countSeatsByScreeningIdInAndStatusIn(List.copyOf(counts.keySet()), OCCUPYING)) {
            long[] pair = counts.get((Long) row[0]);
            if (pair == null) {
                continue;   // 요청하지 않은 회차 (있을 수 없지만 방어)
            }
            int slot = row[1] == BookingStatus.PENDING ? 0 : 1;
            pair[slot] = (Long) row[2];
        }

        return new SeatCountsResponse(counts.entrySet().stream()
            .map(e -> new SeatCountView(e.getKey(), e.getValue()[0], e.getValue()[1]))
            .toList());
    }

    /** 요청 상한 — 계약의 {@code maxItems: 100}과 같다 */
    public static int maxIds() {
        return BookingPolicy.MAX_SEAT_COUNT_IDS;
    }
}

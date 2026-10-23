package com.seatsac.catalog.client;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * CAT-03 잔여 좌석용 booking 집계. 구현은 {@link BookingSeatCountClient}, 테스트는 바꿔 끼운다.
 */
public interface SeatCountClient {

    /**
     * 회차별 사용 불가 좌석 수(선점 + 확정).
     *
     * @return 요청한 회차 전부가 들어 있는 맵. booking이 300ms 안에 답하지 않거나 서킷이 열려 있으면 {@link Optional#empty()}
     *         — 호출자는 {@code remainingSeatsSource: TOTAL}로 대체한다
     */
    Optional<Map<Long, Long>> unavailableCounts(Collection<Long> screeningIds);
}

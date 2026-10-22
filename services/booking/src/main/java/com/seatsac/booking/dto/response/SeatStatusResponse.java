package com.seatsac.booking.dto.response;

import com.seatsac.booking.enums.SeatStatus;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Builder;

/**
 * BKG-01 좌석 현황 조회 200 응답 — 명세 5장.
 *
 * <p>{@code seq}는 이 스냅샷에 반영된 마지막 이벤트 번호다. 화면은 SSE를 먼저 연결한 뒤
 * 이 값으로 빈틈을 판정한다. {@code layoutVersion}으로 CAT-04 배치를 받아 {@code seatId}로 합친다.
 */
@Builder
public record SeatStatusResponse(
    Long screeningId,
    Integer layoutVersion,
    Long seq,
    OffsetDateTime generatedAt,
    List<SeatView> seats
) {
    /** 배치 버전의 전체 좌석. 우선순위 RESERVED > HELD > AVAILABLE */
    public record SeatView(Long seatId, SeatStatus status) {
    }
}

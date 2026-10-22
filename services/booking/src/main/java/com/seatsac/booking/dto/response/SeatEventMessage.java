package com.seatsac.booking.dto.response;

import com.seatsac.booking.enums.SeatEventType;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Builder;

/**
 * BKG-05 좌석 이벤트 본문 — 명세 5장, {@code contracts/events/seat-event.v1.json}.
 * Valkey Pub/Sub({@code seat-events:{회차ID}})로 받아 SSE {@code event: seat}의 {@code data}로 보낸다.
 *
 * <p>공개 스트림이라 <b>예매 ID·사용자 ID를 넣지 않는다.</b> 발행은 RDS 커밋 뒤에 하고
 * {@code seq}는 회차별 {@code INCR seat-seq:{회차ID}} 값이다.
 */
@Builder
public record SeatEventMessage(
    Long seq,
    SeatEventType type,
    Long screeningId,
    List<Long> seatIds,
    OffsetDateTime occurredAt,
    String traceId
) {
}

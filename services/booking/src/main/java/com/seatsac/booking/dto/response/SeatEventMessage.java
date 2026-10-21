package com.seatsac.booking.dto.response;

import com.seatsac.booking.enums.SeatEventType;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * BKG-05 좌석 이벤트 본문 — 명세 5장, {@code contracts/events/seat-event.v1.json}.
 * Valkey Pub/Sub({@code seat-events:{회차ID}})로 받아 SSE {@code event: seat}의 {@code data}로 보낸다.
 *
 * <p>공개 스트림이라 <b>예매 ID·사용자 ID를 넣지 않는다.</b> 발행은 RDS 커밋 뒤에 하고
 * {@code seq}는 회차별 {@code INCR seat-seq:{회차ID}} 값이다.
 */
@Getter
@Builder
public class SeatEventMessage {

    private final Long seq;
    private final SeatEventType type;
    private final Long screeningId;
    private final List<Long> seatIds;
    private final OffsetDateTime occurredAt;
    private final String traceId;
}

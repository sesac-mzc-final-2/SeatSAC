package com.seatsac.booking.component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.booking.common.RedisKeys;
import com.seatsac.booking.dto.response.SeatEventMessage;
import com.seatsac.booking.enums.SeatEventType;
import com.seatsac.common.core.time.Times;
import com.seatsac.common.core.trace.TraceIds;
import com.seatsac.common.core.trace.TraceKeys;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 좌석 이벤트 발행 — `redis-usage.md` 5·7장. <b>RDS 커밋 뒤에만</b> 부른다.
 *
 * <p>{@code INCR seat-seq:{회차}} → {@code PUBLISH seat-events:{회차}} 순서다. 번호를 먼저 올려야
 * 화면이 빈틈({@code seq != lastSeq + 1})을 감지할 수 있다. Lua 안에서는 발행하지 않는다.
 *
 * <p>발행 실패는 <b>삼키고 로그만</b> 남긴다 — 이미 커밋된 상태 전이를 되돌릴 수 없고,
 * 화면은 Pub/Sub 유실을 스냅샷 재조회(BKG-01)로 복구한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SeatEventPublisher {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /**
     * 요청 밖(만료 스캔·아웃박스 릴레이)에서는 MDC가 비어 있다. 계약({@code seat-event.v1.json})은
     * {@code traceId}를 필수 + 32자리로 못 박아 뒀으므로 그때는 새로 만든다.
     */
    private static String traceId() {
        String current = MDC.get(TraceKeys.MDC_TRACE_ID);
        return TraceIds.isValid(current) ? current : TraceIds.newId();
    }

    public void publish(SeatEventType type, Long screeningId, List<Long> seatIds, Instant occurredAt) {
        try {
            Long seq = redis.opsForValue().increment(RedisKeys.seatSeq(screeningId));
            SeatEventMessage message = SeatEventMessage.builder()
                .seq(seq)
                .type(type)
                .screeningId(screeningId)
                .seatIds(List.copyOf(seatIds))
                .occurredAt(Times.toApi(occurredAt))
                .traceId(traceId())
                .build();
            redis.convertAndSend(RedisKeys.seatEvents(screeningId), objectMapper.writeValueAsString(message));
        } catch (JsonProcessingException | RuntimeException e) {
            // 커밋은 이미 끝났다. 화면은 스냅샷 재조회로 복구한다
            log.warn("좌석 이벤트 발행 실패 type={} screeningId={} seatIds={} cause={}: {}",
                type, screeningId, seatIds, e.getClass().getSimpleName(), e.getMessage());
        }
    }
}

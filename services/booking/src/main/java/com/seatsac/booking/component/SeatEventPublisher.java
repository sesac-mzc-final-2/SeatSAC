package com.seatsac.booking.component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.booking.common.RedisKeys;
import com.seatsac.booking.dto.response.SeatEventMessage;
import com.seatsac.booking.enums.SeatEventType;
import com.seatsac.common.core.time.Times;
import com.seatsac.common.core.trace.TraceIds;
import com.seatsac.common.core.trace.TraceKeys;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 좌석 이벤트 발행 — `redis-usage.md` 5·7장. <b>RDS 커밋 뒤에만</b> 부른다.
 *
 * <p>다음 번호({@code seq-next.lua}) → {@code PUBLISH seat-events:{회차}} 순서다. 번호를 먼저 올려야
 * 화면이 빈틈({@code seq != lastSeq + 1})을 감지할 수 있다. Lua 안에서는 발행하지 않는다.
 *
 * <p>번호는 이벤트마다 1씩 오르는 카운트다. 다만 {@code seat-seq:{회차}} 키가 없을 때(회차의 첫 이벤트,
 * Valkey 재시작·노드 교체로 데이터가 사라진 뒤)는 {@link #startValue(Instant) 현재 시각 ms × 1000}에서
 * 시작한다 — 1부터 다시 세면 열려 있던 화면이 새 이벤트를 지난 번호로 보고 버린다.
 *
 * <p>발행 실패는 <b>삼키고 로그만</b> 남긴다 — 이미 커밋된 상태 전이를 되돌릴 수 없고,
 * 화면은 Pub/Sub 유실을 스냅샷 재조회(BKG-01)로 복구한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SeatEventPublisher {

    /**
     * 시작값 배율. 「현재 시각 ms × 1000」은 사라지기 전 번호보다 크다(그 사이 1ms에 1000건 미만이면).
     * 2026년 기준 16자리로 JavaScript 안전 정수(2^53-1, 약 9.0e15)의 약 1/5이라 화면 비교도 정확하다.
     * 더 키우면(× 1,000,000 등) 그 한도를 넘는다.
     */
    static final long START_MULTIPLIER = 1000L;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RedisScript<Long> seatSeqScript;
    private final Clock clock;

    /** 키가 없을 때 쓸 시작값 (테스트가 기대 번호를 계산할 때도 쓴다) */
    public static long startValue(Instant now) {
        return now.toEpochMilli() * START_MULTIPLIER;
    }

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
            Long seq = redis.execute(seatSeqScript, List.of(RedisKeys.seatSeq(screeningId)),
                String.valueOf(startValue(clock.instant())));
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

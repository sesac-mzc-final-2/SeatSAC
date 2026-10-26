package com.seatsac.booking.component;

import com.seatsac.booking.common.RedisKeys;
import com.seatsac.booking.service.HoldExpiryService;
import com.seatsac.common.core.trace.TraceIds;
import com.seatsac.common.core.trace.TraceKeys;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.stereotype.Component;

/**
 * BKG-04 만료 <b>빠른 경로</b> — Valkey keyspace 알림({@code notify-keyspace-events Ex})으로
 * 선점 키가 만료되는 즉시 받는다. 알림은 키 이름만 주고 저장 값(holdId)은 이미 사라졌으므로,
 * 키 이름의 (회차, 좌석)으로 {@code booking_seat}를 조회해 예매를 찾는다.
 *
 * <p>알림은 유실될 수 있고(구독 중 연결 끊김·파드 재시작) 여러 파드가 같은 알림을 받는다.
 * 그래서 이 경로는 <b>빠르게 하기 위한 것</b>일 뿐이고, 정확성은 조건부 UPDATE와 두 안전망
 * ({@link HoldExpirySweeper})이 담보한다.
 *
 * <p>한 선점의 좌석이 4개면 알림도 4개 온다 — 두 번째부터는 조건부 UPDATE가 0행이라 아무 일도
 * 하지 않는다. 좌석 키 말고 {@code user-hold:*} 만료 알림도 같은 채널로 오는데 키 이름으로 걸러낸다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatKeyExpiredListener implements MessageListener {

    /** DB 번호가 바뀌어도 받도록 {@code @*}로 둔다. 구독은 {@link HoldExpiryTriggerConfig}가 건다 */
    public static final PatternTopic EXPIRED_KEYS = new PatternTopic("__keyevent@*__:expired");

    private final BookingRepositoryReaderForExpiry expiredBookings;
    private final HoldExpiryService holdExpiry;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String key = new String(message.getBody(), StandardCharsets.UTF_8);
        Optional<RedisKeys.SeatKey> seat = RedisKeys.parseSeatKey(key);
        if (seat.isEmpty()) {
            return;   // user-hold 등 다른 키의 만료
        }

        // 구독 콜백은 요청 밖이라 MDC가 비어 있다. 로그에 추적 ID를 남기고(명세 12.2),
        // 스레드가 재사용되므로 끝나면 지운다
        MDC.put(TraceKeys.MDC_TRACE_ID, TraceIds.newId());
        try {
            expiredBookings.findBookingIdBySeat(seat.get().screeningId(), seat.get().seatId())
                .ifPresent(holdExpiry::expire);
        } catch (RuntimeException e) {
            // 안전망 두 개가 같은 선점을 다시 본다. 알림 처리 실패로 구독을 끊지 않는다
            log.warn("만료 알림 처리 실패 key={} — 안전망이 이어받는다", key, e);
        } finally {
            MDC.remove(TraceKeys.MDC_TRACE_ID);
        }
    }
}

package com.seatsac.booking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.booking.component.BookingEventPublisher;
import com.seatsac.booking.dto.event.BookingCanceledEvent;
import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.entity.OutboxMessage;
import com.seatsac.booking.enums.OutboxEventType;
import com.seatsac.common.core.trace.TraceIds;
import com.seatsac.common.core.trace.TraceKeys;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * 6.2.4 SNS {@code booking-events} 아웃박스 행 만들기(D28·D29). 보낼 곳(토픽·로컬 큐)이 설정돼 있을 때만 행을 만든다 —
 * 토픽 전에 행이 쌓이면 릴레이가 10회 재시도 끝에 FAILED로 만들어 손으로 되살려야 한다. 그동안 빠진 이벤트는 user 복제본이
 * 사용자별 첫 조회 때 동기로 채운다(명세 23-1).
 */
@Component
@RequiredArgsConstructor
public class BookingEvents {

    /** BKG-07 확정 예매 취소 행 키 — 예매당 한 번 */
    public static final String CANCELED_KEY = "canceled";
    /** {@code booking.confirmed} SNS 행 키 — 알림 큐 행({@code ''})과 나눈다 */
    public static final String CONFIRMED_SNS_KEY = "sns";

    private final BookingEventPublisher publisher;
    private final ObjectMapper objectMapper;

    public boolean enabled() {
        return publisher.enabled();
    }

    /** 요청·결과 처리 중이면 그 traceId, 아니면(스윕) 새로 */
    public static String currentTraceId() {
        String traceId = MDC.get(TraceKeys.MDC_TRACE_ID);
        return TraceIds.isValid(traceId) ? traceId : TraceIds.newId();
    }

    /** {@code booking.confirmed} SNS 행 — payload는 알림 큐 행과 같은 내용에 eventId만 새로(D28, {@code message_key} UK) */
    public OutboxMessage confirmed(String messageKey, Long bookingId, String payload, String traceId,
                                   String correlationId, Instant now) {
        return OutboxMessage.event(messageKey, OutboxEventType.BOOKING_CONFIRMED, bookingId, CONFIRMED_SNS_KEY,
            payload, traceId, correlationId, now);
    }

    /**
     * {@code booking.canceled} 행. 보낼 곳이 없으면 비어 있다.
     *
     * @param aggregateKey {@link #CANCELED_KEY}(BKG-07) 또는 {@code canceled:{checkoutId}}(saga 보상·만료)
     */
    public Optional<OutboxMessage> canceled(Booking booking, BookingCanceledEvent.Reason reason, String aggregateKey,
                                            String traceId, String correlationId, Instant now) {
        if (!enabled()) {
            return Optional.empty();
        }
        String messageKey = UUID.randomUUID().toString();
        String payload;
        try {
            payload = objectMapper.writeValueAsString(
                BookingCanceledEvent.of(messageKey, booking, reason, traceId, correlationId, now));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("취소 이벤트 본문을 만들 수 없다", e);
        }
        return Optional.of(OutboxMessage.event(messageKey, OutboxEventType.BOOKING_CANCELED, booking.getBookingId(),
            aggregateKey, payload, traceId, correlationId, now));
    }
}

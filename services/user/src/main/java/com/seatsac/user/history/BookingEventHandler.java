package com.seatsac.user.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.common.core.trace.TraceIds;
import com.seatsac.common.core.trace.TraceKeys;
import com.seatsac.user.dto.BookingHistoryStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 6.3.4b {@code user-booking-events} 소비 — {@code booking.confirmed}·{@code booking.canceled}(계약 {@code contracts/events})로
 * 예매 내역 복제본을 채운다(API 명세서 v1.5 23-1). inbox({@code processed_message})와 반영을 한 트랜잭션에 쓴다.
 *
 * <ul>
 *   <li>{@code booking.confirmed} → CONFIRMED upsert. {@code amount}·{@code discountAmount}는 있으면 저장</li>
 *   <li>{@code booking.canceled} {@code USER_CANCELED} → CANCELED(행이 없으면 먼저 만든다 — CANCELED가 이긴다)</li>
 *   <li>{@code booking.canceled} {@code EXPIRED}·{@code PAYMENT_FAILED} → 확정된 적 없는 예매라 무시</li>
 *   <li>모르는 {@code type}은 무시(토픽에 새 이벤트가 생겨도 막히지 않게 — 소비자는 모르는 것을 무시한다)</li>
 * </ul>
 * 형식이 틀리면 {@link MalformedEventException}(리스너가 지운다). DB 오류는 그대로 올라가 재배달 3회 뒤 DLQ.
 * 지표 {@code user_message_consumed_total{type,result=ok|duplicate|error}}(런타임 계약 5장).
 */
@Service
public class BookingEventHandler {

    public static final String CONFIRMED = "booking.confirmed";
    public static final String CANCELED = "booking.canceled";

    /** processed_message.message_id CHAR(36) */
    private static final int ID_MAX = 36;
    private static final int USER_ID_MAX = 36;

    private static final Logger log = LoggerFactory.getLogger(BookingEventHandler.class);

    private final ObjectMapper objectMapper;
    private final BookingHistoryStore store;
    private final TransactionTemplate tx;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public BookingEventHandler(ObjectMapper objectMapper, BookingHistoryStore store, TransactionTemplate tx,
                               MeterRegistry meterRegistry, Clock clock) {
        this.objectMapper = objectMapper;
        this.store = store;
        this.tx = tx;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    public enum Result { APPLIED, IGNORED, DUPLICATE }

    /** @throws MalformedEventException 형식이 틀린 이벤트(재배달해도 같다 → 리스너가 지운다) */
    public Result handle(String body) {
        JsonNode root = parse(body);
        String eventId = text(root, "eventId");
        String type = text(root, "type");
        JsonNode payload = root.get("payload");
        if (eventId == null || type == null || payload == null || !payload.isObject()) {
            count("unknown", "error");
            throw new MalformedEventException("envelope 필수 값이 없다(eventId·type·payload)");
        }
        if (eventId.length() > ID_MAX) {
            count("unknown", "error");
            throw new MalformedEventException("eventId가 " + ID_MAX + "자를 넘는다");
        }
        String traceId = text(root, "traceId");
        MDC.put(TraceKeys.MDC_TRACE_ID, TraceIds.isValid(traceId) ? traceId : TraceIds.newId());
        String label = CONFIRMED.equals(type) || CANCELED.equals(type) ? type : "other";
        String result = "error";
        try {
            BookingHistoryStore.Row row = switch (type) {
                case CONFIRMED -> confirmed(payload);
                case CANCELED -> canceled(payload);
                default -> null;
            };
            Instant now = clock.instant();
            Boolean fresh = tx.execute(status -> {
                if (!store.markProcessed(eventId, type, now)) {
                    return false;
                }
                if (row != null) {
                    store.upsert(row, now);
                }
                return true;
            });
            if (!Boolean.TRUE.equals(fresh)) {
                result = "duplicate";
                log.info("이미 반영한 이벤트 eventId={} type={}", eventId, type);
                return Result.DUPLICATE;
            }
            result = "ok";
            if (row == null) {
                log.info("예매 내역 대상이 아닌 이벤트 — 무시 eventId={} type={}", eventId, type);
                return Result.IGNORED;
            }
            log.info("예매 내역 복제본 반영 eventId={} type={} bookingId={} status={}", eventId, type, row.bookingId(),
                row.status());
            return Result.APPLIED;
        } finally {
            count(label, result);
            MDC.remove(TraceKeys.MDC_TRACE_ID);
        }
    }

    private BookingHistoryStore.Row confirmed(JsonNode p) {
        Instant confirmedAt = requiredTime(p, "confirmedAt");
        List<String> labels = new ArrayList<>();
        JsonNode seatLabels = p.get("seatLabels");
        if (seatLabels == null || !seatLabels.isArray()) {
            throw new MalformedEventException("booking.confirmed seatLabels가 배열이 아니다");
        }
        seatLabels.forEach(node -> labels.add(node.asText()));
        Long seatCount = optionalLong(p, "seatCount");
        return new BookingHistoryStore.Row(requiredLong(p, "bookingId"), userId(p), BookingHistoryStatus.CONFIRMED,
            requiredLong(p, "screeningId"), List.copyOf(labels),
            seatCount == null ? labels.size() : seatCount.intValue(),
            optionalLong(p, "amount"), optionalLong(p, "discountAmount"), null, confirmedAt, null);
    }

    /** @return null이면 내역 대상이 아니다(EXPIRED·PAYMENT_FAILED — 확정된 적 없는 예매) */
    private BookingHistoryStore.Row canceled(JsonNode p) {
        String reason = text(p, "reason");
        if (reason == null) {
            throw new MalformedEventException("booking.canceled reason이 없다");
        }
        long bookingId = requiredLong(p, "bookingId");
        String userId = userId(p);
        long screeningId = requiredLong(p, "screeningId");
        Instant canceledAt = requiredTime(p, "canceledAt");
        if (!"USER_CANCELED".equals(reason)) {
            return null;
        }
        return new BookingHistoryStore.Row(bookingId, userId, BookingHistoryStatus.CANCELED, screeningId, null, null,
            null, null, null, null, canceledAt);
    }

    private JsonNode parse(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            count("unknown", "error");
            throw new MalformedEventException("이벤트 본문이 JSON이 아니다", e);
        }
        if (root == null || !root.isObject()) {
            count("unknown", "error");
            throw new MalformedEventException("이벤트 본문이 객체가 아니다");
        }
        return root;
    }

    private static String userId(JsonNode p) {
        String userId = text(p, "userId");
        if (userId == null || userId.length() > USER_ID_MAX) {
            throw new MalformedEventException("userId가 없거나 " + USER_ID_MAX + "자를 넘는다");
        }
        return userId;
    }

    private static long requiredLong(JsonNode p, String field) {
        Long value = optionalLong(p, field);
        if (value == null) {
            throw new MalformedEventException(field + "가 없거나 정수가 아니다");
        }
        return value;
    }

    private static Long optionalLong(JsonNode p, String field) {
        JsonNode node = p.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new MalformedEventException(field + "가 정수가 아니다");
        }
        return node.asLong();
    }

    private static Instant requiredTime(JsonNode p, String field) {
        String value = text(p, field);
        if (value == null) {
            throw new MalformedEventException(field + "가 없다");
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw new MalformedEventException(field + "가 ISO-8601 시각이 아니다", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private void count(String type, String result) {
        meterRegistry.counter("user_message_consumed_total", "type", type, "result", result).increment();
    }
}

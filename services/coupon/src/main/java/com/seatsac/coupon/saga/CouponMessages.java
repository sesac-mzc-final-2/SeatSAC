package com.seatsac.coupon.saga;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.seatsac.common.core.time.Times;
import com.seatsac.common.core.trace.TraceIds;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * saga 메시지 — 계약 {@code contracts/events/coupon.*.v1.json}(#187) 그대로. 명령 envelope을 읽고 결과 envelope을 만든다.
 *
 * <p>envelope: {@code {eventId, type, version, occurredAt, producer, traceId, correlationId, payload}}.
 * 결과의 {@code traceId}·{@code correlationId}는 명령 값을 이어받는다(traceId 형식이 틀리면 새로 만든다).
 */
@Component
public class CouponMessages {

    public static final String RESERVATION_REQUESTED = "coupon.reservation-requested";
    public static final String USE_REQUESTED = "coupon.use-requested";
    public static final String RELEASE_REQUESTED = "coupon.release-requested";

    public static final String RESERVED = "coupon.reserved";
    public static final String RESERVATION_REJECTED = "coupon.reservation-rejected";

    /** UUID 길이 — eventId·correlationId·checkoutId 저장 열이 CHAR(36) */
    static final int ID_MAX = 36;
    /** couponIssueId 저장 열 — coupon_reservation.coupon_issue_id VARCHAR(64) */
    static final int COUPON_ISSUE_ID_MAX = 64;

    private final ObjectMapper objectMapper;

    public CouponMessages(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 받은 명령 */
    public record Command(String eventId, String type, String traceId, String correlationId, JsonNode payload) {

        public String checkoutId() {
            return text("checkoutId");
        }

        public String couponIssueId() {
            return text("couponIssueId");
        }

        public String userId() {
            return text("userId");
        }

        public Long bookingId() {
            JsonNode node = payload.get("bookingId");
            return node == null || !node.canConvertToLong() ? null : node.asLong();
        }

        public String text(String name) {
            JsonNode node = payload.get(name);
            return node == null || node.isNull() ? null : node.asText();
        }
    }

    /** 보낼 결과 — outbox에 그대로 저장한다 */
    public record Reply(String eventId, String type, String traceId, String correlationId, String json) {
    }

    /**
     * @throws MalformedCommandException JSON이 아니거나 envelope 필수 값(eventId·type·payload)이 없다 — 재배달해도 같으니
     *                                   부르는 쪽이 지운다(04 5장)
     */
    public Command parse(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new MalformedCommandException("명령 본문이 JSON이 아니다", e);
        }
        if (root == null || !root.isObject()) {
            throw new MalformedCommandException("명령 본문이 객체가 아니다");
        }
        String eventId = textOf(root, "eventId");
        String type = textOf(root, "type");
        JsonNode payload = root.get("payload");
        if (eventId == null || type == null || payload == null || !payload.isObject()) {
            throw new MalformedCommandException("envelope 필수 값이 없다(eventId·type·payload)");
        }
        String correlationId = textOf(root, "correlationId");
        // processed_message.message_id·outbox correlation_id가 CHAR(36) — 넘으면 저장에서 실패해 재배달만 반복한다
        if (eventId.length() > ID_MAX || (correlationId != null && correlationId.length() > ID_MAX)) {
            throw new MalformedCommandException("eventId·correlationId가 " + ID_MAX + "자를 넘는다");
        }
        return new Command(eventId, type, textOf(root, "traceId"), correlationId, payload);
    }

    public Reply reserved(Command cause, String couponIssueId, int discountRate, Long couponEventId, Instant now) {
        return reply(cause, RESERVED, now, p -> {
            p.put("couponIssueId", couponIssueId);
            p.put("discountRate", discountRate);
            if (couponEventId != null) {
                p.put("eventId", couponEventId);
            }
        });
    }

    public Reply rejected(Command cause, String reason, Instant now) {
        return reply(cause, RESERVATION_REJECTED, now, p -> {
            p.put("couponIssueId", cause.couponIssueId());
            p.put("reason", reason);
        });
    }

    private Reply reply(Command cause, String type, Instant now, Consumer<ObjectNode> body) {
        String eventId = UUID.randomUUID().toString();
        String traceId = TraceIds.isValid(cause.traceId()) ? cause.traceId() : TraceIds.newId();
        String correlationId = cause.correlationId() != null ? cause.correlationId() : cause.checkoutId();

        ObjectNode root = objectMapper.createObjectNode();
        root.put("eventId", eventId);
        root.put("type", type);
        root.put("version", 1);
        root.put("occurredAt", Times.toApi(now).toString());
        root.put("producer", "coupon");
        root.put("traceId", traceId);
        root.put("correlationId", correlationId);
        ObjectNode payload = root.putObject("payload");
        payload.put("checkoutId", cause.checkoutId());
        body.accept(payload);
        if (cause.bookingId() != null) {
            payload.put("bookingId", cause.bookingId());
        }
        try {
            return new Reply(eventId, type, traceId, correlationId, objectMapper.writeValueAsString(root));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }
}

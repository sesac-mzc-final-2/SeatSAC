package com.seatsac.payment.saga;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.seatsac.common.core.time.Times;
import com.seatsac.common.core.trace.TraceIds;
import com.seatsac.payment.dto.response.PaymentResponse;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * saga 메시지 — 계약 {@code contracts/events/payment.*.v1.json}(#176) 그대로. 명령 envelope을 읽고 결과 envelope을 만든다.
 *
 * <p>envelope: {@code {eventId, type, version, occurredAt, producer, traceId, correlationId, payload}}.
 * 결과의 {@code traceId}·{@code correlationId}는 명령 값을 이어받는다(traceId 형식이 틀리면 새로 만든다).
 */
@Component
public class SagaMessages {

    public static final String PREPARATION_REQUESTED = "payment.preparation-requested";
    public static final String CHARGE_REQUESTED = "payment.charge-requested";
    public static final String VERIFICATION_REQUESTED = "payment.verification-requested";
    public static final String RELEASE_REQUESTED = "payment.release-requested";

    public static final String PREPARED = "payment.prepared";
    public static final String PREPARATION_FAILED = "payment.preparation-failed";
    public static final String APPROVED = "payment.approved";
    public static final String DECLINED = "payment.declined";
    public static final String RESULT_UNKNOWN = "payment.result-unknown";

    private final ObjectMapper objectMapper;

    public SagaMessages(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 받은 명령. {@code payload}는 명령마다 필드가 달라 노드로 둔다 */
    public record Command(String eventId, String type, String traceId, String correlationId, JsonNode payload) {

        public String checkoutId() {
            return text("checkoutId");
        }

        public String paymentId() {
            return text("paymentId");
        }

        public Long bookingId() {
            JsonNode node = payload.get("bookingId");
            return node == null || !node.canConvertToLong() ? null : node.asLong();
        }

        public Integer intField(String name) {
            JsonNode node = payload.get(name);
            return node == null || !node.canConvertToInt() ? null : node.asInt();
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
        String eventId = textOf(root, "eventId");
        String type = textOf(root, "type");
        JsonNode payload = root.get("payload");
        if (eventId == null || type == null || payload == null || !payload.isObject()) {
            throw new MalformedCommandException("명령 envelope에 eventId·type·payload가 없다");
        }
        return new Command(eventId, type, textOf(root, "traceId"), textOf(root, "correlationId"), payload);
    }

    public Reply prepared(Command cause, PaymentResponse.Checkout checkout, Instant now) {
        return reply(cause, PREPARED, now, p -> {
            ObjectNode c = p.putObject("checkout");
            c.put("provider", checkout.provider());
            c.put("storeId", checkout.storeId());
            c.put("channelKey", checkout.channelKey());
            c.put("payMethod", checkout.payMethod());
            c.put("currency", checkout.currency());
        });
    }

    public Reply preparationFailed(Command cause, String reason, Instant now) {
        return reply(cause, PREPARATION_FAILED, now, p -> p.put("reason", reason));
    }

    public Reply approved(Command cause, PaymentResponse payment, Instant now) {
        return reply(cause, APPROVED, now, p -> {
            p.put("amount", payment.amount());
            p.put("approvedAt", payment.approvedAt() == null ? Times.toApi(now).toString()
                : payment.approvedAt().toString());
            p.put("pgTransactionId", payment.pgTransactionId());
        });
    }

    public Reply declined(Command cause, String failureCode, boolean retryable, Instant now) {
        return reply(cause, DECLINED, now, p -> {
            p.put("failureCode", failureCode);
            p.put("retryable", retryable);
        });
    }

    public Reply resultUnknown(Command cause, String reason, Instant now) {
        Integer attempt = cause.intField("attempt");
        return reply(cause, RESULT_UNKNOWN, now, p -> {
            p.put("reason", reason);
            p.put("attempt", attempt == null || attempt < 1 ? 1 : attempt);
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
        root.put("producer", "payment");
        root.put("traceId", traceId);
        root.put("correlationId", correlationId);
        ObjectNode payload = root.putObject("payload");
        payload.put("checkoutId", cause.checkoutId());
        payload.put("paymentId", cause.paymentId());
        payload.put("bookingId", cause.bookingId());
        body.accept(payload);
        try {
            return new Reply(eventId, type, traceId, correlationId, objectMapper.writeValueAsString(root));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("결과 메시지를 만들지 못했다", e);
        }
    }

    private static String textOf(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }
}

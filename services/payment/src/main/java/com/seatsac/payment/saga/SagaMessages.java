package com.seatsac.payment.saga;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.seatsac.common.core.time.Times;
import com.seatsac.common.core.trace.TraceIds;
import com.seatsac.common.core.trace.TraceKeys;
import com.seatsac.payment.dto.response.PaymentResponse;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.MDC;
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

    /** D7 — 명령 없이 payment가 스스로 닫은 saga 결제의 승인(스윕·웹훅) */
    public Reply approved(SagaPayment saga, int amount, Instant approvedAt, String pgTransactionId, Instant now) {
        return reply(Ids.of(saga), APPROVED, now, p -> {
            p.put("amount", amount);
            p.put("approvedAt", Times.toApi(approvedAt == null ? now : approvedAt).toString());
            p.put("pgTransactionId", pgTransactionId);
        });
    }

    public Reply declined(Command cause, String failureCode, boolean retryable, Instant now) {
        return reply(cause, DECLINED, now, p -> {
            p.put("failureCode", failureCode);
            p.put("retryable", retryable);
        });
    }

    /** D7 — 명령 없이 payment가 스스로 닫은 saga 결제의 실패(스윕·웹훅·MOCK 기동 정리) */
    public Reply declined(SagaPayment saga, String failureCode, boolean retryable, Instant now) {
        return reply(Ids.of(saga), DECLINED, now, p -> {
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

    /** 결과 envelope을 만들 값 — 명령에서, 또는 명령 없는 D7이면 saga 표시 행에서 */
    private record Ids(String checkoutId, String paymentId, Long bookingId, String traceId, String correlationId) {

        static Ids of(Command cause) {
            return new Ids(cause.checkoutId(), cause.paymentId(), cause.bookingId(), cause.traceId(),
                cause.correlationId() != null ? cause.correlationId() : cause.checkoutId());
        }

        /** 스윕·웹훅에는 이어받을 traceId가 없다 — 지금 로그의 traceId(MDC), 없으면 새로 */
        static Ids of(SagaPayment saga) {
            return new Ids(saga.getCheckoutId(), saga.getPaymentId(), saga.getBookingId(),
                MDC.get(TraceKeys.MDC_TRACE_ID), saga.getCheckoutId());
        }
    }

    private Reply reply(Command cause, String type, Instant now, Consumer<ObjectNode> body) {
        return reply(Ids.of(cause), type, now, body);
    }

    private Reply reply(Ids ids, String type, Instant now, Consumer<ObjectNode> body) {
        String eventId = UUID.randomUUID().toString();
        String traceId = TraceIds.isValid(ids.traceId()) ? ids.traceId() : TraceIds.newId();
        String correlationId = ids.correlationId();

        ObjectNode root = objectMapper.createObjectNode();
        root.put("eventId", eventId);
        root.put("type", type);
        root.put("version", 1);
        root.put("occurredAt", Times.toApi(now).toString());
        root.put("producer", "payment");
        root.put("traceId", traceId);
        root.put("correlationId", correlationId);
        ObjectNode payload = root.putObject("payload");
        payload.put("checkoutId", ids.checkoutId());
        payload.put("paymentId", ids.paymentId());
        payload.put("bookingId", ids.bookingId());
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

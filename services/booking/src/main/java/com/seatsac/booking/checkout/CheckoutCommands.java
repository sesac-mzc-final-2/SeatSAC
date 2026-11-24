package com.seatsac.booking.checkout;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.entity.OutboxMessage;
import com.seatsac.booking.enums.OutboxEventType;
import com.seatsac.booking.repository.OutboxMessageRepository;
import com.seatsac.common.core.time.Times;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * saga 명령을 아웃박스에 넣는다 — 계약 {@code contracts/events/payment.*-requested.v1.json}(#176) 그대로.
 * <b>부르는 쪽 트랜잭션 안에서만</b> 쓴다(상태 전이와 명령 INSERT가 함께 커밋돼야 한다). 보내기는 릴레이가 한다.
 *
 * <p>{@code aggregate_key}: 같은 체크아웃의 같은 명령은 한 번만(유일 키) — 준비·청구 {@code {checkoutId}:payment},
 * 재확인 {@code {checkoutId}:verify:{attempt}}(시도마다 새 행·새 eventId — payment inbox가 중복으로 버리지 않게),
 * 해제 {@code {checkoutId}:release:{n}}(늦은 승인 뒤 한 번 더 보낼 수 있게, D16).
 */
@Component
@RequiredArgsConstructor
public class CheckoutCommands {

    private final OutboxMessageRepository outbox;
    private final ObjectMapper objectMapper;

    public void preparation(CheckoutSaga saga, String paymentId, int amount, int seatCount, Instant now) {
        save(saga, OutboxEventType.PAYMENT_PREPARATION_REQUESTED, saga.getCheckoutId() + ":payment", paymentId, now,
            p -> {
                p.put("amount", amount);
                p.put("method", "TOSSPAY");
                p.put("orderName", BookingPolicy.orderNameOf(seatCount));
            });
    }

    public void charge(CheckoutSaga saga, String paymentId, int amount, String cardLast4, Instant now) {
        save(saga, OutboxEventType.PAYMENT_CHARGE_REQUESTED, saga.getCheckoutId() + ":payment", paymentId, now, p -> {
            p.put("amount", amount);
            p.put("method", "CARD");
            p.put("cardLast4", cardLast4);
        });
    }

    public void verification(CheckoutSaga saga, int attempt, Instant now) {
        save(saga, OutboxEventType.PAYMENT_VERIFICATION_REQUESTED, saga.getCheckoutId() + ":verify:" + attempt,
            saga.getPaymentId(), now, p -> {
                p.put("expectedAmount", saga.getFinalAmount());
                p.put("attempt", attempt);
            });
    }

    /** @param sequence 같은 체크아웃의 몇 번째 해제인지(만료 1, 늦은 승인 뒤 재발송 2…) */
    public void release(CheckoutSaga saga, int sequence, Instant now) {
        save(saga, OutboxEventType.PAYMENT_RELEASE_REQUESTED, saga.getCheckoutId() + ":release:" + sequence,
            saga.getPaymentId(), now, p -> p.put("reason", "HOLD_EXPIRED"));
    }

    private void save(CheckoutSaga saga, OutboxEventType type, String aggregateKey, String paymentId, Instant now,
                      Consumer<ObjectNode> fields) {
        String eventId = UUID.randomUUID().toString();
        ObjectNode root = objectMapper.createObjectNode();
        root.put("eventId", eventId);
        root.put("type", type.messageType());
        root.put("version", 1);
        root.put("occurredAt", Times.toApi(now).toString());
        root.put("producer", "booking");
        root.put("traceId", saga.getTraceId());
        root.put("correlationId", saga.getCheckoutId());
        ObjectNode payload = root.putObject("payload");
        payload.put("checkoutId", saga.getCheckoutId());
        payload.put("paymentId", paymentId);
        payload.put("bookingId", saga.getBookingId());
        fields.accept(payload);
        String json;
        try {
            json = objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("명령 메시지를 만들지 못했다", e);
        }
        outbox.save(OutboxMessage.command(eventId, type, saga.getBookingId(), aggregateKey, json, saga.getTraceId(),
            saga.getCheckoutId(), now));
    }
}

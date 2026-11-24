package com.seatsac.booking.checkout;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.booking.component.PaymentClient.PaymentResult;
import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.booking.service.BookingPaymentResultApplier;
import com.seatsac.booking.service.BookingPaymentResultApplier.Applied;
import com.seatsac.booking.service.BookingRepositoryReader;
import com.seatsac.common.core.trace.TraceIds;
import com.seatsac.common.core.trace.TraceKeys;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * saga 결과 처리(6.2.2, 설계 01 5-2) — {@code booking-replies}의 payment 결과 5종. 계약 {@code payment.*.v1.json}(#176).
 *
 * <p>순서(D27): ① inbox 조회 → ② 예매 반영은 기존 메서드(동기 경로와 같은 {@link BookingPaymentResultApplier}, 자기 트랜잭션)
 * → ③ {@link CheckoutResultTx} 한 트랜잭션(inbox + saga 조건부 전이 + 명령). 늦은 결과·중복은 조건부 전이가 0행이라 무시된다.
 * 모르는 {@code type}(coupon 결과는 어댑터가 {@code sqs}일 때)은 로그 후 받은 것으로 친다(DLQ로 보내지 않음, 설계 04 5장).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutReplyHandler {

    static final Set<CheckoutState> PAYING = EnumSet.of(CheckoutState.READY_TO_PAY, CheckoutState.VERIFYING,
        CheckoutState.CHARGING);

    private final ObjectMapper objectMapper;
    private final ProcessedMessageRepository inbox;
    private final CheckoutSagaRepository sagas;
    private final CheckoutResultTx tx;
    private final BookingRepositoryReader bookingReader;
    private final BookingPaymentResultApplier resultApplier;
    private final CheckoutProperties properties;
    private final CheckoutIntervals intervals;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    /** 메시지 하나 — 끝까지 처리했거나(중복 포함) 무시해도 되면 정상 반환, 다시 받아야 하면 예외 */
    public void handle(String body) {
        JsonNode root = parse(body);
        String eventId = text(root, "eventId");
        String type = text(root, "type");
        JsonNode payload = root.get("payload");
        if (eventId == null || type == null || payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("결과 envelope에 eventId·type·payload가 없다");
        }
        String traceId = text(root, "traceId");
        MDC.put(TraceKeys.MDC_TRACE_ID, TraceIds.isValid(traceId) ? traceId : TraceIds.newId());
        String result = "error";
        try {
            if (inbox.existsById(eventId)) {
                result = "duplicate";
                return;
            }
            Optional<CheckoutSaga> saga = Optional.ofNullable(text(payload, "checkoutId")).flatMap(sagas::findById);
            if (saga.isEmpty()) {
                // 우리 saga가 아니거나 이미 지워졌다 — 다시 받아도 같다
                log.warn("모르는 체크아웃의 결과 — 무시한다 type={} checkoutId={}", type, text(payload, "checkoutId"));
                tx.ignore(eventId, type, clock.instant());
                result = "ok";
                return;
            }
            dispatch(eventId, type, payload, saga.get());
            result = "ok";
        } catch (DataIntegrityViolationException e) {
            result = "duplicate";   // 같은 결과가 동시에 두 번 — 먼저 끝난 쪽이 반영했다
        } finally {
            meterRegistry.counter("booking_message_consumed_total", "type", type, "result", result).increment();
            MDC.remove(TraceKeys.MDC_TRACE_ID);
        }
    }

    private void dispatch(String eventId, String type, JsonNode payload, CheckoutSaga saga) {
        Instant now = clock.instant();
        switch (type) {
            case "payment.prepared" -> onPrepared(eventId, type, payload, saga, now);
            case "payment.preparation-failed" ->
                decline(eventId, type, saga, EnumSet.of(CheckoutState.PAYMENT_PREPARING), text(payload, "reason"), now);
            case "payment.approved" -> onApproved(eventId, type, payload, saga);
            case "payment.declined" -> onDeclined(eventId, type, payload, saga, now);
            case "payment.result-unknown" -> onUnknown(eventId, type, payload, saga, now);
            default -> {
                log.warn("모르는 결과 type — 무시한다 type={} checkoutId={}", type, saga.getCheckoutId());
                tx.ignore(eventId, type, now);
            }
        }
    }

    private void onPrepared(String eventId, String type, JsonNode payload, CheckoutSaga saga, Instant now) {
        Booking booking = booking(saga);
        if (!tx.readyToPay(eventId, type, saga, payload.path("checkout").toString(), booking.getExpiresAt(), now)) {
            log.info("결제 준비 결과가 늦었다 — 무시 checkoutId={} state={}", saga.getCheckoutId(), saga.getState());
        }
    }

    private void onApproved(String eventId, String type, JsonNode payload, CheckoutSaga saga) {
        Booking booking = booking(saga);
        if (saga.getState() == CheckoutState.EXPIRED) {
            // payment가 해제 명령을 못 받고 승인했다(D16) — 확정하지 않는다. 해제를 한 번 더 보내 payment가 환불하게 한다
            log.warn("만료된 체크아웃에 승인 — 해제를 다시 보낸다 checkoutId={} bookingId={}", saga.getCheckoutId(),
                saga.getBookingId());
            tx.refundInProgress(eventId, type, saga, true, 2, clock.instant());
            return;
        }
        if (!PAYING.contains(saga.getState()) || booking.getStatus() != BookingStatus.PENDING) {
            // 예매가 이미 닫혔으면 스윕이 saga를 닫고 해제 명령을 보낸다(그 승인은 payment가 환불)
            tx.ignore(eventId, type, clock.instant());
            return;
        }
        PaymentResult payment = new PaymentResult(text(payload, "paymentId"), saga.getBookingId(),
            payload.path("amount").asInt(), "APPROVED", null, null, null, instant(payload, "approvedAt"), null,
            saga.getMethod().name(), null, text(payload, "pgTransactionId"), null);
        Applied applied = resultApplier.applyApproved(booking, payment, false);
        Instant now = clock.instant();
        if (applied == Applied.CONFIRMED || applied == Applied.ALREADY_CONFIRMED) {
            if (tx.confirmed(eventId, type, saga, PAYING, now)) {
                count("confirmed");
                log.info("체크아웃 확정 checkoutId={} bookingId={}", saga.getCheckoutId(), saga.getBookingId());
            }
            return;
        }
        // 확정 직전에 선점이 만료됐다 — 예매는 만료 처리가, saga는 스윕이 닫고 해제 명령을 보낸다
        tx.ignore(eventId, type, now);
    }

    private void onDeclined(String eventId, String type, JsonNode payload, CheckoutSaga saga, Instant now) {
        String failureCode = text(payload, "failureCode");
        if (saga.getState() == CheckoutState.EXPIRED) {
            if ("BOOKING_EXPIRED".equals(failureCode)) {
                // 해제 명령을 받은 payment가 늦은 PAID를 환불 대상으로 닫았다(D22)
                tx.refundInProgress(eventId, type, saga, false, 0, now);
            } else {
                tx.ignore(eventId, type, now);
            }
            return;
        }
        boolean retryable = payload.path("retryable").asBoolean(false);
        if (retryable && properties.paymentRetryEnabled() && saga.getAttempt() < properties.maxAttempts()
            && PAYING.contains(saga.getState())) {
            // D25 — 돈이 안 빠진 실패. 좌석·예매·결제 ID 그대로 두고 다시 결제할 수 있게 한다(쿠폰만 반환)
            if (tx.finish(eventId, type, saga, PAYING, CheckoutState.PAYMENT_RETRYABLE, "payment_declined",
                "PAYMENT_DECLINED", failureCode, null, false, now)) {
                count("payment_retryable");
            }
            return;
        }
        decline(eventId, type, saga, PAYING, failureCode, now);
    }

    /** 거절 보상 — 좌석 해제(동기와 같은 applyDeclined) → saga FAILED + 쿠폰 반환 */
    private void decline(String eventId, String type, CheckoutSaga saga, Set<CheckoutState> expected, String reason,
                         Instant now) {
        if (!expected.contains(saga.getState())) {
            tx.ignore(eventId, type, now);
            return;
        }
        Booking booking = booking(saga);
        if (booking.getStatus() == BookingStatus.PENDING) {
            resultApplier.applyDeclined(booking);
        }
        if (tx.finish(eventId, type, saga, expected, CheckoutState.FAILED, "payment_declined", "PAYMENT_DECLINED", reason,
            null, false, clock.instant())) {
            count("failed");
            meterRegistry.counter("booking_checkout_compensation_total", "reason", "payment_declined").increment();
        }
    }

    private void onUnknown(String eventId, String type, JsonNode payload, CheckoutSaga saga, Instant now) {
        if (!CheckoutState.MONEY_MAY_MOVE.contains(saga.getState())) {
            tx.ignore(eventId, type, now);
            return;
        }
        String reason = text(payload, "reason");
        Instant deadline = intervals.nextVerify(saga.getVerifyAttempt(), reason, booking(saga).getExpiresAt(), now);
        tx.noteUnknown(eventId, type, saga, reason, deadline, now);
    }

    private Booking booking(CheckoutSaga saga) {
        return bookingReader.findById(saga.getBookingId())
            .orElseThrow(() -> new IllegalStateException("체크아웃의 예매가 없다 bookingId=" + saga.getBookingId()));
    }

    private void count(String result) {
        meterRegistry.counter("booking_checkout_total", "result", result).increment();
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("결과 본문이 JSON이 아니다", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : OffsetDateTime.parse(value).toInstant();
    }
}

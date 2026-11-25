package com.seatsac.booking.checkout;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.booking.component.PaymentClient.PaymentResult;
import com.seatsac.booking.dto.event.BookingConfirmedEvent;
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

    private static final Set<String> KNOWN_TYPES = Set.of("payment.prepared", "payment.preparation-failed",
        "payment.approved", "payment.declined", "payment.result-unknown",
        "coupon.reserved", "coupon.reservation-rejected");

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
    private final CheckoutTimings timings;
    private final Clock clock;

    /** 메시지 하나 — 끝까지 처리했거나(중복 포함) 무시해도 되면 정상 반환, 다시 받아야 하면 예외 */
    public void handle(String body) {
        JsonNode root;
        try {
            root = parse(body);
        } catch (MalformedReplyException e) {
            countMalformed();
            throw e;
        }
        String eventId = text(root, "eventId");
        String type = text(root, "type");
        JsonNode payload = root.get("payload");
        if (eventId == null || type == null || payload == null || !payload.isObject()) {
            countMalformed();
            throw new MalformedReplyException("결과 envelope에 eventId·type·payload가 없다");
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
            // inbox에 이 결과가 있을 때만 「같은 결과의 동시 처리」다. 그 밖의 유일 키 충돌은 처리되지 않은 것이라
            // 예외를 올려 재배달한다(지우면 결과가 사라진다)
            if (!inbox.existsById(eventId)) {
                log.warn("결과 처리 중 유일 키 충돌 — 재배달한다 eventId={} type={}", eventId, type);
                throw e;
            }
            result = "duplicate";
        } finally {
            meterRegistry.counter("booking_message_consumed_total", "type", typeLabel(type), "result", result).increment();
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
        String paymentId = text(payload, "paymentId");
        if (saga.getState() == CheckoutState.EXPIRED) {
            // payment가 해제 명령을 못 받고 승인했다(D16) — 확정하지 않는다. 해제를 한 번 더 보내 payment가 환불하게 한다
            log.warn("만료된 체크아웃에 승인 — 해제를 다시 보낸다 checkoutId={} bookingId={}", saga.getCheckoutId(),
                saga.getBookingId());
            tx.refundInProgress(eventId, type, saga, paymentId, clock.instant());
            return;
        }
        if (!saga.getState().isActive()) {
            if (saga.getState() == CheckoutState.CONFIRMED && paymentId != null && paymentId.equals(saga.getPaymentId())) {
                tx.ignore(eventId, type, clock.instant());   // 이 saga를 확정한 결제의 중복 승인
                return;
            }
            // 끝난 saga(실패·재결제 대기·다른 결제로 확정)에 온 승인 — 확정에 쓰지 않는 돈이라 환불한다(06 5장)
            log.warn("끝난 체크아웃에 승인 — 해제 명령으로 환불한다 checkoutId={} state={} bookingId={}",
                saga.getCheckoutId(), saga.getState(), saga.getBookingId());
            tx.releaseLate(eventId, type, saga, paymentId, clock.instant());
            return;
        }
        if (!PAYING.contains(saga.getState()) || booking.getStatus() != BookingStatus.PENDING) {
            // 진행 중인데 예매가 이미 닫혔으면 스윕이 saga를 닫고 해제 명령을 보낸다(그 승인은 payment가 환불)
            tx.ignore(eventId, type, clock.instant());
            return;
        }
        PaymentResult payment = new PaymentResult(paymentId, saga.getBookingId(),
            payload.path("amount").asInt(), "APPROVED", null, null, null, instant(payload, "approvedAt"), null,
            saga.getMethod().name(), null, text(payload, "pgTransactionId"), null);
        timings.step("verified", saga.getCreatedAt(), clock.instant());
        // 6.2.4 — booking.confirmed의 amount는 할인 뒤 최종 금액, discountAmount·correlationId(checkoutId)
        int original = saga.getOriginalAmount() == null ? booking.getAmount() : saga.getOriginalAmount();
        BookingConfirmedEvent.Pricing pricing = new BookingConfirmedEvent.Pricing(
            saga.getFinalAmount() == null ? original : saga.getFinalAmount(),
            saga.getDiscountAmount() == null ? 0 : saga.getDiscountAmount(), saga.getCheckoutId());
        Applied applied = resultApplier.applyApproved(booking, payment, false, pricing);
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
                tx.refundInProgress(eventId, type, saga, null, now);
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

    /** 지표 {@code type} 라벨 — 모르는 type은 {@code unknown}으로 묶는다(라벨 값이 늘지 않게) */
    static String typeLabel(String type) {
        return KNOWN_TYPES.contains(type) ? type : "unknown";
    }

    /** 형식 오류(04 5장) — 지우기 전에 센다 */
    private void countMalformed() {
        meterRegistry.counter("booking_message_consumed_total", "type", "unknown", "result", "error").increment();
    }

    private void count(String result) {
        meterRegistry.counter("booking_checkout_total", "result", result).increment();
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new MalformedReplyException("결과 본문이 JSON이 아니다", e);
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

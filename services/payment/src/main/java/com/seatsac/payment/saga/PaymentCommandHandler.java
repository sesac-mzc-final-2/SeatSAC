package com.seatsac.payment.saga;

import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.trace.TraceIds;
import com.seatsac.common.core.trace.TraceKeys;
import com.seatsac.payment.common.PaymentErrorCode;
import com.seatsac.payment.dto.request.PaymentCreateRequest;
import com.seatsac.payment.dto.response.PaymentResponse;
import com.seatsac.payment.entity.Payment;
import com.seatsac.payment.enums.PaymentFailureCode;
import com.seatsac.payment.enums.PaymentMethod;
import com.seatsac.payment.enums.PaymentStatus;
import com.seatsac.payment.portone.PortOneUnavailableException;
import com.seatsac.payment.service.PaymentService;
import com.seatsac.payment.service.PaymentStateService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * saga 명령 처리(6.2.3, 설계 02) — payment는 참여자다. 명령 4종을 받아 <b>기존 결제 메서드</b>를 부르고 결과를 outbox에 넣는다.
 *
 * <p>순서(D27): ① inbox 조회(있으면 끝) → ② 처리는 트랜잭션 밖(모의 판정의 지연 카드 sleep·포트원 조회가 커넥션을 쥐지 않게,
 * payment 풀은 5개) → ③ {@link SagaResultWriter} 한 트랜잭션(inbox + 표시 + 결과) → 부르는 쪽이 메시지 삭제.
 * ②의 기존 메서드는 멱등이라(같은 결제 ID면 저장된 상태, 조건부 UPDATE) 재배달 재처리가 안전하다.
 *
 * <p>업무 결과(거절·결과 불명·포트원 무응답)는 예외가 아니라 결과 메시지다 — 재배달로 포트원을 연타하지 않는다.
 * 형식이 틀린 명령은 {@link MalformedCommandException}(리스너가 지운다, 04 5장), DB 오류 등 처리 중 장애만 예외로 올라가
 * 재배달 3회 뒤 DLQ로 간다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCommandHandler {

    /** 지표 {@code type} 라벨에 쓰는 값 — 모르는 type은 {@code unknown}으로 묶는다(라벨 값이 늘지 않게) */
    private static final Set<String> KNOWN_TYPES = Set.of(SagaMessages.PREPARATION_REQUESTED,
        SagaMessages.CHARGE_REQUESTED, SagaMessages.VERIFICATION_REQUESTED, SagaMessages.RELEASE_REQUESTED);

    /** 같은 예매로 다시 결제할 수 있는 실패 — 포트원 FAILED(돈이 안 빠짐). MOCK 거절·금액 불일치·만료는 아니다(06) */
    private static final Set<PaymentFailureCode> RETRYABLE = Set.of(
        PaymentFailureCode.USER_CANCELED, PaymentFailureCode.PG_DECLINED);

    private final SagaMessages messages;
    private final SagaResultWriter writer;
    private final ProcessedMessageRepository inbox;
    private final SagaPaymentRepository sagaPayments;
    private final PaymentService paymentService;
    private final PaymentStateService paymentState;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    /** 처리 결과(지표 라벨) */
    public enum Result { OK, DUPLICATE }

    /**
     * @throws MalformedCommandException 형식이 틀린 명령(재배달해도 같다 → 리스너가 지운다)
     */
    public Result handle(String body) {
        SagaMessages.Command command;
        try {
            command = messages.parse(body);
        } catch (MalformedCommandException e) {
            meterRegistry.counter("payment_command_total", "type", "unknown", "result", "error").increment();
            throw e;
        }
        MDC.put(TraceKeys.MDC_TRACE_ID, TraceIds.isValid(command.traceId()) ? command.traceId() : TraceIds.newId());
        MDC.put(TraceKeys.MDC_PAYMENT_ID, String.valueOf(command.paymentId()));
        Timer.Sample sample = Timer.start(meterRegistry);
        String result = "error";
        try {
            requireIds(command);
            if (inbox.existsById(command.eventId())) {
                result = "duplicate";
                log.info("이미 처리한 명령 — 결과를 다시 내지 않는다 eventId={} type={}", command.eventId(), command.type());
                return Result.DUPLICATE;
            }
            dispatch(command);
            result = "ok";
            return Result.OK;
        } catch (DataIntegrityViolationException e) {
            // 유일 키 충돌이 모두 「같은 명령의 동시 처리」는 아니다 — 해제와 준비가 동시에 와서 saga_payment 행을 같이
            // 만들다 충돌할 수도 있다(그때 해제는 처리되지 않았다). inbox에 이 명령이 있을 때만 중복으로 보고 지우고,
            // 없으면 예외를 올려 재배달한다(다음 배달은 이미 생긴 행을 보고 정상 처리한다)
            if (inbox.existsById(command.eventId())) {
                result = "duplicate";
                log.info("같은 명령의 동시 처리 — 먼저 끝난 쪽 결과를 쓴다 eventId={} type={}", command.eventId(), command.type());
                return Result.DUPLICATE;
            }
            log.warn("명령 처리 중 유일 키 충돌 — 재배달한다 eventId={} type={}", command.eventId(), command.type());
            throw e;
        } finally {
            String type = typeLabel(command.type());
            meterRegistry.counter("payment_command_total", "type", type, "result", result).increment();
            sample.stop(meterRegistry.timer("payment_command_seconds", "type", type));
            MDC.remove(TraceKeys.MDC_PAYMENT_ID);
            MDC.remove(TraceKeys.MDC_TRACE_ID);
        }
    }

    private void dispatch(SagaMessages.Command command) {
        switch (command.type()) {
            case SagaMessages.PREPARATION_REQUESTED -> prepare(command);
            case SagaMessages.CHARGE_REQUESTED -> charge(command);
            case SagaMessages.VERIFICATION_REQUESTED -> verify(command);
            case SagaMessages.RELEASE_REQUESTED -> release(command);
            default -> throw new MalformedCommandException("모르는 명령 type=" + command.type());
        }
    }

    /** TOSSPAY 준비 — 동기 {@code POST /internal/payments} TOSSPAY와 같은 메서드. 돈은 움직이지 않는다 */
    private void prepare(SagaMessages.Command command) {
        Instant now = clock.instant();
        if (sagaPayments.isReleased(command.paymentId())) {
            writer.record(command, false, messages.preparationFailed(command, "BOOKING_EXPIRED", now), now);
            return;
        }
        PaymentResponse response;
        try {
            response = paymentService.pay(new PaymentCreateRequest(command.paymentId(), command.bookingId(),
                command.intField("amount"), PaymentMethod.TOSSPAY, command.text("orderName"), null));
        } catch (BusinessException e) {
            String reason = preparationFailureReason(e);
            log.warn("결제 준비 실패 bookingId={} reason={}", command.bookingId(), reason);
            writer.record(command, false, messages.preparationFailed(command, reason, now), now);
            return;
        }
        Instant done = clock.instant();
        SagaMessages.Reply reply = response.status() == PaymentStatus.PROCESSING && response.checkout() != null
            ? messages.prepared(command, response.checkout(), done)
            : replyFor(command, response, done);   // 재배달 사이 웹훅이 이미 닫았다
        writer.record(command, true, reply, done);
    }

    /**
     * CARD(모의) 청구 — 판정까지 끝낸다. 지연 카드는 이 스레드가 기다린다(트랜잭션 밖).
     * 결과는 설계 02대로 {@code approved}·{@code declined}뿐이다 — 청구는 준비 단계가 없어 booking이
     * {@code preparation-failed}를 받지 않는다(CHARGING에서 받으면 선점 만료까지 멈춘다). 청구를 못 했으면
     * 돈이 안 움직였으므로 {@code declined{retryable=false}}
     */
    private void charge(SagaMessages.Command command) {
        Instant now = clock.instant();
        if (sagaPayments.isReleased(command.paymentId())) {
            writer.record(command, false,
                messages.declined(command, PaymentFailureCode.BOOKING_EXPIRED.name(), false, now), now);
            return;
        }
        PaymentResponse response;
        try {
            response = paymentService.chargeMockLast4(command.paymentId(), command.bookingId(),
                command.intField("amount"), command.text("cardLast4"));
        } catch (BusinessException e) {
            String reason = preparationFailureReason(e);
            log.warn("모의 청구 실패 bookingId={} reason={}", command.bookingId(), reason);
            writer.record(command, false, messages.declined(command, reason, false, now), now);
            return;
        }
        Instant done = clock.instant();
        writer.record(command, true, replyFor(command, response, done), done);
    }

    /**
     * 결제 확인. PORTONE은 포트원 단건 조회(기존 verify), MOCK은 행 상태만 읽는다(verify가 NOT_VERIFIABLE).
     * 행이 아직 없으면(표준 큐라 준비·청구보다 먼저 올 수 있다) 예외가 아니라 {@code result-unknown{NOT_FOUND}}
     */
    private void verify(SagaMessages.Command command) {
        Optional<Payment> found = paymentState.find(command.paymentId());
        if (found.isEmpty()) {
            Instant now = clock.instant();
            writer.record(command, false, messages.resultUnknown(command, "NOT_FOUND", now), now);
            return;
        }
        Payment payment = found.get();
        Integer expected = command.intField("expectedAmount");
        if (expected != null && !expected.equals(payment.getAmount())) {
            // booking 계산과 결제 행이 다르다 — 포트원 대조는 결제 행 금액으로 한다(결제창 금액과 같은 값)
            log.warn("확인 명령의 금액이 결제 행과 다르다 bookingId={} expected={} actual={}",
                command.bookingId(), expected, payment.getAmount());
        }
        PaymentResponse response;
        try {
            response = payment.isPortOne() ? paymentService.verify(command.paymentId())
                : paymentService.get(command.paymentId());
        } catch (PortOneUnavailableException e) {
            Instant now = clock.instant();
            writer.record(command, false, messages.resultUnknown(command, "PORTONE_UNAVAILABLE", now), now);
            return;
        }
        Instant done = clock.instant();
        writer.record(command, false, replyFor(command, response, done), done);
    }

    /** 해제(D16) — booking이 선점 만료로 놓은 결제. 결과는 승인된 결제를 이번에 놓았을 때만 낸다 */
    private void release(SagaMessages.Command command) {
        Instant now = clock.instant();
        Optional<Payment> found = paymentState.find(command.paymentId());
        boolean portOne = found.map(Payment::isPortOne).orElse(false);
        boolean approved = found.map(Payment::isApproved).orElse(false);
        boolean released = writer.recordRelease(command, portOne, approved,
            releasedApproved -> releasedApproved
                ? messages.declined(command, PaymentFailureCode.BOOKING_EXPIRED.name(), false, now) : null,
            now);
        log.info("saga 결제 해제 bookingId={} status={} 승인을환불대상으로={}", command.bookingId(),
            found.map(Payment::getStatus).orElse(null), released);
    }

    /** 결제 행 상태 → 결과 메시지 */
    private SagaMessages.Reply replyFor(SagaMessages.Command command, PaymentResponse response, Instant now) {
        return switch (response.status()) {
            case APPROVED -> messages.approved(command, response, now);
            case FAILED -> {
                PaymentFailureCode code = response.failureCode();
                boolean retryable = response.method() == PaymentMethod.TOSSPAY && RETRYABLE.contains(code);
                yield messages.declined(command, code == null ? "PG_DECLINED" : code.name(), retryable, now);
            }
            // 포트원에서 이미 취소된 결제(콘솔 취소 등) — 돈은 돌아갔다. 확정에 쓰지 않는다
            case CANCELED -> messages.declined(command, "CANCELED", false, now);
            case PROCESSING -> messages.resultUnknown(command, "NOT_YET", now);
        };
    }

    /** 계약 {@code payment.preparation-failed.reason} — 열린 집합 */
    private static String preparationFailureReason(BusinessException e) {
        if (e.code() instanceof PaymentErrorCode code) {
            return switch (code) {
                case PORTONE_DISABLED -> "PORTONE_DISABLED";
                case INVALID_AMOUNT -> "INVALID_AMOUNT";
                case PAYMENT_ID_CONFLICT, PAYMENT_ALREADY_EXISTS -> "PAYMENT_CONFLICT";
                default -> code.name();
            };
        }
        return e.code().responseCode();
    }

    static String typeLabel(String type) {
        return KNOWN_TYPES.contains(type) ? type : "unknown";
    }

    private static void requireIds(SagaMessages.Command command) {
        if (command.checkoutId() == null || command.paymentId() == null || command.bookingId() == null) {
            throw new MalformedCommandException("명령에 checkoutId·paymentId·bookingId가 없다 type=" + command.type());
        }
    }
}

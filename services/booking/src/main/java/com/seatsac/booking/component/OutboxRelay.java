package com.seatsac.booking.component;

import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.entity.OutboxMessage;
import com.seatsac.booking.repository.OutboxMessageRepository;
import com.seatsac.common.core.trace.TraceIds;
import com.seatsac.common.core.trace.TraceKeys;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * 예매 완료 알림 릴레이 — 아웃박스에 쌓인 메시지를 SQS로 보낸다(ADR-30·51, 계약 확정 #12).
 *
 * <p>요청 스레드에서 보내지 않는 이유: 확정(BKG-10)의 응답 시간에 SQS 지연이 섞이면 안 되고,
 * 전송 실패가 이미 승인된 결제의 확정을 되돌리면 더 안 된다. 확정은 같은 트랜잭션에서
 * 아웃박스 행만 남기고, 전송은 여기서만 한다.
 *
 * <p>한 건의 흐름은 <b>점유 → 커밋 → 전송 → 결과 기록</b>이다. 행을 잠근 채로 네트워크를
 * 기다리지 않으므로 Hikari 연결 5개가 묶이지 않고, 파드가 전송 중에 죽어도 점유가 만료되면
 * 다음 주기가 이어받는다. 그 경우 <b>같은 메시지가 두 번 갈 수 있는데</b>, {@code eventId}가
 * 아웃박스 UUID로 같아서 소비자(Lambda)가 걸러낸다(계약 확정 #12).
 *
 * <p>미리보기 파드에서도 돈다(런타임 계약 2장) — 점유가 조건부 UPDATE라 겹쳐도 한 번만 간다.
 *
 * <p>주기 실행은 {@link OutboxRelayScheduler}가 맡는다. 이 클래스는 시각을 받아 한 바퀴 도는
 * 메서드만 두어 테스트가 주기를 기다리지 않고 바로 부를 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxMessageRepository outboxRepository;
    private final NotificationPublisher publisher;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    /**
     * 아직 보내지 못한 건수. 릴레이가 <b>꺼져 있을 때도</b> 쌓이는 것이 보여야 하므로
     * 릴레이 주기와 무관하게 수집 시점에 센다(지표 {@code booking_outbox_pending}).
     */
    @PostConstruct
    void registerPendingGauge() {
        Gauge.builder("booking_outbox_pending", this, OutboxRelay::unsentCount)
            .description("아직 보내지 못한 알림 메시지 수 (PENDING·SENDING)")
            .register(meterRegistry);
    }

    private double unsentCount() {
        try {
            return outboxRepository.countUnsent();
        } catch (RuntimeException e) {
            log.warn("아웃박스 건수 조회 실패 — 지표를 비운다");
            return Double.NaN;   // 0으로 두면 「밀린 게 없다」로 읽힌다
        }
    }

    /** 한 바퀴. 보낸 건수를 돌려준다(테스트·로그용) */
    public int relayOnce() {
        String traceId = TraceIds.newId();   // 요청 밖 작업도 추적 ID를 남긴다 (명세 12.2)
        MDC.put(TraceKeys.MDC_TRACE_ID, traceId);
        try {
            return relayBatch();
        } finally {
            MDC.remove(TraceKeys.MDC_TRACE_ID);
        }
    }

    private int relayBatch() {
        Instant now = clock.instant();
        List<OutboxMessage> due = outboxRepository.findDue(now,
            PageRequest.of(0, BookingPolicy.OUTBOX_RELAY_BATCH));
        if (due.isEmpty()) {
            return 0;
        }

        int sent = 0;
        for (OutboxMessage message : due) {
            if (relay(message, now)) {
                sent++;
            }
        }
        if (sent > 0 || due.size() > 1) {
            log.info("아웃박스 릴레이 집은건수={} 보냄={}", due.size(), sent);
        }
        return sent;
    }

    /** @return 보냈으면 true. 다른 파드가 먼저 집었거나 실패하면 false */
    private boolean relay(OutboxMessage message, Instant now) {
        Long outboxId = message.getOutboxId();
        Instant claimUntil = now.plus(BookingPolicy.OUTBOX_CLAIM_TIMEOUT);
        if (outboxRepository.claim(outboxId, claimUntil, now) == 0) {
            return false;   // 다른 파드가 집었다
        }

        int attempt = message.getAttemptCount() + 1;   // 점유가 올린 값
        try {
            publisher.publish(message.getEventType().messageType(), message.getTraceId(),
                message.getPayload());
        } catch (RuntimeException e) {
            recordFailure(message, attempt, e);
            return false;
        }

        outboxRepository.markSent(outboxId, clock.instant());
        count("sent");
        log.info("알림 발행 eventId={} type={} bookingId={} 시도={}", message.getMessageKey(),
            message.getEventType().messageType(), message.getAggregateId(), attempt);
        return true;
    }

    /**
     * 실패 기록. 한도({@link BookingPolicy#OUTBOX_MAX_ATTEMPTS}) 안이면 다음 시도 시각을 두고
     * 되돌리고, 넘기면 {@code FAILED}로 두고 경보용 ERROR 로그를 남긴다.
     */
    private void recordFailure(OutboxMessage message, int attempt, RuntimeException cause) {
        String reason = reasonOf(cause);
        Instant now = clock.instant();

        if (attempt >= BookingPolicy.OUTBOX_MAX_ATTEMPTS) {
            outboxRepository.markFailed(message.getOutboxId(), reason, now);
            count("failed");
            log.error("알림 발행 {}회 실패 — 더 보내지 않는다 eventId={} bookingId={} 원인={}",
                attempt, message.getMessageKey(), message.getAggregateId(), reason);
            return;
        }

        Instant next = now.plus(backoff(attempt));
        outboxRepository.markRetry(message.getOutboxId(), next, reason, now);
        count("retry");
        log.warn("알림 발행 실패 — {} 뒤 다시 보낸다 eventId={} 시도={} 원인={}",
            backoff(attempt), message.getMessageKey(), attempt, reason);
    }

    /**
     * 1·2·4…최대 60초(ERD 3.3). 한 번 실패한 뒤 바로 다시 부르면 같은 장애에 연결만 쓰고,
     * 계속 늘리면 큐가 돌아온 뒤에도 오래 안 보낸다.
     */
    static Duration backoff(int attempt) {
        long seconds = 1L << Math.min(attempt - 1, 10);   // 1,2,4,…(시프트 넘침 방지)
        return Duration.ofSeconds(Math.min(seconds, BookingPolicy.OUTBOX_MAX_BACKOFF.toSeconds()));
    }

    /** 로그·DB에 남길 실패 이유. 길이는 {@code last_error VARCHAR(500)}에 맞춘다 */
    private static String reasonOf(RuntimeException cause) {
        String message = cause.getMessage() == null ? "" : cause.getMessage();
        String text = cause.getClass().getSimpleName() + (message.isBlank() ? "" : ": " + message);
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    private void count(String result) {
        meterRegistry.counter("booking_notification_publish_total", "result", result).increment();
    }
}

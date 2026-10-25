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
        int errors = 0;
        for (OutboxMessage message : due) {
            // 건별로 감싼다 — 한 건의 DB 오류가 배치를 끊으면 뒤에 있는 메시지는 이 주기에
            // 아예 처리되지 않고, 앞 건이 계속 실패하면 같은 자리에서 영구히 막힌다
            // (만료 스캔 HoldExpirySweeper와 같은 이유)
            try {
                if (relay(message)) {
                    sent++;
                }
            } catch (RuntimeException e) {
                errors++;
                log.error("아웃박스 한 건 처리 실패 — 다음 건으로 넘어간다 eventId={} bookingId={}",
                    message.getMessageKey(), message.getAggregateId(), e);
            }
        }
        if (sent > 0 || errors > 0 || due.size() > 1) {
            log.info("아웃박스 릴레이 집은건수={} 보냄={} 오류={}", due.size(), sent, errors);
        }
        return sent;
    }

    /**
     * 한 건. 점유 시각은 <b>건마다 다시 구한다</b> — 배치 시작 시각으로 계산하면 뒤쪽 건의
     * 실제 점유 시간이 30초보다 짧아져 전송 중에 만료되고, 다른 파드가 이어받아 중복이 늘어난다.
     *
     * @return 보냈으면 true. 다른 파드가 먼저 집었거나 실패하면 false
     */
    private boolean relay(OutboxMessage message) {
        Long outboxId = message.getOutboxId();
        Instant claimedAt = clock.instant();
        Instant claimUntil = claimedAt.plus(BookingPolicy.OUTBOX_CLAIM_TIMEOUT);
        // 조회 시점의 attemptCount를 기대값으로 걸어(compare-and-set) 점유한다 →
        // 성공하면 DB 값이 반드시 조회값+1이므로 아래 attempt 토큰이 실제 값과 어긋나지 않는다
        if (outboxRepository.claim(outboxId, claimUntil, claimedAt,
            BookingPolicy.OUTBOX_MAX_ATTEMPTS, message.getAttemptCount()) == 0) {
            failIfExhausted(message, claimedAt);
            return false;   // 다른 파드가 집었거나 한도에 닿았다
        }

        int attempt = message.getAttemptCount() + 1;   // 점유가 올린 값(기대값+1) = 이번 시도의 토큰
        try {
            publisher.publish(message.getEventType().messageType(), message.getTraceId(),
                message.getPayload());
        } catch (RuntimeException e) {
            recordFailure(message, attempt, e);
            return false;
        }

        if (outboxRepository.markSent(outboxId, attempt, clock.instant()) == 0) {
            // 내 점유가 만료되고 다른 파드가 이어받았다. 그쪽 상태를 덮어쓰지 않는다
            log.warn("보냈지만 점유가 이미 넘어갔다 — 중복 발행 가능 eventId={}", message.getMessageKey());
        }
        count("sent");
        log.info("알림 발행 eventId={} type={} bookingId={} 시도={}", message.getMessageKey(),
            message.getEventType().messageType(), message.getAggregateId(), attempt);
        return true;
    }

    /**
     * 점유만 하고 <b>전송 전에 죽은</b> 파드가 남긴 행을 닫는다.
     *
     * <p>그 경우 {@link #recordFailure}가 돌지 못해 상태가 {@code SENDING}에 머물고
     * {@code attempt_count}만 늘어난다. 점유 조건에 한도를 걸어 두면 한도에 닿은 뒤로는
     * 아무도 집지 못하므로, 여기서 {@code FAILED}로 닫고 경보를 남긴다. 닫지 않으면
     * 그 행은 영원히 {@code SENDING}으로 남아 {@code booking_outbox_pending}에 쌓인다.
     */
    private void failIfExhausted(OutboxMessage message, Instant now) {
        if (message.getAttemptCount() < BookingPolicy.OUTBOX_MAX_ATTEMPTS) {
            return;   // 흔한 경우 — 다른 파드가 먼저 집었다. 쿼리를 더 보내지 않는다
        }
        int closed = outboxRepository.failExhausted(message.getOutboxId(),
            "점유 만료 — 재시도 한도 도달(전송 전에 파드가 멈췄을 수 있다)", now,
            BookingPolicy.OUTBOX_MAX_ATTEMPTS);
        if (closed > 0) {
            count("failed");
            log.error("알림 발행 점유가 한도까지 만료됐다 — 더 보내지 않는다 eventId={} bookingId={} 시도={}",
                message.getMessageKey(), message.getAggregateId(), message.getAttemptCount());
        }
    }

    /**
     * 실패 기록. 한도({@link BookingPolicy#OUTBOX_MAX_ATTEMPTS}) 안이면 다음 시도 시각을 두고
     * 되돌리고, 넘기면 {@code FAILED}로 두고 경보용 ERROR 로그를 남긴다.
     */
    private void recordFailure(OutboxMessage message, int attempt, RuntimeException cause) {
        String reason = reasonOf(cause);
        Instant now = clock.instant();

        if (attempt >= BookingPolicy.OUTBOX_MAX_ATTEMPTS) {
            outboxRepository.markFailed(message.getOutboxId(), attempt, reason, now);
            count("failed");
            log.error("알림 발행 {}회 실패 — 더 보내지 않는다 eventId={} bookingId={} 원인={}",
                attempt, message.getMessageKey(), message.getAggregateId(), reason);
            return;
        }

        Instant next = now.plus(backoff(attempt));
        outboxRepository.markRetry(message.getOutboxId(), attempt, next, reason, now);
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

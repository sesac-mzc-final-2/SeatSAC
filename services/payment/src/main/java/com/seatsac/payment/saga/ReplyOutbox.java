package com.seatsac.payment.saga;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 결과 outbox — booking {@code OutboxMessage}와 같은 표 구조·같은 재시도 규칙(설계 02 4장). 보내는 곳은
 * {@code booking-replies} 하나라 목적지 열은 두지 않는다. {@code payload}는 envelope 전체다.
 *
 * <p>{@code aggregateKey} = 결과를 낳은 명령의 eventId. 같은 명령이 다시 와도(inbox를 지나친 동시 재배달)
 * 유일 키 (event_type, aggregate_id, aggregate_key)가 결과 두 번을 막는다.
 */
@Entity
@Table(name = "outbox_message")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReplyOutbox {

    /** 아웃박스 전송 상태 — SENDING은 점유 중(next_attempt_at = 점유 만료) */
    public enum Status { PENDING, SENDING, SENT, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "outbox_id")
    private Long outboxId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "message_key", length = 36, nullable = false, unique = true, updatable = false)
    private String messageKey;

    @Column(name = "event_type", length = 60, nullable = false, updatable = false)
    private String eventType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private Long aggregateId;

    @Column(name = "aggregate_key", length = 80, nullable = false, updatable = false)
    private String aggregateKey;

    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "json")
    private String payload;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "trace_id", length = 32, updatable = false)
    private String traceId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "correlation_id", length = 36, updatable = false)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private Status status;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    static ReplyOutbox pending(SagaMessages.Reply reply, Long bookingId, String aggregateKey, Instant now) {
        ReplyOutbox row = new ReplyOutbox();
        row.messageKey = Objects.requireNonNull(reply.eventId(), "eventId");
        row.eventType = Objects.requireNonNull(reply.type(), "type");
        row.aggregateId = Objects.requireNonNull(bookingId, "bookingId");
        row.aggregateKey = Objects.requireNonNull(aggregateKey, "aggregateKey");
        row.payload = Objects.requireNonNull(reply.json(), "payload");
        row.traceId = reply.traceId();
        row.correlationId = reply.correlationId();
        row.status = Status.PENDING;
        row.attemptCount = 0;
        row.nextAttemptAt = now;
        row.createdAt = now;
        row.updatedAt = now;
        return row;
    }
}

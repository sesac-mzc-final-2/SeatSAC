package com.seatsac.catalog.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 변경 이벤트 outbox — booking {@code OutboxMessage}·coupon {@code ReplyOutbox}와 같은 표 구조·같은 재시도 규칙(설계 02 4장).
 * 보내는 곳은 SNS {@code catalog-events} 하나라 목적지 열은 두지 않는다. {@code payload}는 envelope 전체다.
 *
 * <p>행은 {@link CatalogChangeWriter}가 JDBC로 넣는다(장부 갱신과 같은 트랜잭션). 이 엔티티는 릴레이가 읽고 상태를 바꿀 때만 쓴다.
 * {@code aggregateKey} = 원본 version — 유일 키 (event_type, aggregate_id, aggregate_key)가 같은 변경 두 번을 막는다.
 */
@Entity
@Table(name = "outbox_message")
public class CatalogOutbox {

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

    protected CatalogOutbox() {
    }

    public Long getOutboxId() {
        return outboxId;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getEventType() {
        return eventType;
    }

    public Long getAggregateId() {
        return aggregateId;
    }

    public String getAggregateKey() {
        return aggregateKey;
    }

    public String getPayload() {
        return payload;
    }

    public String getTraceId() {
        return traceId;
    }

    public Status getStatus() {
        return status;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}

package com.seatsac.booking.entity;

import com.seatsac.booking.enums.OutboxEventType;
import com.seatsac.booking.enums.OutboxStatus;
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
 * 아웃박스 — ERD 3.3, 알림 결정 문서 10장. 확정 UPDATE가 1행일 때만 같은 트랜잭션에서 INSERT한다.
 * {@code payload}는 트랜잭션 전에 만들어 넘긴다. 전송은 릴레이 스케줄러만 한다(요청 스레드 금지).
 */
@Entity
@Table(name = "outbox_message")
public class OutboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "outbox_id")
    private Long outboxId;

    /** 메시지 eventId(UUID). 재전송해도 같은 값 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "message_key", length = 36, nullable = false, unique = true, updatable = false)
    private String messageKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", length = 40, nullable = false, updatable = false)
    private OutboxEventType eventType;

    /** 예매 ID (FK 없음 — 이벤트 종류마다 가리키는 대상이 다를 수 있다) */
    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private Long aggregateId;

    /** 메시지 본문 JSON 문자열. 트랜잭션 전에 만든 값 그대로 저장한다 */
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "json")
    private String payload;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "trace_id", length = 32, updatable = false)
    private String traceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private OutboxStatus status;

    /** 잡은 횟수. 펜싱과 재시도 한도에 쓴다 */
    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    /** PENDING이면 다음 시도 시각, SENDING이면 점유 만료 시각 (UTC) */
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

    protected OutboxMessage() {
    }

    /** 바로 보낼 수 있는 PENDING 메시지. messageKey는 호출하는 쪽에서 발급한 UUID */
    public static OutboxMessage pending(String messageKey, OutboxEventType eventType, Long aggregateId,
                                        String payload, String traceId, Instant now) {
        OutboxMessage message = new OutboxMessage();
        message.messageKey = messageKey;
        message.eventType = eventType;
        message.aggregateId = aggregateId;
        message.payload = payload;
        message.traceId = traceId;
        message.status = OutboxStatus.PENDING;
        message.attemptCount = 0;
        message.nextAttemptAt = now;
        message.createdAt = now;
        message.updatedAt = now;
        return message;
    }

    public Long getOutboxId() {
        return outboxId;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public OutboxEventType getEventType() {
        return eventType;
    }

    public Long getAggregateId() {
        return aggregateId;
    }

    public String getPayload() {
        return payload;
    }

    public String getTraceId() {
        return traceId;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

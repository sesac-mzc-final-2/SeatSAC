package com.seatsac.coupon.saga;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 명령 inbox — 같은 eventId는 한 번만 처리한다(ADR-117). 결과 outbox와 같은 트랜잭션에 INSERT한다(D27) */
@Entity
@Table(name = "processed_message")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedMessage {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "message_id", length = 36, nullable = false, updatable = false)
    private String messageId;

    @Column(name = "message_type", length = 60, nullable = false, updatable = false)
    private String messageType;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    public static ProcessedMessage of(String messageId, String messageType, Instant now) {
        ProcessedMessage row = new ProcessedMessage();
        row.messageId = messageId;
        row.messageType = messageType;
        row.receivedAt = now;
        return row;
    }
}

package com.seatsac.coupon.saga;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * 명령 inbox — 같은 eventId는 한 번만 처리한다(ADR-117). 결과 outbox와 같은 트랜잭션에 INSERT한다(D27).
 * ID를 미리 채우므로 {@link Persistable}로 「새 행」이라고 알린다 — 아니면 {@code save}가 {@code merge}(SELECT 뒤 생략)로
 * 가서, 다른 파드가 먼저 커밋한 같은 eventId를 PK 충돌 없이 지나친다
 */
@Entity
@Table(name = "processed_message")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedMessage implements Persistable<String> {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "message_id", length = 36, nullable = false, updatable = false)
    private String messageId;

    @Column(name = "message_type", length = 60, nullable = false, updatable = false)
    private String messageType;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Transient
    private boolean fresh = true;

    @Override
    public String getId() {
        return messageId;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostPersist
    @PostLoad
    void markStored() {
        fresh = false;
    }

    public static ProcessedMessage of(String messageId, String messageType, Instant now) {
        ProcessedMessage row = new ProcessedMessage();
        row.messageId = messageId;
        row.messageType = messageType;
        row.receivedAt = now;
        return row;
    }
}

package com.seatsac.booking.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * {@code outbox_message.event_type} ↔ {@link OutboxEventType}. <b>모르는 값은 예외 대신 null</b>로 읽는다.
 *
 * <p>이유(11/24 리뷰 4번): 릴레이는 안 보낸 행을 종류와 상관없이 한 번에 읽는다. enum으로 그대로 읽으면, 이 버전보다 새 버전이
 * 넣은 종류(예: 다음 배포의 coupon 명령)를 만나는 순간 조회 전체가 실패해 <b>알림까지 멈춘다</b> — 새 버전에서 이 버전으로
 * 롤백한 경우다. null로 읽고 릴레이가 그 행만 건너뛰면 아는 행은 계속 나간다. 쓰는 쪽은 enum 이름 그대로다.
 */
@Converter
public class OutboxEventTypeConverter implements AttributeConverter<OutboxEventType, String> {

    @Override
    public String convertToDatabaseColumn(OutboxEventType type) {
        return type == null ? null : type.name();
    }

    @Override
    public OutboxEventType convertToEntityAttribute(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OutboxEventType.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            return null;   // 더 새 버전이 넣은 종류 — 릴레이가 건너뛴다
        }
    }
}

package com.seatsac.booking.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** {@code outbox_message.destination} — 모르는 목적지(더 새 버전)는 null로 읽고 릴레이가 건너뛴다({@link OutboxEventTypeConverter}와 같은 이유) */
@Converter
public class OutboxDestinationConverter implements AttributeConverter<OutboxDestination, String> {

    @Override
    public String convertToDatabaseColumn(OutboxDestination destination) {
        return destination == null ? null : destination.name();
    }

    @Override
    public OutboxDestination convertToEntityAttribute(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OutboxDestination.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}

package com.seatsac.booking.dto.event;

import com.seatsac.booking.entity.Booking;
import com.seatsac.common.core.time.Times;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * 6.2.4 예매 취소 이벤트 — `contracts/events/booking.canceled.v1.json`(SNS booking-events → user 예매 내역 복제본).
 * 확정 예매 취소(BKG-07, {@code USER_CANCELED}, D29)와 체크아웃 saga의 보상·만료({@code PAYMENT_FAILED}·{@code EXPIRED}).
 * 동기 경로의 결제 실패·만료는 보내지 않는다(user가 무시한다). 개인정보 금지.
 */
public record BookingCanceledEvent(
    String eventId,
    String type,
    int version,
    OffsetDateTime occurredAt,
    String producer,
    String traceId,
    String correlationId,
    Payload payload
) {

    public static final String TYPE = "booking.canceled";

    public enum Reason { USER_CANCELED, EXPIRED, PAYMENT_FAILED }

    public record Payload(Long bookingId, String userId, Long screeningId, Reason reason, OffsetDateTime canceledAt) {
    }

    public static BookingCanceledEvent of(String messageKey, Booking booking, Reason reason, String traceId,
                                          String correlationId, Instant now) {
        return new BookingCanceledEvent(messageKey, TYPE, 1, Times.toApi(now), "booking", traceId, correlationId,
            new Payload(booking.getBookingId(), booking.getUserId(), booking.getScreeningId(), reason,
                Times.toApi(now)));
    }
}

package com.seatsac.booking.dto.event;

import com.seatsac.booking.component.CatalogClient;
import com.seatsac.booking.entity.Booking;
import com.seatsac.common.core.time.Times;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

/**
 * 예매 완료 알림 — `contracts/events/booking.confirmed.v1.json`. 아웃박스 행의 payload로 저장하고
 * 릴레이가 SQS로 보낸다(릴레이는 AWS SDK 승인 후).
 *
 * <p>Catalog 조회가 실패하면 영화·극장·상영관·시각을 <b>null로 두고 확정은 그대로 진행</b>한다 —
 * 결제가 이미 승인됐으니 알림 본문 때문에 확정을 멈출 수 없다(계약이 그 네 필드를 null 허용으로
 * 열어 둔 이유).
 */
public record BookingConfirmedEvent(
    String eventId,
    String type,
    int version,
    OffsetDateTime occurredAt,
    String producer,
    String traceId,
    Payload payload
) {

    public static final String TYPE = "booking.confirmed";
    public static final int VERSION = 1;
    private static final String PRODUCER = "booking";

    /** 알림 본문 — 계약의 필수 11개를 모두 담는다(값이 없으면 null) */
    @Builder
    public record Payload(
        Long bookingId,
        String userId,
        Long screeningId,
        String movieTitle,
        String theaterName,
        String auditoriumName,
        OffsetDateTime startsAt,
        List<String> seatLabels,
        Integer seatCount,
        Integer amount,
        OffsetDateTime confirmedAt
    ) {
        public Payload {
            seatLabels = seatLabels == null ? List.of() : List.copyOf(seatLabels);
        }
    }

    /**
     * @param messageKey 아웃박스 행의 {@code message_key} — 알림 {@code eventId}와 같은 값이다.
     *                   재전송해도 같은 값이라 소비자가 중복을 걸러낼 수 있다
     * @param screening  Catalog 조회 결과. {@code null}이면 이름·시각을 비워 둔다
     */
    public static BookingConfirmedEvent of(String messageKey, Booking booking,
                                           CatalogClient.Screening screening, String traceId,
                                           Instant occurredAt) {
        return new BookingConfirmedEvent(messageKey, TYPE, VERSION, Times.toApi(occurredAt), PRODUCER,
            traceId, Payload.builder()
            .bookingId(booking.getBookingId())
            .userId(booking.getUserId())
            .screeningId(booking.getScreeningId())
            .movieTitle(screening == null ? null : screening.movieTitle())
            .theaterName(screening == null ? null : screening.theaterName())
            .auditoriumName(screening == null ? null : screening.auditoriumName())
            .startsAt(screening == null ? null : Times.toApi(screening.startsAt()))
            .seatLabels(booking.getSeatLabels())
            .seatCount(booking.getSeatCount())
            .amount(booking.getAmount())
            .confirmedAt(Times.toApi(booking.getConfirmedAt() == null ? occurredAt
                : booking.getConfirmedAt()))
            .build());
    }

    public static String newMessageKey() {
        return UUID.randomUUID().toString();
    }
}

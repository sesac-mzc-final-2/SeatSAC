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
    /** 6.2.4 — 체크아웃 saga로 확정되면 checkoutId, 동기 경로는 null(계약 null 허용) */
    String correlationId,
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
        /** 결제 금액 — 쿠폰 할인이 있으면 할인 뒤 최종 금액(= 포트원 결제 금액, 6.2.4) */
        Integer amount,
        /** 6.2.4 쿠폰 할인액(원). 할인이 없으면 0 */
        Integer discountAmount,
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
        return of(messageKey, booking, screening, traceId, occurredAt, Pricing.of(booking));
    }

    /**
     * 결제 금액·할인·saga ID. 동기 경로는 {@link #of(Booking)}(예매 원가, 할인 0, correlationId 없음), 체크아웃 saga는
     * {@code checkout_saga}의 최종 금액·할인액·checkoutId
     */
    public record Pricing(int amount, int discountAmount, String correlationId) {

        public static Pricing of(Booking booking) {
            return new Pricing(booking.getAmount(), 0, null);
        }
    }

    public static BookingConfirmedEvent of(String messageKey, Booking booking,
                                           CatalogClient.Screening screening, String traceId,
                                           Instant occurredAt, Pricing pricing) {
        return new BookingConfirmedEvent(messageKey, TYPE, VERSION, Times.toApi(occurredAt), PRODUCER,
            traceId, pricing.correlationId(), Payload.builder()
            .bookingId(booking.getBookingId())
            .userId(booking.getUserId())
            .screeningId(booking.getScreeningId())
            .movieTitle(screening == null ? null : screening.movieTitle())
            .theaterName(screening == null ? null : screening.theaterName())
            .auditoriumName(screening == null ? null : screening.auditoriumName())
            .startsAt(screening == null ? null : Times.toApi(screening.startsAt()))
            .seatLabels(booking.getSeatLabels())
            .seatCount(booking.getSeatCount())
            .amount(pricing.amount())
            .discountAmount(pricing.discountAmount())
            .confirmedAt(Times.toApi(booking.getConfirmedAt() == null ? occurredAt
                : booking.getConfirmedAt()))
            .build());
    }

    public static String newMessageKey() {
        return UUID.randomUUID().toString();
    }
}

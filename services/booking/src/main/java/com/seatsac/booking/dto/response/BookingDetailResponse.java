package com.seatsac.booking.dto.response;

import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.common.core.time.Times;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Builder;

/**
 * 예매 단건 조회 200 응답 — 명세 5장. 결제 화면과 503 뒤 결과 재확인에 쓴다.
 * {@code seatIds}는 좌석을 점유 중일 때만(PENDING·CONFIRMED) 담고 끝난 예매는 빈 배열이다.
 */
@Builder
public record BookingDetailResponse(
    Long bookingId,
    Long screeningId,
    BookingStatus status,
    List<Long> seatIds,
    List<String> seatLabels,
    Integer seatCount,
    Integer amount,
    OffsetDateTime expiresAt,
    OffsetDateTime createdAt,
    OffsetDateTime confirmedAt,
    OffsetDateTime canceledAt,
    PaymentView payment,
    /** 6.2.1 — 체크아웃 saga 경로면 가장 최근 체크아웃(없으면 null). 상세는 GET /bookings/{id}/checkout */
    CheckoutRef checkout
) {
    public record CheckoutRef(String checkoutId, String state) {
    }

    public static BookingDetailResponse from(Booking booking, List<Long> seatIds, PaymentView payment) {
        return from(booking, seatIds, payment, null);
    }

    public static BookingDetailResponse from(Booking booking, List<Long> seatIds, PaymentView payment,
                                             CheckoutRef checkout) {
        return BookingDetailResponse.builder()
            .bookingId(booking.getBookingId())
            .screeningId(booking.getScreeningId())
            .status(booking.getStatus())
            .seatIds(seatIds == null ? List.of() : List.copyOf(seatIds))
            .seatLabels(booking.getSeatLabels())
            .seatCount(booking.getSeatCount())
            .amount(booking.getAmount())
            .expiresAt(Times.toApi(booking.getExpiresAt()))
            .createdAt(Times.toApi(booking.getCreatedAt()))
            .confirmedAt(Times.toApi(booking.getConfirmedAt()))
            .canceledAt(Times.toApi(booking.getCanceledAt()))
            .payment(payment)
            .checkout(checkout)
            .build();
    }
}

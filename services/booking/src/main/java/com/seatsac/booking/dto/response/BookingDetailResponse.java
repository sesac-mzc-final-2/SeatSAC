package com.seatsac.booking.dto.response;

import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.common.core.time.Times;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * 예매 단건 조회 200 응답 — 명세 5장. 결제 화면과 503 뒤 결과 재확인에 쓴다.
 * {@code seatIds}는 좌석을 점유 중일 때만(PENDING·CONFIRMED) 담고 끝난 예매는 빈 배열이다.
 */
@Getter
@Builder
public class BookingDetailResponse {

    private final Long bookingId;
    private final Long screeningId;
    private final BookingStatus status;
    private final List<Long> seatIds;
    private final List<String> seatLabels;
    private final Integer seatCount;
    private final Integer amount;
    private final OffsetDateTime expiresAt;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime confirmedAt;
    private final OffsetDateTime canceledAt;
    private final PaymentView payment;

    public static BookingDetailResponse from(Booking booking, List<Long> seatIds, PaymentView payment) {
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
            .build();
    }
}

package com.seatsac.booking.dto.response;

import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.common.core.time.Times;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Builder;

/**
 * BKG-14 사용자 예매 내역의 원소 — 명세 5장. User(USR-04)가 내부 API로 받아
 * 영화 제목·상영 시각을 Catalog에서 받아 합친다. 응답 전체는 공통 {@code PageResponse}로 감싼다.
 *
 * <p>{@code booking} 테이블만 읽는다 — 좌석 번호는 {@code seat_labels}에 있고 {@code booking_seat}를
 * 조회하지 않는다(끝난 예매는 행이 없다).
 */
@Builder
public record BookingSummaryResponse(
    Long bookingId,
    BookingStatus status,
    Long screeningId,
    List<String> seatLabels,
    Integer seatCount,
    Integer amount,
    OffsetDateTime createdAt,
    OffsetDateTime confirmedAt,
    OffsetDateTime canceledAt
) {
    public static BookingSummaryResponse from(Booking booking) {
        return BookingSummaryResponse.builder()
            .bookingId(booking.getBookingId())
            .status(booking.getStatus())
            .screeningId(booking.getScreeningId())
            .seatLabels(booking.getSeatLabels())
            .seatCount(booking.getSeatCount())
            .amount(booking.getAmount())
            .createdAt(Times.toApi(booking.getCreatedAt()))
            .confirmedAt(Times.toApi(booking.getConfirmedAt()))
            .canceledAt(Times.toApi(booking.getCanceledAt()))
            .build();
    }
}

package com.seatsac.booking.dto.response;

import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.common.core.time.Times;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * BKG-10 결제 요청 200 응답 — 명세 5장. 결제 거절도 정상 처리 결과라 200이고
 * {@code status}({@code CONFIRMED} / {@code PAYMENT_FAILED})로 구분한다.
 */
@Getter
@Builder
public class BookingPaymentResponse {

    private final Long bookingId;
    private final BookingStatus status;
    private final OffsetDateTime confirmedAt;
    private final List<String> seatLabels;
    private final PaymentView payment;

    public static BookingPaymentResponse from(Booking booking, PaymentView payment) {
        return BookingPaymentResponse.builder()
            .bookingId(booking.getBookingId())
            .status(booking.getStatus())
            .confirmedAt(Times.toApi(booking.getConfirmedAt()))
            .seatLabels(booking.getSeatLabels())
            .payment(payment)
            .build();
    }
}

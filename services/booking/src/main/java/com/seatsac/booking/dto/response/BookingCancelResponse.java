package com.seatsac.booking.dto.response;

import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.common.core.time.Times;
import java.time.OffsetDateTime;
import lombok.Builder;
import lombok.Getter;

/** BKG-07 예매 취소 200 응답 — 명세 5장. 환불된 결제 정보를 함께 돌려준다. */
@Getter
@Builder
public class BookingCancelResponse {

    private final Long bookingId;
    private final BookingStatus status;
    private final OffsetDateTime canceledAt;
    private final PaymentView payment;

    public static BookingCancelResponse from(Booking booking, PaymentView payment) {
        return BookingCancelResponse.builder()
            .bookingId(booking.getBookingId())
            .status(booking.getStatus())
            .canceledAt(Times.toApi(booking.getCanceledAt()))
            .payment(payment)
            .build();
    }
}

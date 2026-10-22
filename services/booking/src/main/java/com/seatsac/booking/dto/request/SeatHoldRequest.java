package com.seatsac.booking.dto.request;

import com.seatsac.booking.common.BookingPolicy;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * BKG-02 좌석 선점 요청 — 명세 5장. {@code POST /seats/hold}
 *
 * <p>좌석 ID 중복·다른 상영관·비활성 좌석은 애너테이션으로 판정할 수 없어
 * 흐름 서비스가 Catalog 배치 캐시와 맞춰 보고 400 {@code INVALID_SEATS}로 거절한다.
 */
public record SeatHoldRequest(

    @NotNull @Positive Long screeningId,

    /** 1~4개. 상한은 {@link BookingPolicy#MAX_SEATS_PER_HOLD} 하나만 본다 */
    @NotNull
    @Size(min = BookingPolicy.MIN_SEATS_PER_HOLD, max = BookingPolicy.MAX_SEATS_PER_HOLD)
    List<@NotNull @Positive Long> seatIds
) {
    public SeatHoldRequest {
        seatIds = seatIds == null ? null : List.copyOf(seatIds);
    }
}

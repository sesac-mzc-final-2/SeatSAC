package com.seatsac.booking.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;

/**
 * BKG-02 좌석 선점 요청 — 명세 5장. {@code POST /seats/hold}
 *
 * <p>좌석 ID 중복·다른 상영관·비활성 좌석은 애너테이션으로 판정할 수 없어
 * 흐름 서비스가 Catalog 배치 캐시와 맞춰 보고 400 {@code INVALID_SEATS}로 거절한다.
 */
@Getter
@Builder
@Jacksonized
@ToString
public class SeatHoldRequest {

    @NotNull
    @Positive
    private final Long screeningId;

    /** 1~4개 (BookingPolicy.MAX_SEATS_PER_HOLD) */
    @NotEmpty
    @Size(max = 4)
    private final List<@NotNull @Positive Long> seatIds;

    public List<Long> seatIds() {
        return seatIds == null ? List.of() : List.copyOf(seatIds);
    }
}

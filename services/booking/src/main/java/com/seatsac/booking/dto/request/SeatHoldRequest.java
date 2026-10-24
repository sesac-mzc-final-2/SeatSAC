package com.seatsac.booking.dto.request;

import com.seatsac.booking.common.BookingPolicy;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Collections;
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
        // List.copyOf는 null 원소에서 NPE를 던져 필드 검증(seatIds[i] @NotNull)까지 가지 못한다.
        // null을 보존하는 불변 복사라야 400 details에 어느 좌석이 문제인지 남는다
        seatIds = seatIds == null ? null : Collections.unmodifiableList(new ArrayList<>(seatIds));
    }
}

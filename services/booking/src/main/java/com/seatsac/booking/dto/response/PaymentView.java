package com.seatsac.booking.dto.response;

import com.seatsac.common.core.time.Times;
import java.time.Instant;
import java.time.OffsetDateTime;
import lombok.Builder;

/**
 * 응답에 함께 나가는 결제 정보 — 명세 5장. 결제 요청 전이면 응답의 {@code payment}는 {@code null}이다.
 * 카드는 끝 4자리만 담는다(전체 번호·{@code expiry}·{@code holder}는 응답·로그에 넣지 않는다).
 */
@Builder
public record PaymentView(
    String paymentId,
    String status,
    String cardLast4,
    String failureCode,
    OffsetDateTime approvedAt,
    OffsetDateTime canceledAt
) {
    /** UTC {@link Instant}를 받아 응답용 {@code +09:00}으로 바꿔 담는다 */
    public static PaymentView of(String paymentId, String status, String cardLast4, String failureCode,
                                 Instant approvedAt, Instant canceledAt) {
        return PaymentView.builder()
            .paymentId(paymentId)
            .status(status)
            .cardLast4(cardLast4)
            .failureCode(failureCode)
            .approvedAt(Times.toApi(approvedAt))
            .canceledAt(Times.toApi(canceledAt))
            .build();
    }
}

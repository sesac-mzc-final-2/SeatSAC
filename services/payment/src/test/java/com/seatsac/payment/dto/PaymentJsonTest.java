package com.seatsac.payment.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.common.core.time.Times;
import com.seatsac.payment.dto.response.PaymentResponse;
import com.seatsac.payment.enums.PaymentMethod;
import com.seatsac.payment.enums.PaymentStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/**
 * 계약({@code payment.internal.openapi.yaml}의 {@code Payment})은 {@code failureCode}·
 * {@code cancelReason}·{@code approvedAt}·{@code canceledAt}을 <b>필수</b>로 둔다.
 * 값이 없을 때 키가 빠지면 소비자(Booking)가 「필드 없음」과 null을 구분해야 하므로 null로 담는다.
 */
@JsonTest
class PaymentJsonTest {

    private static final Instant NOW = Instant.parse("2026-10-23T03:02:59Z");   // KST 12:02:59

    @Autowired
    ObjectMapper mapper;

    @Test
    void nullableFieldsStayInTheBodyAsNull() throws Exception {
        String json = mapper.writeValueAsString(PaymentResponse.builder()
            .paymentId("3f2b8c1e-7a4d-4e9b-9c61-2d5f0a8e7b13").bookingId(5001L).amount(30_000)
            .method(PaymentMethod.CARD).status(PaymentStatus.PROCESSING).cardLast4("9999")
            .createdAt(Times.toApi(NOW))
            .build());

        assertThat(json)
            .contains("\"failureCode\":null")
            .contains("\"cancelReason\":null")
            .contains("\"approvedAt\":null")
            .contains("\"canceledAt\":null")
            .contains("\"createdAt\":\"2026-10-23T12:02:59+09:00\"");
    }
}

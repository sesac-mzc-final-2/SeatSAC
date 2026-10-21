package com.seatsac.booking.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.dto.request.BookingPayRequest;
import com.seatsac.booking.dto.request.SeatHoldRequest;
import com.seatsac.booking.dto.response.SeatHoldResponse;
import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.PaymentMethod;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/**
 * DTO가 명세(5장)의 JSON 모양과 맞는지. Lombok {@code @Builder} + {@code @Jacksonized}로
 * 역직렬화가 되는지, 응답 시각이 {@code +09:00}으로 나가는지, 민감값이 새지 않는지 확인한다.
 */
@JsonTest
class BookingDtoJsonTest {

    private static final Instant NOW = Instant.parse("2026-10-20T03:01:10Z");   // KST 12:01:10

    @Autowired
    ObjectMapper mapper;

    @Test
    void seatHoldRequestIsDeserialized() throws Exception {
        SeatHoldRequest req = mapper.readValue(
            "{\"screeningId\": 42, \"seatIds\": [101, 102]}", SeatHoldRequest.class);

        assertThat(req.getScreeningId()).isEqualTo(42L);
        assertThat(req.seatIds()).containsExactly(101L, 102L);
    }

    @Test
    void payRequestIsDeserializedAndKeepsCardOutOfToString() throws Exception {
        BookingPayRequest req = mapper.readValue("""
            {"method": "CARD", "card": {"number": "4111111111111111", "expiry": "12/28", "holder": "KIM"}}
            """, BookingPayRequest.class);

        assertThat(req.getMethod()).isEqualTo(PaymentMethod.CARD);
        assertThat(req.getCard().last4()).isEqualTo("1111");
        assertThat(req.toString()).doesNotContain("4111111111111111").doesNotContain("KIM");
    }

    @Test
    void holdResponseUsesKstOffsetAndHidesHoldId() throws Exception {
        Booking booking = Booking.pending("11111111-1111-4111-8111-111111111111", 42L,
            "hold-uuid-must-not-leak", List.of("J7", "J8"), 2, NOW.plus(BookingPolicy.HOLD_TTL), NOW);

        String json = mapper.writeValueAsString(SeatHoldResponse.from(booking, List.of(101L, 102L)));

        assertThat(json).contains("\"amount\":30000")
            .contains("\"seatLabels\":[\"J7\",\"J8\"]")
            .contains("\"expiresAt\":\"2026-10-20T12:06:10+09:00\"")
            .contains("\"createdAt\":\"2026-10-20T12:01:10+09:00\"")
            .doesNotContain("holdId")
            .doesNotContain("hold-uuid-must-not-leak");
    }

    @Test
    void validationRejectsTooManySeatsAndBadCard() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(SeatHoldRequest.builder()
                .screeningId(42L).seatIds(List.of(1L, 2L, 3L, 4L, 5L)).build())).hasSize(1);
            assertThat(validator.validate(SeatHoldRequest.builder()
                .screeningId(42L).seatIds(List.of()).build())).hasSize(1);
            assertThat(validator.validate(BookingPayRequest.builder()
                .method(PaymentMethod.CARD)
                .card(BookingPayRequest.Card.builder()
                    .number("4111").expiry("13/28").holder("KIM").build())
                .build())).hasSize(2);   // number 자리수, expiry 월
        }
    }
}

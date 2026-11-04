package com.seatsac.booking.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.dto.request.BookingPayRequest;
import com.seatsac.booking.dto.request.SeatHoldRequest;
import com.seatsac.booking.dto.response.BookingCancelResponse;
import com.seatsac.booking.dto.response.PaymentView;
import com.seatsac.booking.dto.response.SeatHoldResponse;
import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.booking.enums.PaymentMethod;
import com.seatsac.common.core.time.Times;
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

        assertThat(req.screeningId()).isEqualTo(42L);
        assertThat(req.seatIds()).containsExactly(101L, 102L);
    }

    @Test
    void payRequestIsDeserializedAndKeepsCardOutOfToString() throws Exception {
        BookingPayRequest req = mapper.readValue("""
            {"method": "CARD", "card": {"number": "4111111111111111", "expiry": "12/28", "holder": "KIM"}}
            """, BookingPayRequest.class);

        assertThat(req.method()).isEqualTo(PaymentMethod.CARD);
        assertThat(req.card().last4()).isEqualTo("1111");
        assertThat(req.toString()).doesNotContain("4111111111111111").doesNotContain("KIM");
        assertThat(req.card().toString()).doesNotContain("4111111111111111").doesNotContain("KIM");
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

            assertThat(validator.validate(
                new SeatHoldRequest(42L, List.of(1L, 2L, 3L, 4L, 5L)))).hasSize(1);   // 5석
            assertThat(validator.validate(
                new SeatHoldRequest(42L, List.of()))).hasSize(1);                     // 0석
            assertThat(validator.validate(new BookingPayRequest(PaymentMethod.CARD,
                new BookingPayRequest.Card("4111", "13/28", "KIM")))).hasSize(2);     // 자리수, 월
        }
    }

    /**
     * 카드 번호 자리수는 계약(`^[0-9]{12,19}$`)과 같아야 한다 — booking만 16자리로 좁히면
     * 화면·payment는 받아들이는 번호를 여기서 400으로 막는다.
     */
    @Test
    void cardNumberAcceptsContractRange() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            for (String number : List.of("4".repeat(12), "4".repeat(16), "4".repeat(19))) {
                assertThat(validator.validate(new BookingPayRequest(PaymentMethod.CARD,
                    new BookingPayRequest.Card(number, "12/28", "KIM"))))
                    .as("계약이 허용하는 자리수: %s자리", number.length()).isEmpty();
            }
            for (String number : List.of("4".repeat(11), "4".repeat(20), "4242-4242-4242-4242")) {
                assertThat(validator.validate(new BookingPayRequest(PaymentMethod.CARD,
                    new BookingPayRequest.Card(number, "12/28", "KIM"))))
                    .as("계약 밖: %s", number).hasSize(1);
            }
        }
    }

    /**
     * 계약의 {@code CancelResult} 하나를 BKG-03·BKG-07이 같이 쓴다 —
     * 결제가 없는 해제에서는 {@code payment} 키가 아예 빠지고, 취소에서는 담긴다.
     */
    @Test
    void cancelResultOmitsPaymentOnHoldRelease() throws Exception {
        String release = mapper.writeValueAsString(
            BookingCancelResponse.ofHoldRelease(5001L, Times.toApi(NOW)));

        assertThat(release).contains("\"status\":\"CANCELED\"")
            .contains("\"canceledAt\":\"2026-10-20T12:01:10+09:00\"")
            .doesNotContain("payment");

        String canceled = mapper.writeValueAsString(new BookingCancelResponse(5001L,
            BookingStatus.CANCELED, Times.toApi(NOW),
            PaymentView.of("3f2b8a10-0000-4000-8000-000000000001", "CANCELED", "1111",
                null, NOW, NOW)));

        assertThat(canceled).contains("\"payment\":")
            .contains("\"cardLast4\":\"1111\"")
            .doesNotContain("4111111111111111");
    }
}

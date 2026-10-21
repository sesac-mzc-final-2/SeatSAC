package com.seatsac.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatsac.booking.common.BookingErrorCode;
import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.booking.repository.BookingRepository;
import com.seatsac.booking.repository.BookingSeatRepository;
import com.seatsac.booking.service.BookingStateService.PendingBooking;
import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.test.MySqlContainerSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 3.2.1 확인: PENDING 예매 생성과 유일 제약 (screening_id, seat_id) 위반.
 * DDL은 Flyway가 만들고 엔티티는 {@code ddl-auto=validate}로 검증되므로, 이 테스트가 통과하면
 * 매핑과 DDL이 맞는다는 뜻이다. Clock은 고정 값으로 바꿔 끼운다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlContainerSupport.class)
class BookingStateServiceTest {

    private static final String USER_ID = "11111111-1111-4111-8111-111111111111";
    private static final Instant NOW = Instant.parse("2026-10-21T03:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Autowired
    BookingStateService bookingStateService;
    @Autowired
    BookingRepository bookingRepository;
    @Autowired
    BookingSeatRepository bookingSeatRepository;

    @Test
    void createPendingSavesBookingAndSeats() {
        Instant now = clock.instant();

        Booking saved = bookingStateService.createPending(
            command(42L, List.of(101L, 102L), now.plus(BookingPolicy.HOLD_TTL)), now);

        Booking found = bookingRepository.findById(saved.getBookingId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(found.getUserId()).isEqualTo(USER_ID);
        assertThat(found.getSeatCount()).isEqualTo(2);
        assertThat(found.getAmount()).isEqualTo(30_000);
        assertThat(found.getSeatLabels()).containsExactly("J7", "J8");   // JSON 컬럼 왕복
        assertThat(found.getExpiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(found.getCurrentPaymentId()).isNull();
        assertThat(bookingSeatRepository.findByScreeningId(42L))
            .extracting(seat -> seat.getSeatId())
            .containsExactlyInAnyOrder(101L, 102L);
    }

    @Test
    void createPendingRejectsSeatAlreadyTaken() {
        Instant now = clock.instant();
        bookingStateService.createPending(command(77L, List.of(201L), now.plus(BookingPolicy.HOLD_TTL)), now);

        assertThatThrownBy(() -> bookingStateService.createPending(
            command(77L, List.of(201L, 202L), now.plus(BookingPolicy.HOLD_TTL)), now))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code())
            .isEqualTo(BookingErrorCode.SEAT_UNAVAILABLE);

        // 두 번째 요청의 좌석은 남지 않는다 (같은 트랜잭션이 롤백)
        assertThat(bookingSeatRepository.findByScreeningId(77L)).hasSize(1);
    }

    private PendingBooking command(Long screeningId, List<Long> seatIds, Instant expiresAt) {
        List<String> labels = seatIds.size() == 2 ? List.of("J7", "J8") : List.of("J7");
        return new PendingBooking(USER_ID, screeningId, UUID.randomUUID().toString(), seatIds,
            labels.subList(0, seatIds.size()), expiresAt);
    }
}

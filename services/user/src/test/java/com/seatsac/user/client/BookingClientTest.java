package com.seatsac.user.client;

import com.seatsac.common.test.StubHttpServer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatsac.common.core.PageResponse;
import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.web.client.InternalRestClientFactory;
import com.seatsac.user.common.UserErrorCode;
import com.seatsac.user.dto.BookingHistoryStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * booking 내역 호출의 결말 — 정상(쿼리 전달·페이징 그대로·모르는 필드 무시), 5xx·타임아웃 → 503 BOOKING_UNAVAILABLE,
 * 서킷 열림(호출 없이 503). 컨테이너 없이 JDK HttpServer로 booking을 대신한다.
 */
class BookingClientTest {

    private static final String USER = "11111111-1111-4111-8111-111111111111";
    private static final String PAGE = "{\"content\":[{\"bookingId\":101,\"status\":\"CONFIRMED\",\"screeningId\":5001,"
        + "\"seatLabels\":[\"E7\",\"E8\"],\"seatCount\":2,\"amount\":30000,\"createdAt\":\"2026-11-16T11:00:00+09:00\","
        + "\"confirmedAt\":\"2026-11-16T11:00:30+09:00\",\"canceledAt\":null,\"extra\":true}],"
        + "\"page\":1,\"size\":10,\"totalElements\":11,\"totalPages\":2}";

    private StubHttpServer booking;
    private CircuitBreakerRegistry breakers;
    private BookingClient client;

    @BeforeEach
    void start() throws Exception {
        booking = new StubHttpServer("/internal/bookings", request -> PAGE);
        breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
            .slidingWindowSize(4).minimumNumberOfCalls(2).failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(10)).build());
        client = new BookingClient(
            new InternalRestClientFactory().create(booking.baseUrl(), Duration.ofMillis(500)), breakers);
    }

    @AfterEach
    void stop() {
        booking.close();
    }

    @Test
    void passesQueryThroughAndKeepsBookingPaging() {
        PageResponse<BookingClient.BookingItem> page = client.listBookings(USER, BookingHistoryStatus.CONFIRMED, 1, 10);

        assertThat(booking.received.get(0).query()).isEqualTo("userId=" + USER + "&status=CONFIRMED&page=1&size=10");
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(10);
        assertThat(page.totalElements()).isEqualTo(11);
        assertThat(page.totalPages()).isEqualTo(2);
        BookingClient.BookingItem item = page.content().get(0);
        assertThat(item.bookingId()).isEqualTo(101L);
        assertThat(item.status()).isEqualTo("CONFIRMED");
        assertThat(item.screeningId()).isEqualTo(5001L);
        assertThat(item.seatLabels()).containsExactly("E7", "E8");
        assertThat(item.confirmedAt().toInstant()).isEqualTo(OffsetDateTime.parse("2026-11-16T11:00:30+09:00").toInstant());
        assertThat(item.canceledAt()).isNull();
    }

    @Test
    void omitsStatusWhenNotGiven() {
        client.listBookings(USER, null, 0, 20);

        assertThat(booking.received.get(0).query()).isEqualTo("userId=" + USER + "&page=0&size=20");
    }

    @Test
    void serverErrorIsBookingUnavailable() {
        booking.status = 503;

        assertThatThrownBy(() -> client.listBookings(USER, null, 0, 20))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.code()).isEqualTo(UserErrorCode.BOOKING_UNAVAILABLE);
                assertThat(e.code().status()).isEqualTo(503);
                assertThat(e.code().responseCode()).isEqualTo("BOOKING_UNAVAILABLE");
            });
    }

    @Test
    void slowResponseIsBookingUnavailable() {
        booking.delayMillis = 1500;

        assertThatThrownBy(() -> client.listBookings(USER, null, 0, 20))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo(UserErrorCode.BOOKING_UNAVAILABLE));
    }

    @Test
    void openCircuitSkipsTheCall() {
        booking.status = 503;
        assertThatThrownBy(() -> client.listBookings(USER, null, 0, 20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> client.listBookings(USER, null, 0, 20)).isInstanceOf(BusinessException.class);
        assertThat(breakers.circuitBreaker("booking").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int callsSoFar = booking.received.size();

        booking.status = 200;
        assertThatThrownBy(() -> client.listBookings(USER, null, 0, 20))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo(UserErrorCode.BOOKING_UNAVAILABLE));
        assertThat(booking.received).hasSize(callsSoFar);
    }
}

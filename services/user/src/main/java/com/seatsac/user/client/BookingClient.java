package com.seatsac.user.client;

import com.seatsac.common.core.PageResponse;
import com.seatsac.common.core.error.BusinessException;
import com.seatsac.user.common.UserErrorCode;
import com.seatsac.user.dto.BookingHistoryStatus;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@code GET http://booking:8080/internal/bookings?userId&status&page&size} (booking 내부 계약, BKG-14).
 *
 * <ul>
 *   <li>타임아웃·연결 오류·5xx는 공통 {@code InternalRestClientFactory}가 503으로 바꿔 던진다</li>
 *   <li>서킷 {@code booking}(실패율 50%·10초, ADR-23)이 열려 있으면 호출하지 않는다</li>
 *   <li>어떤 실패든 503 {@code BOOKING_UNAVAILABLE}(계약) — 예매 내역은 대체할 값이 없다</li>
 * </ul>
 * 재시도는 하지 않는다. 응답의 모르는 필드는 무시한다.
 */
@Component
public class BookingClient {

    private static final ParameterizedTypeReference<PageResponse<BookingItem>> PAGE_TYPE =
        new ParameterizedTypeReference<>() {
        };

    private static final Logger log = LoggerFactory.getLogger(BookingClient.class);

    private final RestClient bookingRestClient;
    private final CircuitBreaker circuitBreaker;

    public BookingClient(RestClient bookingRestClient, CircuitBreakerRegistry circuitBreakerRegistry) {
        this.bookingRestClient = bookingRestClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("booking");
    }

    /** @param status null이면 PENDING 제외 전부 (booking이 처리) */
    public PageResponse<BookingItem> listBookings(String userId, BookingHistoryStatus status, int page, int size) {
        try {
            PageResponse<BookingItem> body = circuitBreaker.executeSupplier(() -> bookingRestClient.get()
                .uri(builder -> builder.path("/internal/bookings")
                    .queryParam("userId", userId)
                    .queryParamIfPresent("status", Optional.ofNullable(status))
                    .queryParam("page", page)
                    .queryParam("size", size)
                    .build())
                .retrieve()
                .body(PAGE_TYPE));
            if (body == null) {
                log.warn("booking returned an empty body for /internal/bookings");
                throw UserErrorCode.BOOKING_UNAVAILABLE.exception();
            }
            return body;
        } catch (CallNotPermittedException | BusinessException | RestClientException e) {
            log.warn("booking history unavailable cause={}: {}", e.getClass().getSimpleName(), e.getMessage());
            throw UserErrorCode.BOOKING_UNAVAILABLE.exception(e);
        }
    }

    /**
     * booking 응답 원소. 시각은 booking이 +09:00으로 주지만 역직렬화 설정에 따라 오프셋이 바뀔 수 있어
     * 응답 DTO에서 {@code Times.toApi}로 다시 맞춘다. {@code status}는 모르는 값이 와도 그대로 넘기도록 문자열이다.
     */
    public record BookingItem(
        Long bookingId,
        String status,
        Long screeningId,
        List<String> seatLabels,
        Integer seatCount,
        Long amount,
        OffsetDateTime createdAt,
        OffsetDateTime confirmedAt,
        OffsetDateTime canceledAt
    ) {
        public BookingItem {
            seatLabels = seatLabels == null ? List.of() : List.copyOf(seatLabels);
        }
    }
}

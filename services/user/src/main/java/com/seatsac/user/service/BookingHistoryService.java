package com.seatsac.user.service;

import com.seatsac.common.core.PageResponse;
import com.seatsac.user.client.BookingClient;
import com.seatsac.user.client.BookingClient.BookingItem;
import com.seatsac.user.client.CatalogClient;
import com.seatsac.user.client.CatalogClient.ScreeningSummary;
import com.seatsac.user.dto.BookingHistoryStatus;
import com.seatsac.user.dto.MyBooking;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * USR-04 마이페이지 예매 내역 — booking {@code GET /internal/bookings} → catalog {@code POST /internal/screenings/summaries}
 * 순서로 합친다(계약). booking 실패는 503 {@code BOOKING_UNAVAILABLE}({@link BookingClient}), catalog 실패는
 * {@code screening: null}. 페이징 값은 booking 것을 그대로 돌려준다.
 */
@Service
public class BookingHistoryService {

    private static final Logger log = LoggerFactory.getLogger(BookingHistoryService.class);

    private final BookingClient bookingClient;
    private final CatalogClient catalogClient;

    public BookingHistoryService(BookingClient bookingClient, CatalogClient catalogClient) {
        this.bookingClient = bookingClient;
        this.catalogClient = catalogClient;
    }

    public PageResponse<MyBooking> listMyBookings(String userId, BookingHistoryStatus status, int page, int size) {
        PageResponse<BookingItem> bookings = bookingClient.listBookings(userId, status, page, size);

        Set<Long> screeningIds = new LinkedHashSet<>();
        for (BookingItem item : bookings.content()) {
            if (item != null && item.screeningId() != null) {
                screeningIds.add(item.screeningId());
            }
        }
        Map<Long, ScreeningSummary> summaries = catalogClient.screeningSummaries(screeningIds).orElseGet(() -> {
            log.warn("screening summaries unavailable - bookings returned without screening userId={}", userId);
            return Map.of();
        });

        List<MyBooking> content = bookings.content().stream()
            .filter(Objects::nonNull)
            .map(item -> MyBooking.of(item, summaries.get(item.screeningId())))
            .toList();
        return new PageResponse<>(content, bookings.page(), bookings.size(), bookings.totalElements(),
            bookings.totalPages());
    }
}

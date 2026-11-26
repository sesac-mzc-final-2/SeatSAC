package com.seatsac.user.history;

import com.seatsac.common.core.PageResponse;
import com.seatsac.user.client.BookingClient;
import com.seatsac.user.client.BookingClient.BookingItem;
import com.seatsac.user.dto.BookingHistoryStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code APP_BOOKING_HISTORY_MODE=local}의 읽기(API 명세서 v1.5 23-1).
 *
 * <ul>
 *   <li><b>첫 조회 채우기</b>: {@code booking_history_sync}에 그 사용자가 없으면 booking {@code GET /internal/bookings}로 한 번
 *       채우고 기록한다. 그 뒤로는 복제본만 — 예매가 없는 사용자도 매번 booking으로 가지 않는다. 채우는 중 booking 장애면
 *       sync와 같은 503 {@code BOOKING_UNAVAILABLE}이고 기록하지 않아 다음 조회가 다시 채운다</li>
 *   <li>채우는 값은 CONFIRMED와 「확정된 뒤 취소된」 CANCELED만 — 이벤트로 받는 것과 같은 범위. 같은 upsert 규칙이라 채우는 동안
 *       이벤트가 와도 CANCELED가 이긴다</li>
 *   <li>정렬은 {@code confirmed_at} 내림차순, 응답 페이징 모양은 sync와 같다</li>
 * </ul>
 */
@Component
public class BookingHistoryCopy {

    /** booking 내부 API 쪽 크기 상한(계약 size 최대 50) */
    static final int FILL_PAGE_SIZE = 50;

    private static final Logger log = LoggerFactory.getLogger(BookingHistoryCopy.class);

    private final BookingClient bookingClient;
    private final BookingHistoryStore store;
    private final TransactionTemplate tx;
    private final BookingHistoryProperties properties;
    private final Clock clock;

    public BookingHistoryCopy(BookingClient bookingClient, BookingHistoryStore store, TransactionTemplate tx,
                              BookingHistoryProperties properties, Clock clock) {
        this.bookingClient = bookingClient;
        this.store = store;
        this.tx = tx;
        this.properties = properties;
        this.clock = clock;
    }

    public PageResponse<BookingItem> listBookings(String userId, BookingHistoryStatus status, int page, int size) {
        if (!store.isSynced(userId)) {
            fill(userId);
        }
        long total = store.count(userId, status);
        List<BookingItem> content = store.page(userId, status, page, size);
        int totalPages = (int) ((total + size - 1) / size);
        return new PageResponse<>(content, page, size, total, totalPages);
    }

    private void fill(String userId) {
        List<BookingHistoryStore.Row> rows = new ArrayList<>();
        int pages = 0;
        for (int page = 0; ; page++) {
            PageResponse<BookingItem> chunk = bookingClient.listBookings(userId, null, page, FILL_PAGE_SIZE);
            for (BookingItem item : chunk.content()) {
                BookingHistoryStore.Row row = toRow(userId, item);
                if (row != null) {
                    rows.add(row);
                }
            }
            pages++;
            if (page + 1 >= chunk.totalPages() || chunk.content().isEmpty()) {
                break;
            }
            if (pages >= properties.fillMaxPages()) {
                log.warn("예매 내역 첫 채우기가 {}쪽에서 멈췄다 — 나머지는 이벤트로만 들어온다 userId={}", pages, userId);
                break;
            }
        }
        Instant now = clock.instant();
        tx.executeWithoutResult(status -> {
            rows.forEach(row -> store.upsert(row, now));
            store.markSynced(userId, now);
        });
        log.info("예매 내역 복제본 첫 채우기 userId={} rows={} pages={}", userId, rows.size(), pages);
    }

    /** @return null이면 내역 복제본 대상이 아니다(PENDING·결제 실패·만료·확정 전 취소) */
    static BookingHistoryStore.Row toRow(String userId, BookingItem item) {
        if (item == null || item.bookingId() == null || item.screeningId() == null || item.confirmedAt() == null) {
            return null;
        }
        BookingHistoryStatus status;
        if ("CONFIRMED".equals(item.status())) {
            status = BookingHistoryStatus.CONFIRMED;
        } else if ("CANCELED".equals(item.status())) {
            status = BookingHistoryStatus.CANCELED;
        } else {
            return null;
        }
        return new BookingHistoryStore.Row(item.bookingId(), userId, status, item.screeningId(), item.seatLabels(),
            item.seatCount(), item.amount(), null, instant(item.createdAt()), instant(item.confirmedAt()),
            status == BookingHistoryStatus.CANCELED ? instant(item.canceledAt()) : null);
    }

    private static Instant instant(OffsetDateTime at) {
        return at == null ? null : at.toInstant();
    }
}

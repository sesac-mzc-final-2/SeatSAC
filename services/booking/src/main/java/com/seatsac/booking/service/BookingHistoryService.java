package com.seatsac.booking.service;

import com.seatsac.booking.dto.response.BookingSummaryResponse;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.booking.repository.BookingRepository;
import com.seatsac.common.core.PageResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BKG-14 사용자 예매 내역 — user 서비스(USR-04)가 내부 API로 받아 Catalog의 영화·상영 정보와 합친다.
 *
 * <p><b>{@code PENDING}은 기본으로 제외한다.</b> 선점 중인 예매는 「내역」이 아니라 진행 중인 결제이고,
 * 5분 뒤 사라질 수도 있어 마이페이지에 보이면 혼란스럽다(계약: 생략 시 PENDING 제외 전부).
 *
 * <p>{@code booking} 테이블만 읽는다 — 좌석 번호는 {@code seat_labels} 컬럼에 있고, 끝난 예매는
 * {@code booking_seat} 행이 지워져 있다.
 */
@Service
@RequiredArgsConstructor
public class BookingHistoryService {

    /** 내역에 보여 주는 상태 (계약의 status enum과 같다) */
    private static final List<BookingStatus> HISTORY_STATUSES = List.of(
        BookingStatus.CONFIRMED, BookingStatus.PAYMENT_FAILED,
        BookingStatus.EXPIRED, BookingStatus.CANCELED);

    private final BookingRepository bookingRepository;

    @Transactional(readOnly = true)
    public PageResponse<BookingSummaryResponse> findByUser(String userId, BookingStatus status,
                                                           int page, int size) {
        List<BookingStatus> statuses = status == null ? HISTORY_STATUSES : List.of(status);
        Page<com.seatsac.booking.entity.Booking> found = bookingRepository
            .findByUserIdAndStatusIn(userId, statuses, PageRequest.of(page, size));

        return PageResponse.of(found.getContent().stream().map(BookingSummaryResponse::from).toList(),
            page, size, found.getTotalElements());
    }

    /** 내역에 보여 줄 수 있는 상태인지 — {@code PENDING}을 콕 집어 요청하면 400이다 */
    public static boolean isHistoryStatus(BookingStatus status) {
        return HISTORY_STATUSES.contains(status);
    }
}

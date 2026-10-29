package com.seatsac.booking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.booking.component.CatalogClient;
import com.seatsac.booking.component.PaymentClient;
import com.seatsac.booking.component.PaymentClient.PaymentCancelReason;
import com.seatsac.booking.component.PaymentClient.PaymentResult;
import com.seatsac.booking.component.SeatEventPublisher;
import com.seatsac.booking.component.SeatHoldStrategy;
import com.seatsac.booking.dto.event.BookingConfirmedEvent;
import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.SeatEventType;
import com.seatsac.common.core.trace.TraceIds;
import com.seatsac.common.core.trace.TraceKeys;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * BKG-06 결제 결과 반영 — 명세 5·6장. <b>두 경로가 같은 로직을 쓴다</b>:
 * BKG-10 결제 응답, 그리고 단건 조회(결과 재확인, 지연 카드 경로).
 *
 * <p>승인 처리의 순서가 핵심이다.
 * <pre>
 * 1. [tx 밖] 알림 본문 준비 (Catalog 1초·3초 호출을 트랜잭션 안에 두면 Hikari 5개가 마른다)
 * 2. [tx]    CONFIRMED 조건부 UPDATE가 1행일 때만 아웃박스 INSERT  ← 둘이 같은 트랜잭션
 * 3. 커밋 후  release.lua → INCR → PUBLISH CONFIRMED
 * </pre>
 *
 * <p>2단계가 0행이면 그 사이 선점이 만료된 것이다 — 아웃박스를 쓰지 않고 <b>결제를 취소</b>한다.
 * 돈만 빠져나가고 좌석은 없는 상태를 만들지 않기 위해서다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingPaymentResultApplier {

    /** 반영 결과 — 부르는 쪽이 응답(200·409)을 고른다 */
    public enum Applied {
        /** 내가 확정했다 (아웃박스 행도 들어갔다) */
        CONFIRMED,
        /** <b>다른 경로가 이미 확정했다.</b> 환불하지 않는다 — 예매도 결제도 유효하다 */
        ALREADY_CONFIRMED,
        /** 승인을 받았지만 확정할 수 없는 상태였다(만료). 결제를 취소(환불)하고 예매도 EXPIRED로 끝냈다 */
        EXPIRED_REFUNDED,
        /** 거절을 반영했다 (좌석을 풀었다) */
        FAILED,
        /** 이미 다른 경로가 처리했다 (거절 반영을 못 한 경우) */
        ALREADY_SETTLED
    }

    private final BookingStateService bookingState;
    private final BookingRepositoryReader bookingReader;
    private final CatalogClient catalogClient;
    private final PaymentClient paymentClient;
    private final SeatHoldStrategy seatHold;
    private final SeatEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public Applied applyApproved(Booking booking, PaymentResult payment) {
        String messageKey = BookingConfirmedEvent.newMessageKey();
        String traceId = traceId();
        // 느린 일(Catalog 조회·좌석 ID 읽기)을 먼저 끝낸다
        CatalogClient.Screening screening = findScreening(booking);
        List<Long> seatIds = bookingReader.findOccupiedSeatIds(booking.getBookingId());

        // 시각은 <b>확정 바로 앞에서</b> 구한다. 부르는 쪽이 결제 요청 전에 구한 값이나
        // Catalog 조회(연결 1초·응답 3초) 전에 구한 값을 쓰면, 그 사이 선점이 만료돼도
        // 확정 조건(expiresAt > :now)이 통과해 「만료된 선점을 확정」하고 환불을 건너뛴다.
        // 이 한 시각을 만료 조건·confirmedAt·아웃박스 payload에 함께 쓴다
        Instant now = clock.instant();
        String payload = payloadOf(messageKey, booking, screening, traceId, now);

        if (!bookingState.confirmWithOutbox(booking.getBookingId(), messageKey, payload, traceId, now)) {
            // 0행인 이유가 두 가지다 — 만료됐거나, 그 사이 <b>다른 경로가 이미 확정</b>했거나.
            // 두 경로(BKG-10 응답·단건 조회 폴링)가 겹치면 후자가 생긴다. 그때 환불하면
            // 「확정된 예매 + 환불된 결제」가 되므로 현재 상태를 반드시 다시 읽는다
            boolean alreadyConfirmed = bookingReader.findById(booking.getBookingId())
                .map(Booking::isConfirmed)
                .orElse(false);
            if (alreadyConfirmed) {
                log.info("다른 경로가 먼저 확정했다 — 환불하지 않는다 bookingId={} paymentId={}",
                    booking.getBookingId(), payment.paymentId());
                return Applied.ALREADY_CONFIRMED;
            }

            log.warn("승인 뒤 확정 실패(만료·취소) — 결제를 취소하고 예매를 만료로 끝낸다 bookingId={} paymentId={}",
                booking.getBookingId(), payment.paymentId());
            // 환불을 먼저 한다. 만료로 바꾼 뒤 취소가 실패하면 「예매 없는 승인 결제」가 남는다 —
            // 여기서 실패하면 PENDING으로 남겨 다음 조회가 다시 시도하게 둔다
            if (cancelPayment(payment.paymentId(), PaymentCancelReason.HOLD_EXPIRED)) {
                expireAfterRefund(booking, now);
            } else {
                log.warn("환불을 확인하지 못해 예매를 PENDING으로 남긴다 — 다음 조회가 다시 시도한다 bookingId={}",
                    booking.getBookingId());
            }
            return Applied.EXPIRED_REFUNDED;
        }

        releaseKeys(booking, seatIds);
        eventPublisher.publish(SeatEventType.CONFIRMED, booking.getScreeningId(), seatIds, now);
        log.info("예매 확정 bookingId={} paymentId={} messageKey={}", booking.getBookingId(),
            payment.paymentId(), messageKey);
        return Applied.CONFIRMED;
    }

    public Applied applyDeclined(Booking booking) {
        Instant now = clock.instant();
        List<Long> seatIds = bookingReader.findOccupiedSeatIds(booking.getBookingId());
        if (!bookingState.failPayment(booking.getBookingId(), now)) {
            log.info("결제 거절을 반영하지 못했다(이미 처리됨) bookingId={}", booking.getBookingId());
            return Applied.ALREADY_SETTLED;
        }

        releaseKeys(booking, seatIds);
        eventPublisher.publish(SeatEventType.RELEASED, booking.getScreeningId(), seatIds, now);
        log.info("결제 거절 반영 bookingId={} seats={}", booking.getBookingId(), seatIds.size());
        return Applied.FAILED;
    }

    /** Catalog 실패는 알림 본문만 비운다 — 결제가 승인됐으니 확정을 멈출 수 없다 (명세 5장) */
    /** 알림 본문에 넣을 회차 정보. 실패해도 확정은 진행한다(이름·시각이 null인 채로 간다) */
    private CatalogClient.Screening findScreening(Booking booking) {
        try {
            return catalogClient.findScreening(booking.getScreeningId());
        } catch (RuntimeException e) {
            log.warn("알림 본문의 Catalog 조회 실패 — 이름·시각을 비우고 확정은 진행한다 screeningId={}",
                booking.getScreeningId());
            return null;
        }
    }

    private String payloadOf(String messageKey, Booking booking, CatalogClient.Screening screening,
                             String traceId, Instant now) {
        try {
            return objectMapper.writeValueAsString(
                BookingConfirmedEvent.of(messageKey, booking, screening, traceId, now));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("알림 본문을 만들 수 없다", e);
        }
    }

    /**
     * 환불한 뒤 예매를 <b>EXPIRED로 끝낸다</b>. 여기서 끝내지 않으면 예매가 PENDING에 남는데,
     * 만료 스캔은 결제를 요청한 예매({@code current_payment_id} 있음)를 계속 보류하므로
     * <b>아무도 다시 조회하지 않으면 영구히 PENDING</b>이 된다 — 그 사이 {@code booking_seat} 행이
     * 남아 그 좌석은 좌석 현황에 비어 보이는데도 다시 선점되지 않는다(유일 제약).
     *
     * <p>계약도 이 경우를 EXPIRED로 본다 (BKG-10 409 {@code details[status=EXPIRED, reason=HOLD_EXPIRED]}).
     * 0행이면 그 사이 다른 경로가 끝낸 것이므로 아무것도 하지 않는다.
     */
    private void expireAfterRefund(Booking booking, Instant now) {
        bookingState.expireHold(booking.getBookingId(), now).ifPresentOrElse(hold -> {
            eventPublisher.publish(SeatEventType.EXPIRED, hold.screeningId(), hold.seatIds(), now);
            releaseKeys(booking, hold.seatIds());
            log.info("환불 후 만료 반영 bookingId={} seats={}", hold.bookingId(), hold.seatIds().size());
        }, () -> log.info("환불 후 만료를 반영하지 못했다(이미 처리됨) bookingId={}", booking.getBookingId()));
    }

    /** 확정·거절 모두 선점 키는 더 필요 없다. 실패해도 TTL·만료 스캔이 치운다 */
    private void releaseKeys(Booking booking, List<Long> seatIds) {
        try {
            seatHold.release(booking.getScreeningId(), booking.getUserId(), booking.getHoldId(),
                seatIds);
        } catch (DataAccessException e) {
            log.warn("결제 처리 후 Valkey 정리 실패 bookingId={}", booking.getBookingId(), e);
        }
    }

    /** 환불 실패는 삼키고 로그만 — 응답은 이미 정해졌고 만료 처리(BKG-12)가 다시 시도한다 */
    /**
     * 결제 취소(환불). PAY-03은 멱등이라 다시 불러도 된다.
     *
     * @return 취소를 확인했으면 {@code true}. {@code false}면 <b>돈이 아직 잡혀 있을 수 있다</b> —
     *     부르는 쪽은 예매를 끝내지 말고 PENDING으로 남겨 다음 조회가 다시 시도하게 둔다
     */
    private boolean cancelPayment(String paymentId, PaymentCancelReason reason) {
        try {
            paymentClient.cancel(paymentId, reason);
            return true;
        } catch (RuntimeException e) {
            log.error("결제 취소 실패 paymentId={} reason={} — 다음 조회가 다시 시도한다",
                paymentId, reason, e);
            return false;
        }
    }

    /** 요청 밖(단건 조회는 요청 안이지만 만료 경로와 공유)에서도 32자리 추적 ID를 남긴다 */
    private static String traceId() {
        String current = MDC.get(TraceKeys.MDC_TRACE_ID);
        return TraceIds.isValid(current) ? current : TraceIds.newId();
    }
}

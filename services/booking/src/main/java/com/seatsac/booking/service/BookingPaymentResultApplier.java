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
        /** 승인을 받았지만 확정할 수 없는 상태였다(만료·취소). 결제는 취소(환불)했다 */
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

    public Applied applyApproved(Booking booking, PaymentResult payment, Instant now) {
        String messageKey = BookingConfirmedEvent.newMessageKey();
        String traceId = traceId();
        String payload = payloadOf(messageKey, booking, traceId, now);
        List<Long> seatIds = bookingReader.findOccupiedSeatIds(booking.getBookingId());

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

            log.warn("승인 뒤 확정 실패(만료·취소) — 결제를 취소한다 bookingId={} paymentId={}",
                booking.getBookingId(), payment.paymentId());
            cancelPayment(payment.paymentId(), PaymentCancelReason.HOLD_EXPIRED);
            return Applied.EXPIRED_REFUNDED;
        }

        releaseKeys(booking, seatIds);
        eventPublisher.publish(SeatEventType.CONFIRMED, booking.getScreeningId(), seatIds, now);
        log.info("예매 확정 bookingId={} paymentId={} messageKey={}", booking.getBookingId(),
            payment.paymentId(), messageKey);
        return Applied.CONFIRMED;
    }

    public Applied applyDeclined(Booking booking, Instant now) {
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
    private String payloadOf(String messageKey, Booking booking, String traceId, Instant now) {
        CatalogClient.Screening screening = null;
        try {
            screening = catalogClient.findScreening(booking.getScreeningId());
        } catch (RuntimeException e) {
            log.warn("알림 본문의 Catalog 조회 실패 — 이름·시각을 비우고 확정은 진행한다 screeningId={}",
                booking.getScreeningId());
        }
        try {
            return objectMapper.writeValueAsString(
                BookingConfirmedEvent.of(messageKey, booking, screening, traceId, now));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("알림 본문을 만들 수 없다", e);
        }
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
    private void cancelPayment(String paymentId, PaymentCancelReason reason) {
        try {
            paymentClient.cancel(paymentId, reason);
        } catch (RuntimeException e) {
            log.error("결제 취소 실패 paymentId={} reason={} — 만료 처리가 다시 시도한다",
                paymentId, reason, e);
        }
    }

    /** 요청 밖(단건 조회는 요청 안이지만 만료 경로와 공유)에서도 32자리 추적 ID를 남긴다 */
    private static String traceId() {
        String current = MDC.get(TraceKeys.MDC_TRACE_ID);
        return TraceIds.isValid(current) ? current : TraceIds.newId();
    }
}

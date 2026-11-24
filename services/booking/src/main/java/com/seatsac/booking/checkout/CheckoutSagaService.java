package com.seatsac.booking.checkout;

import com.seatsac.booking.checkout.CheckoutStateService.Begin;
import com.seatsac.booking.checkout.dto.CheckoutAcceptedResponse;
import com.seatsac.booking.checkout.dto.CheckoutRequest;
import com.seatsac.booking.checkout.dto.CheckoutResponse;
import com.seatsac.booking.common.BookingErrorCode;
import com.seatsac.booking.component.ScreeningCache;
import com.seatsac.booking.config.PaymentProperties;
import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.booking.enums.PaymentMethod;
import com.seatsac.booking.service.BookingRepositoryReader;
import com.seatsac.common.core.error.ErrorDetail;
import com.seatsac.common.core.trace.TraceIds;
import com.seatsac.common.core.trace.TraceKeys;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 체크아웃 saga 진입점(6.2.1, ADR-116, 설계 01 5장) — 시작·확인 요청·조회. 요청 스레드는 payment·coupon을 부르지 않는다:
 * saga 행과 첫 명령을 아웃박스에 쓰고 202로 끝난다. 결과는 결과 메시지가 반영하고 화면은 {@link #get}을 polling한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutSagaService {

    static final long POLL_AFTER_MS = 1_000L;

    private final Clock clock;
    private final BookingRepositoryReader bookingReader;
    private final ScreeningCache screeningCache;
    private final PaymentProperties paymentProperties;
    private final CheckoutProperties checkoutProperties;
    private final CheckoutSagaRepository sagas;
    private final CheckoutStateService stateService;
    private final MeterRegistry meterRegistry;

    /** {@code POST /bookings/{id}/checkout} — 202 */
    public CheckoutAcceptedResponse start(String userId, String mockKey, Long bookingId, CheckoutRequest request) {
        String cardLast4 = null;
        if (request.method() == PaymentMethod.CARD) {
            if (!paymentProperties.mockKeyMatches(mockKey)) {
                throw BookingErrorCode.MOCK_NOT_ALLOWED.exception(ErrorDetail.of("method", PaymentMethod.CARD.name(),
                    BookingErrorCode.MOCK_NOT_ALLOWED.name()));
            }
            if (request.card() == null) {
                throw BookingErrorCode.INVALID_PAYMENT_METHOD.exception(ErrorDetail.required("card"));
            }
            String number = request.card().number();
            cardLast4 = number.substring(number.length() - 4);   // 명령에는 끝 4자리만(번호 전체는 큐·DLQ에 남는다)
        }

        Instant now = clock.instant();
        Booking booking = ownedBooking(userId, bookingId);
        if (booking.getStatus() != BookingStatus.PENDING) {
            throw BookingErrorCode.BOOKING_NOT_PENDING.exception(ErrorDetail.of("status", booking.getStatus().name(),
                BookingErrorCode.BOOKING_NOT_PENDING.name()));
        }
        if (booking.isExpiredAt(now)) {
            throw BookingErrorCode.HOLD_EXPIRED.exception(ErrorDetail.of("status", booking.getStatus().name(),
                BookingErrorCode.HOLD_EXPIRED.name()));
        }
        if (sagas.existsActive(bookingId, CheckoutState.ACTIVE)) {
            throw inProgress(bookingId);
        }
        String replacing = null;
        if (booking.getCurrentPaymentId() != null) {
            // 재결제(D25, 플래그): 마지막 saga가 PAYMENT_RETRYABLE이고 그 결제가 지금 붙은 결제일 때만 교체한다.
            // 그 밖(동기 경로가 붙인 결제)은 한 예매 한 경로
            CheckoutSaga last = sagas.findFirstByBookingIdOrderByCreatedAtDescCheckoutIdDesc(bookingId).orElse(null);
            boolean retry = checkoutProperties.paymentRetryEnabled() && last != null
                && last.getState() == CheckoutState.PAYMENT_RETRYABLE
                && booking.getCurrentPaymentId().equals(last.getPaymentId());
            if (!retry) {
                throw BookingErrorCode.PAYMENT_PATH_CONFLICT.exception(ErrorDetail.of("bookingId", bookingId,
                    BookingErrorCode.PAYMENT_PATH_CONFLICT.name()));
            }
            replacing = booking.getCurrentPaymentId();
        }
        long previous = sagas.countByBookingId(bookingId);
        if (previous >= checkoutProperties.maxAttempts()) {
            throw BookingErrorCode.CHECKOUT_RETRY_LIMIT.exception(ErrorDetail.of("attempt", previous, "RETRY_LIMIT"));
        }
        // 예매 마감(상영 5분 전)·Catalog 응답 없음 503 — 트랜잭션 밖, 아무것도 바뀌기 전에
        screeningCache.requireOpen(screeningCache.screeningOfBooking(bookingId, booking.getScreeningId()));

        String checkoutId = UUID.randomUUID().toString();
        CheckoutState state;
        try {
            state = stateService.begin(new Begin(checkoutId, booking, userId, request.method(), (int) previous + 1,
                request.couponIssueId(), cardLast4, traceId(), replacing), now);
        } catch (DataIntegrityViolationException e) {
            throw inProgress(bookingId);   // 동시에 들어온 두 시작 — 유일 키가 하나만 받았다
        }
        if (state == CheckoutState.COUPON_REJECTED) {
            meterRegistry.counter("booking_checkout_total", "result", "coupon_rejected").increment();
        }
        log.info("체크아웃 시작 checkoutId={} bookingId={} method={} state={}", checkoutId, bookingId,
            request.method(), state);
        return new CheckoutAcceptedResponse(checkoutId, bookingId, state, state.isActive() ? POLL_AFTER_MS : null);
    }

    /** {@code POST /bookings/{id}/checkout/confirm} — 결제창이 닫혔다(성공·취소 어느 쪽이든). 202 */
    public CheckoutAcceptedResponse confirm(String userId, Long bookingId, String paymentId) {
        ownedBooking(userId, bookingId);
        CheckoutSaga saga = latest(bookingId);
        if (!paymentId.equals(saga.getPaymentId())) {
            throw BookingErrorCode.CHECKOUT_PAYMENT_ID_MISMATCH.exception(ErrorDetail.of("paymentId", paymentId,
                "PAYMENT_ID_MISMATCH"));
        }
        stateService.confirm(saga, clock.instant());
        return new CheckoutAcceptedResponse(saga.getCheckoutId(), bookingId, CheckoutState.VERIFYING, POLL_AFTER_MS);
    }

    /** {@code GET /bookings/{id}/checkout} — 가장 최근 체크아웃. payment·coupon을 부르지 않는다 */
    public CheckoutResponse get(String userId, Long bookingId) {
        Booking booking = ownedBooking(userId, bookingId);
        CheckoutSaga saga = latest(bookingId);
        Instant now = clock.instant();
        boolean retryable = saga.getState().isRetryable()
            && saga.getAttempt() < checkoutProperties.maxAttempts()
            && booking.getStatus() == BookingStatus.PENDING && !booking.isExpiredAt(now);
        return CheckoutResponse.of(saga, booking, retryable, saga.getState().isActive() ? POLL_AFTER_MS : null);
    }

    private CheckoutSaga latest(Long bookingId) {
        return sagas.findFirstByBookingIdOrderByCreatedAtDescCheckoutIdDesc(bookingId)
            .orElseThrow(BookingErrorCode.CHECKOUT_NOT_FOUND::exception);
    }

    private Booking ownedBooking(String userId, Long bookingId) {
        Booking booking = bookingReader.findById(bookingId).orElseThrow(BookingErrorCode.BOOKING_NOT_FOUND::exception);
        if (!booking.isOwnedBy(userId)) {
            throw BookingErrorCode.NOT_OWNER.exception();
        }
        return booking;
    }

    private static com.seatsac.common.core.error.BusinessException inProgress(Long bookingId) {
        return BookingErrorCode.CHECKOUT_IN_PROGRESS.exception(ErrorDetail.of("bookingId", bookingId,
            BookingErrorCode.CHECKOUT_IN_PROGRESS.name()));
    }

    private static String traceId() {
        String current = MDC.get(TraceKeys.MDC_TRACE_ID);
        return TraceIds.isValid(current) ? current : TraceIds.newId();
    }
}

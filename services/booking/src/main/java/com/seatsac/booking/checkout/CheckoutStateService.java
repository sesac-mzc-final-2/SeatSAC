package com.seatsac.booking.checkout;

import com.seatsac.booking.checkout.coupon.CouponPort;
import com.seatsac.booking.checkout.coupon.CouponPort.CouponReply;
import com.seatsac.booking.checkout.coupon.Discount;
import com.seatsac.booking.common.BookingErrorCode;
import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.PaymentMethod;
import com.seatsac.booking.service.BookingStateService;
import com.seatsac.common.core.error.ErrorDetail;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 체크아웃 트랜잭션 전용 — SQL과 아웃박스 INSERT만 한다(외부 I/O 없음). 상태 전이와 명령이 함께 커밋된다.
 * 검증·Catalog 조회는 {@link CheckoutSagaService}가 트랜잭션 밖에서 먼저 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutStateService {

    private final CheckoutSagaRepository sagas;
    private final CheckoutCommands commands;
    private final CouponPort couponPort;
    private final BookingStateService bookingState;
    private final CheckoutProperties properties;

    /** 시작 입력 — 카드 끝 4자리는 CARD(모의)만 */
    /** @param replacingPaymentId 재결제(D25)면 지금 붙은(실패한) 결제 ID — 새 ID로 비교 후 교체한다. 처음이면 null */
    public record Begin(String checkoutId, Booking booking, String userId, PaymentMethod method, int attempt,
                        String couponIssueId, String cardLast4, String traceId, String replacingPaymentId) {
    }

    /**
     * 설계 01 5-1 — saga 행 + 첫 명령을 한 트랜잭션에. 진행 중 체크아웃이 이미 있으면 유일 키
     * ({@code uk_checkout_active_booking})가 막는다({@link org.springframework.dao.DataIntegrityViolationException}).
     *
     * @return 이번에 들어간 상태(COUPON_RESERVING·PAYMENT_PREPARING·CHARGING·COUPON_REJECTED)
     */
    @Transactional
    public CheckoutState begin(Begin begin, Instant now) {
        Booking booking = begin.booking();
        CheckoutSaga saga = sagas.saveAndFlush(CheckoutSaga.started(begin.checkoutId(), booking.getBookingId(),
            begin.userId(), begin.method(), begin.attempt(), begin.couponIssueId(), booking.getAmount(),
            begin.traceId(), now));

        if (begin.couponIssueId() == null) {
            return startPayment(saga, booking, Discount.NONE, begin.cardLast4(), begin.replacingPaymentId(), now);
        }
        Optional<CouponReply> reply = couponPort.reserve(saga.getCheckoutId(), booking.getBookingId(),
            begin.userId(), begin.couponIssueId(), booking.getAmount());
        if (reply.isEmpty()) {
            sagas.advance(saga.getCheckoutId(), EnumSet.of(CheckoutState.STARTED), CheckoutState.COUPON_RESERVING,
                "coupon_requested", now.plus(properties.stepTimeout()), now);
            return CheckoutState.COUPON_RESERVING;
        }
        return switch (reply.get()) {
            case CouponPort.Reserved reserved ->
                startPayment(saga, booking, reserved.discount(), begin.cardLast4(), begin.replacingPaymentId(), now);
            case CouponPort.Rejected rejected -> {
                // 잡힌 게 없다 — 보상 없음. 예매·좌석·선점 시간 그대로(D14)
                sagas.finish(saga.getCheckoutId(), EnumSet.of(CheckoutState.STARTED), CheckoutState.COUPON_REJECTED,
                    "coupon_rejected", "COUPON_NOT_USABLE", rejected.reason(), null, now);
                yield CheckoutState.COUPON_REJECTED;
            }
        };
    }

    /**
     * 결제 명령을 처음 보내는 순간에 paymentId를 붙인다(D14 — 쿠폰 단계에서 붙이면 만료가 유예만큼 밀린다).
     * 붙이지 못하면(동기 경로가 그 사이 붙였다) 트랜잭션 전체를 되돌린다 → 409 {@code PAYMENT_PATH_CONFLICT}.
     */
    CheckoutState startPayment(CheckoutSaga saga, Booking booking, Discount discount, String cardLast4,
                               String replacingPaymentId, Instant now) {
        int original = saga.getOriginalAmount();
        int discountAmount = discount.apply(original);
        int finalAmount = original - discountAmount;
        if (finalAmount < 1) {
            // 할인율 상한 99%(conventions 6장) — 0원 결제는 없다. coupon 쪽 버그라 결제를 만들지 않는다
            throw new IllegalStateException("최종 금액이 1원 미만이다 checkoutId=" + saga.getCheckoutId());
        }
        String paymentId = UUID.randomUUID().toString();
        boolean attached = replacingPaymentId == null
            ? bookingState.attachPayment(booking.getBookingId(), paymentId, now)
            : bookingState.replacePayment(booking.getBookingId(), replacingPaymentId, paymentId, now);
        if (!attached) {
            throw BookingErrorCode.PAYMENT_PATH_CONFLICT.exception(ErrorDetail.of("bookingId", booking.getBookingId(),
                BookingErrorCode.PAYMENT_PATH_CONFLICT.name()));
        }
        boolean card = saga.getMethod() == PaymentMethod.CARD;
        CheckoutState next = card ? CheckoutState.CHARGING : CheckoutState.PAYMENT_PREPARING;
        Instant deadline = now.plus(card ? CheckoutProperties.VERIFY_INTERVAL : properties.stepTimeout());
        String kind = discount.kind() == Discount.Kind.NONE ? null : discount.kind().name();
        Integer value = discount.kind() == Discount.Kind.NONE ? null : discount.value();
        sagas.startPayment(saga.getCheckoutId(), EnumSet.of(CheckoutState.STARTED, CheckoutState.COUPON_RESERVING),
            next, card ? "charge_requested" : "payment_requested", deadline, paymentId, kind, value, discountAmount,
            finalAmount, now);
        if (card) {
            commands.charge(saga, paymentId, finalAmount, cardLast4, now);
        } else {
            commands.preparation(saga, paymentId, finalAmount, booking.getSeatCount(), now);
        }
        return next;
    }

    /** 설계 01 5-3 — READY_TO_PAY → VERIFYING + 첫 확인 명령. 0행이면 409 {@code CHECKOUT_NOT_READY} */
    @Transactional
    public void confirm(CheckoutSaga saga, Instant now) {
        if (sagas.startVerify(saga.getCheckoutId(), now.plus(CheckoutProperties.VERIFY_INTERVAL), now) == 0) {
            CheckoutState current = sagas.findById(saga.getCheckoutId()).map(CheckoutSaga::getState)
                .orElse(saga.getState());
            throw BookingErrorCode.CHECKOUT_NOT_READY.exception(ErrorDetail.of("state", current.name(),
                BookingErrorCode.CHECKOUT_NOT_READY.name()));
        }
        commands.verification(saga, 1, now);
    }
}

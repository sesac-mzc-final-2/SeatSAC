package com.seatsac.booking.checkout;

import com.seatsac.booking.checkout.dto.CheckoutAcceptedResponse;
import com.seatsac.booking.checkout.dto.CheckoutConfirmRequest;
import com.seatsac.booking.checkout.dto.CheckoutRequest;
import com.seatsac.booking.checkout.dto.CheckoutResponse;
import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.idempotency.Idempotent;
import com.seatsac.common.core.http.Headers;
import com.seatsac.common.web.user.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 체크아웃 saga API 3개 — 계약 {@code booking.openapi.yaml} tag {@code checkout}(#176).
 * {@code APP_CHECKOUT_SAGA_ENABLED=false}(기본)면 컨트롤러가 없어 세 경로가 404다.
 * 시작·확인은 {@code @Idempotent} — 같은 키의 재요청에 첫 202를 그대로 돌려준다(쿠폰 거절 뒤 다시 시작은 새 키).
 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.checkout", name = "saga-enabled", havingValue = "true")
public class CheckoutController {

    private final CheckoutSagaService checkoutService;

    @Idempotent
    @PostMapping("/bookings/{bookingId}/checkout")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CheckoutAcceptedResponse start(@CurrentUser String userId, @PathVariable Long bookingId,
                                          @RequestHeader(Headers.IDEMPOTENCY_KEY) String idempotencyKey,
                                          @RequestHeader(value = BookingPolicy.MOCK_PAYMENT_KEY_HEADER,
                                              required = false) String mockKey,
                                          @Valid @RequestBody CheckoutRequest request) {
        return checkoutService.start(userId, mockKey, bookingId, request);
    }

    @GetMapping("/bookings/{bookingId}/checkout")
    public CheckoutResponse get(@CurrentUser String userId, @PathVariable Long bookingId) {
        return checkoutService.get(userId, bookingId);
    }

    @Idempotent
    @PostMapping("/bookings/{bookingId}/checkout/confirm")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CheckoutAcceptedResponse confirm(@CurrentUser String userId, @PathVariable Long bookingId,
                                            @RequestHeader(Headers.IDEMPOTENCY_KEY) String idempotencyKey,
                                            @Valid @RequestBody CheckoutConfirmRequest request) {
        return checkoutService.confirm(userId, bookingId, request.paymentId());
    }
}

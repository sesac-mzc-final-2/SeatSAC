package com.seatsac.booking.component;

import com.seatsac.booking.common.BookingErrorCode;
import com.seatsac.booking.enums.PaymentMethod;
import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.CommonResponseCode;
import com.seatsac.common.core.error.ErrorDetail;
import com.seatsac.common.web.client.InternalRestClientFactory;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Payment 내부 호출 — 연결 1초·응답 3초, 서킷 실패율 50%·10초 (ADR-23·32).
 *
 * <p><b>결제는 한 번만 일어나야 한다.</b> 그래서 재시도 규칙이 좁다 — 응답을 못 받은 경우
 * (타임아웃·연결 오류·상대 5xx)에만 <b>같은 {@code paymentId}로 1회</b> 다시 부르고, 그래도
 * 모르면 조회로 확인한다. Payment가 행을 먼저 만들어 두기 때문에 같은 ID로 다시 불러도
 * 결제가 두 번 일어나지 않고 현재 상태({@code PROCESSING} 또는 결과)를 돌려준다.
 *
 * <p>끝까지 결과를 모르면 503 {@code PAYMENT_RESULT_UNKNOWN}이다 — 예매는 {@code PENDING}으로
 * 남고 화면이 단건 조회로 재확인하며, 만료 처리(BKG-12)가 끝까지 정리한다. <b>모르는 상태를
 * 「실패」로 단정하지 않는 것</b>이 핵심이다: 승인된 결제를 실패로 처리하면 돈은 빠져나갔는데
 * 좌석이 풀린다.
 */
@Slf4j
@Component
public class PaymentClient {

    private final RestClient payment;
    private final MeterRegistry meterRegistry;

    public PaymentClient(InternalRestClientFactory factory,
                         @Value("${app.services.payment-url}") String paymentUrl,
                         MeterRegistry meterRegistry) {
        this.payment = factory.create(paymentUrl);
        this.meterRegistry = meterRegistry;
    }

    /**
     * PAY-01 — 모의 결제 승인. 거절도 정상 응답이다(결과가 {@code FAILED}).
     *
     * @throws BusinessException 503 {@code PAYMENT_RESULT_UNKNOWN} 끝까지 결과를 모를 때,
     *                           503 {@code PAYMENT_UNAVAILABLE} 서킷이 열렸을 때
     */
    @CircuitBreaker(name = "payment")
    public PaymentResult request(PaymentRequest request) {
        Attempt attempt = post(request);
        if (attempt.noResponse()) {
            // 응답을 못 받았다. 같은 paymentId로 1회만 다시 — Payment는 행을 먼저 만들어 둔다
            log.warn("결제 응답을 받지 못해 같은 결제 ID로 1회 재호출한다 paymentId={} bookingId={}",
                request.paymentId(), request.bookingId());
            attempt = post(request);
        }
        if (attempt.noResponse()) {
            // 재호출도 실패 → 조회로 확인한다 (결제가 됐는지 모르는 채 끝내지 않는다)
            attempt = Attempt.of(find(request.paymentId()).orElse(null));
        }

        PaymentResult result = attempt.result();
        if (result == null || result.isProcessing()) {
            count("timeout");
            throw BookingErrorCode.PAYMENT_RESULT_UNKNOWN.exception(
                ErrorDetail.of("paymentId", request.paymentId()));
        }
        count(result.isApproved() ? "approved" : "declined");
        return result;
    }

    /** 결제 상태 조회 — 결과 재확인·만료 처리. 없으면 비어 있음(결제 요청이 닿지 않았다) */
    @CircuitBreaker(name = "payment")
    public Optional<PaymentResult> find(String paymentId) {
        try {
            return Optional.ofNullable(payment.get()
                .uri("/internal/payments/{paymentId}", paymentId)
                .retrieve()
                .onStatus(PaymentClient::isNotFound, (req, res) -> {
                    throw new PaymentNotFoundException();
                })
                .body(PaymentResult.class));
        } catch (PaymentNotFoundException e) {
            return Optional.empty();
        } catch (BusinessException e) {
            // 조회 실패는 「모른다」다. 부르는 쪽이 보류·503을 고른다
            log.warn("결제 조회 실패 paymentId={}", paymentId);
            return Optional.empty();
        }
    }

    /**
     * PAY-03 — 모의 환불. 멱등이므로 같은 결제를 두 번 취소해도 된다.
     * 실패하면 503 {@code PAYMENT_UNAVAILABLE}로 올려 화면·만료 처리가 다시 시도하게 한다.
     */
    @CircuitBreaker(name = "payment")
    public PaymentResult cancel(String paymentId, PaymentCancelReason reason) {
        try {
            // 취소 성공은 booking_payment_call_total로 세지 않는다 — 그 지표의 라벨은
            // approved·declined·timeout·error 네 개로 정해져 있고 자동 롤백이 그대로 쓴다
            return payment.post()
                .uri("/internal/payments/{paymentId}/cancel", paymentId)
                .body(new CancelRequest(reason))
                .retrieve()
                .body(PaymentResult.class);
        } catch (BusinessException e) {
            count("error");
            throw unavailable(e);
        }
    }

    /**
     * 한 번의 POST 결과.
     *
     * @param result     받은 결과 (없을 수 있다)
     * @param noResponse 응답 자체를 못 받았는가 — <b>이때만</b> 재호출한다.
     *                   409처럼 「응답은 받았고 조회로도 결과를 못 찾은」 경우는 재호출해도 같다
     */
    private record Attempt(PaymentResult result, boolean noResponse) {

        private static Attempt of(PaymentResult result) {
            return new Attempt(result, false);
        }

        private static Attempt unavailable() {
            return new Attempt(null, true);
        }
    }

    private Attempt post(PaymentRequest request) {
        try {
            return Attempt.of(payment.post().uri("/internal/payments").body(request).retrieve()
                .body(PaymentResult.class));
        } catch (HttpClientErrorException.Conflict e) {
            // 같은 예매에 이미 결제가 있다(PAYMENT_CONFLICT). 결과는 조회로 알 수 있으므로
            // 모른다고 끝내지 않는다 — 우리 상태와 payment 상태가 어긋난 경우다.
            // 조회로도 못 찾으면 재호출하지 않는다(또 409다) → 결과 미확인으로 끝낸다
            log.warn("결제 요청이 409 — 조회로 현재 상태를 확인한다 paymentId={} bookingId={}",
                request.paymentId(), request.bookingId());
            return Attempt.of(find(request.paymentId()).orElse(null));
        } catch (HttpClientErrorException e) {
            // 400 등 — 우리 요청이 잘못됐다. 재시도해도 같으니 그대로 올린다
            count("error");
            log.error("결제 요청이 거부됐다 status={} paymentId={}", e.getStatusCode(),
                request.paymentId());
            throw e;
        } catch (BusinessException e) {
            if (isUnavailable(e)) {
                return Attempt.unavailable();   // 타임아웃·연결 오류·상대 5xx
            }
            count("error");
            throw e;
        }
    }

    private static boolean isUnavailable(BusinessException e) {
        return CommonResponseCode.SERVICE_UNAVAILABLE.name().equals(e.code().responseCode());
    }

    /** 서킷이 열렸거나 취소가 실패한 경우 — 503 {@code PAYMENT_UNAVAILABLE} */
    public static BusinessException unavailable(Throwable cause) {
        if (cause instanceof BusinessException business && !isUnavailable(business)) {
            return business;   // 4xx·409 등은 그대로 올린다
        }
        return BookingErrorCode.PAYMENT_UNAVAILABLE.exception();
    }

    private void count(String result) {
        meterRegistry.counter("booking_payment_call_total", "result", result).increment();
    }

    private static boolean isNotFound(HttpStatusCode status) {
        return status.value() == 404;
    }

    /** 404를 흐름 제어로만 쓴다 — 스택 트레이스를 만들지 않는다 */
    private static final class PaymentNotFoundException extends RuntimeException {
        private PaymentNotFoundException() {
            super(null, null, false, false);
        }
    }

    /** PAY-01 요청 — 카드는 payment가 끝 4자리만 저장한다 */
    public record PaymentRequest(String paymentId, Long bookingId, Integer amount,
                                 PaymentMethod method, Card card) {

        /** 카드 정보가 로그에 찍히지 않게 가린다 */
        @Override
        public String toString() {
            return "PaymentRequest(paymentId=" + paymentId + ", bookingId=" + bookingId
                + ", amount=" + amount + ", method=" + method + ", card=****)";
        }

        public record Card(String number, String expiry, String holder) {
            @Override
            public String toString() {
                return "Card(****)";
            }
        }
    }

    /** 취소 사유 — payment 계약의 {@code CancelReason} */
    public enum PaymentCancelReason {
        HOLD_EXPIRED,
        BOOKING_CANCELED,
        STALE_PAYMENT
    }

    private record CancelRequest(PaymentCancelReason reason) {
    }

    /**
     * 쓰는 필드만 받는다(모르는 필드는 무시). 시각은 응답의 {@code +09:00}을 그대로 읽는다.
     *
     * <p>{@code failureCode}는 payment의 내부 코드다({@code CARD_DECLINED}·
     * {@code INSUFFICIENT_BALANCE}·{@code PROCESSING_TIMEOUT}). <b>외부 응답으로 그대로 흘리지
     * 않는다</b> — booking 계약({@code PayResult}·{@code Booking})의 {@code failureCode}는 앞의 두
     * 값과 null만 허용하므로, 3.2.6에서 응답을 만들 때 그 밖의 값은 null로 바꿔 「결제 실패」로만
     * 보여 준다({@link #publicFailureCode}).
     */
    public record PaymentResult(
        String paymentId,
        Long bookingId,
        Integer amount,
        String status,
        String cardLast4,
        String failureCode,
        String cancelReason,
        Instant approvedAt,
        Instant canceledAt
    ) {
        public boolean isApproved() {
            return "APPROVED".equals(status);
        }

        public boolean isProcessing() {
            return "PROCESSING".equals(status);
        }

        public boolean isCanceled() {
            return "CANCELED".equals(status);
        }

        /**
         * 화면에 보여도 되는 거절 사유만 남긴다. {@code PROCESSING_TIMEOUT} 같은 운영용 코드는
         * null이 되어 화면은 「결제 실패」로만 표시한다(booking 계약의 enum을 넓히지 않는 이유).
         */
        public String publicFailureCode() {
            // Set.of(...)의 contains(null)은 NPE다. 승인 건은 failureCode가 null이다
            if (failureCode == null) {
                return null;
            }
            return PUBLIC_FAILURE_CODES.contains(failureCode) ? failureCode : null;
        }
    }

    /** booking 외부 응답에 담아도 되는 거절 사유 (계약 {@code FailureCode}) */
    private static final Set<String> PUBLIC_FAILURE_CODES =
        Set.of("CARD_DECLINED", "INSUFFICIENT_BALANCE");
}

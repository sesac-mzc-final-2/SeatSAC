package com.seatsac.booking.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatsac.booking.component.PaymentClient.PaymentCancelReason;
import com.seatsac.booking.component.PaymentClient.PaymentRequest;
import com.seatsac.booking.component.PaymentClient.PaymentResult;
import com.seatsac.booking.enums.PaymentMethod;
import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.CommonResponseCode;
import com.seatsac.common.test.StubHttpServer;
import com.seatsac.common.web.client.InternalRestClientFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Payment 호출 규약 (명세 5장 BKG-10 3단계) — <b>결제는 한 번만 일어나야 한다</b>는 제약 때문에
 * 재시도 횟수와 「모르는 상태」 처리가 핵심이다.
 *
 * <p>가짜 payment는 공용 {@link StubHttpServer}로 띄운다 — 응답을 미리 넣어 두고 <b>요청 수를
 * 세어</b> 「같은 결제 ID로 1회만 재호출」을 확인한다. 읽기 타임아웃은 응답을 늦게 보내 만든다.
 */
class PaymentClientTest {

    private static final String PAYMENT_ID = "3f2b8c1e-7a4d-4e9b-9c61-2d5f0a8e7b13";
    private static final Long BOOKING = 5001L;

    private StubHttpServer payment;
    private PaymentClient client;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void startFakePayment() throws IOException {
        // 지연으로 읽기 타임아웃을 만들므로 동시 처리 스텁이다 — 한 스레드면 4초 지연이
        // 다음 요청까지 막아 재시도 검증이 시간 여유에 기댄다
        payment = StubHttpServer.concurrent("/internal/payments",
            request -> "{\"code\":\"PAYMENT_CONFLICT\",\"details\":[]}");
        payment.status = 500;   // 쌓아 둔 응답을 다 쓴 뒤의 기본값

        meterRegistry = new SimpleMeterRegistry();
        client = new PaymentClient(new InternalRestClientFactory(), payment.baseUrl(), meterRegistry);
    }

    @AfterEach
    void stopFakePayment() {
        payment.close();
    }

    /** POST 요청 수 — 「같은 결제 ID로 1회만 재호출」을 센다 (경로 끝이 /cancel인 것은 제외) */
    private long postCount() {
        return payment.received.stream()
            .filter(r -> "POST".equals(r.method()) && !r.path().endsWith("/cancel"))
            .count();
    }

    private String lastRequest() {
        var last = payment.received.get(payment.received.size() - 1);
        return last.method() + " " + last.path();
    }

    private static StubHttpServer.Reply ok(String body) {
        return StubHttpServer.Reply.ok(body);
    }

    /** 읽기 타임아웃 3초를 넘겨 「응답을 못 받은 경우」를 만든다 */
    private static StubHttpServer.Reply slow(String body) {
        return StubHttpServer.Reply.ok(body).after(4_000);
    }

    private static StubHttpServer.Reply status(int status) {
        return new StubHttpServer.Reply(status, "{\"code\":\"PAYMENT_CONFLICT\",\"details\":[]}", 0);
    }

    private static String paymentJson(String status) {
        return """
            {"paymentId":"%s","bookingId":5001,"amount":30000,"method":"CARD","status":"%s",
             "cardLast4":"1112","failureCode":null,"cancelReason":null,
             "approvedAt":"2026-10-23T12:03:00+09:00","canceledAt":null,
             "createdAt":"2026-10-23T12:02:59+09:00"}
            """.formatted(PAYMENT_ID, status);
    }

    private static PaymentRequest request() {
        return new PaymentRequest(PAYMENT_ID, BOOKING, 30_000, PaymentMethod.CARD,
            new PaymentRequest.Card("4111111111111112", "12/28", "KIM"));
    }

    private double metric(String result) {
        var counter = meterRegistry.find("booking_payment_call_total").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    // --- 정상 ---

    @Test
    void approvedResultIsReturnedAndCounted() {
        payment.enqueue(ok(paymentJson("APPROVED")));

        PaymentResult result = client.request(request());

        assertThat(result.isApproved()).isTrue();
        assertThat(result.cardLast4()).isEqualTo("1112");
        assertThat(result.approvedAt()).isNotNull();
        assertThat(postCount()).isEqualTo(1);              // 재시도하지 않는다
        assertThat(metric("approved")).isEqualTo(1);
    }

    /** 거절은 정상 응답이다 — 자동 롤백 계산에서 빠지는 라벨로 센다 */
    @Test
    void declinedResultIsNotAFailure() {
        payment.enqueue(ok(paymentJson("FAILED")));

        PaymentResult result = client.request(request());

        assertThat(result.isApproved()).isFalse();
        assertThat(metric("declined")).isEqualTo(1);
        assertThat(metric("timeout")).isZero();
    }

    /**
     * payment의 내부 코드는 화면에 흘리지 않는다 — booking 계약의 {@code failureCode}는
     * 거절 사유 두 개와 null만 허용한다(그래서 계약 enum을 넓히지 않았다).
     */
    @Test
    void internalFailureCodesAreNotExposedToClients() {
        payment.enqueue(ok(paymentJson("FAILED").replace("\"failureCode\":null",
            "\"failureCode\":\"PROCESSING_TIMEOUT\"")));

        PaymentResult result = client.request(request());

        assertThat(result.failureCode()).isEqualTo("PROCESSING_TIMEOUT");   // 내부에서는 보인다
        assertThat(result.publicFailureCode()).isNull();                    // 응답에는 담지 않는다
    }

    /** 승인 건은 failureCode가 null이다 — Set.of(...).contains(null)이 NPE라 먼저 걸러야 한다 */
    @Test
    void approvedPaymentHasNoFailureCode() {
        payment.enqueue(ok(paymentJson("APPROVED")));

        PaymentResult result = client.request(request());

        assertThat(result.failureCode()).isNull();
        assertThat(result.publicFailureCode()).isNull();   // NPE 없이 null
    }

    @Test
    void declineReasonsAreExposedToClients() {
        payment.enqueue(ok(paymentJson("FAILED").replace("\"failureCode\":null",
            "\"failureCode\":\"CARD_DECLINED\"")));

        assertThat(client.request(request()).publicFailureCode()).isEqualTo("CARD_DECLINED");
    }

    // --- 응답을 못 받은 경우 ---

    /** 타임아웃 → 같은 결제 ID로 1회 재호출 → 결과를 받으면 그것이 답이다 */
    @Test
    void timeoutRetriesOnceWithTheSamePaymentId() {
        payment.enqueue(slow(paymentJson("APPROVED")));
        payment.enqueue(ok(paymentJson("APPROVED")));

        PaymentResult result = client.request(request());

        assertThat(result.isApproved()).isTrue();
        assertThat(postCount()).isEqualTo(2);              // 1회만 더
        assertThat(result.paymentId()).isEqualTo(PAYMENT_ID);
    }

    /** 재호출도 실패하면 조회로 확인한다 */
    @Test
    void secondFailureFallsBackToLookup() {
        payment.enqueue(slow(paymentJson("APPROVED")));
        payment.enqueue(slow(paymentJson("APPROVED")));
        payment.enqueue(ok(paymentJson("APPROVED")));   // GET

        PaymentResult result = client.request(request());

        assertThat(result.isApproved()).isTrue();
        assertThat(postCount()).isEqualTo(2);
        assertThat(lastRequest()).startsWith("GET ");
    }

    /** PROCESSING은 「모른다」다 — 실패로 단정하지 않고 503으로 올린다 (예매는 PENDING 유지) */
    @Test
    void processingResultIsReportedAsUnknown() {
        payment.enqueue(ok(paymentJson("PROCESSING")));

        assertThatThrownBy(() -> client.request(request()))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.PAYMENT_RESULT_UNKNOWN.name());

        assertThat(metric("timeout")).isEqualTo(1);
    }

    @Test
    void unknownUntilTheEndIsReportedAsUnknown() {
        payment.enqueue(slow(paymentJson("APPROVED")));
        payment.enqueue(slow(paymentJson("APPROVED")));
        payment.enqueue(status(500));                   // 조회도 실패

        assertThatThrownBy(() -> client.request(request()))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.PAYMENT_RESULT_UNKNOWN.name());
    }

    // --- 4xx ---

    /** 같은 예매에 결제가 이미 있다(409) → 조회로 현재 상태를 알아낸다 */
    @Test
    void conflictIsResolvedByLookingUpTheCurrentState() {
        payment.enqueue(status(409));
        payment.enqueue(ok(paymentJson("APPROVED")));

        PaymentResult result = client.request(request());

        assertThat(result.isApproved()).isTrue();
        assertThat(postCount()).isEqualTo(1);              // 409에는 재호출하지 않는다
    }

    /** 409 뒤 조회로도 못 찾으면 <b>재호출하지 않는다</b> — 또 409다. 결과 미확인으로 끝낸다 */
    @Test
    void conflictWithoutLookupResultDoesNotRetryThePost() {
        payment.enqueue(status(409));
        payment.enqueue(status(404));   // 조회도 없음

        assertThatThrownBy(() -> client.request(request()))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.PAYMENT_RESULT_UNKNOWN.name());

        assertThat(postCount()).isEqualTo(1);
    }

    /** 400은 우리 요청이 잘못된 것이다 — 재시도하지 않고 그대로 올린다 */
    @Test
    void badRequestIsNotRetried() {
        payment.enqueue(status(400));

        assertThatThrownBy(() -> client.request(request()))
            .isInstanceOf(HttpClientErrorException.class);

        assertThat(postCount()).isEqualTo(1);
        assertThat(metric("error")).isEqualTo(1);
    }

    // --- 조회 ---

    @Test
    void lookupReturnsEmptyWhenPaymentDoesNotExist() {
        payment.enqueue(status(404));

        Optional<PaymentResult> result = client.find(UUID.randomUUID().toString());

        assertThat(result).isEmpty();
    }

    @Test
    void lookupReturnsEmptyWhenPaymentIsUnreachable() {
        payment.enqueue(status(500));

        assertThat(client.find(PAYMENT_ID)).isEmpty();
    }

    // --- 취소 ---

    @Test
    void cancelReturnsCanceledPayment() {
        payment.enqueue(ok(paymentJson("CANCELED")));

        PaymentResult result = client.cancel(PAYMENT_ID, PaymentCancelReason.HOLD_EXPIRED);

        assertThat(result.isCanceled()).isTrue();
        assertThat(lastRequest()).isEqualTo("POST /internal/payments/" + PAYMENT_ID + "/cancel");
    }

    /** 취소 실패는 503으로 올린다 — 화면·만료 처리가 다시 시도한다 */
    @Test
    void cancelFailureIsServiceUnavailable() {
        payment.enqueue(status(500));

        assertThatThrownBy(() -> client.cancel(PAYMENT_ID, PaymentCancelReason.STALE_PAYMENT))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.SERVICE_UNAVAILABLE.name());

        assertThat(metric("error")).isEqualTo(1);
    }
}

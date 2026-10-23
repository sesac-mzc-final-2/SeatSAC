package com.seatsac.booking.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatsac.booking.component.PaymentClient.PaymentCancelReason;
import com.seatsac.booking.component.PaymentClient.PaymentRequest;
import com.seatsac.booking.component.PaymentClient.PaymentResult;
import com.seatsac.booking.enums.PaymentMethod;
import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.CommonResponseCode;
import com.seatsac.common.web.client.InternalRestClientFactory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Payment 호출 규약 (명세 5장 BKG-10 3단계) — <b>결제는 한 번만 일어나야 한다</b>는 제약 때문에
 * 재시도 횟수와 「모르는 상태」 처리가 핵심이다.
 *
 * <p>가짜 payment는 JDK 내장 {@link HttpServer}로 띄운다 — 응답을 미리 넣어 두고 <b>요청 수를
 * 세어</b> 「같은 결제 ID로 1회만 재호출」을 확인한다. 읽기 타임아웃은 응답을 늦게 보내 만든다.
 */
class PaymentClientTest {

    private static final String PAYMENT_ID = "3f2b8c1e-7a4d-4e9b-9c61-2d5f0a8e7b13";
    private static final Long BOOKING = 5001L;

    private HttpServer server;
    private PaymentClient client;
    private SimpleMeterRegistry meterRegistry;

    /** 미리 넣어 둔 응답을 순서대로 내보낸다 */
    private final Deque<Response> responses = new ArrayDeque<>();
    private final List<String> requestedPaths = new ArrayList<>();
    private final AtomicInteger postCount = new AtomicInteger();

    private record Response(int status, String body, long delayMillis) {

        private static Response ok(String body) {
            return new Response(200, body, 0);
        }

        private static Response slow(String body) {
            return new Response(200, body, 4_000);   // 읽기 타임아웃 3초를 넘긴다
        }

        private static Response status(int status) {
            return new Response(status, "{\"code\":\"PAYMENT_CONFLICT\",\"details\":[]}", 0);
        }
    }

    @BeforeEach
    void startFakePayment() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/payments", this::handle);
        server.start();

        meterRegistry = new SimpleMeterRegistry();
        client = new PaymentClient(new InternalRestClientFactory(),
            "http://127.0.0.1:" + server.getAddress().getPort(), meterRegistry);
    }

    @AfterEach
    void stopFakePayment() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestedPaths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
        if ("POST".equals(exchange.getRequestMethod())
            && !exchange.getRequestURI().getPath().endsWith("/cancel")) {
            postCount.incrementAndGet();
        }
        Response response = responses.isEmpty() ? Response.status(500) : responses.poll();
        if (response.delayMillis() > 0) {
            try {
                Thread.sleep(response.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
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
        responses.add(Response.ok(paymentJson("APPROVED")));

        PaymentResult result = client.request(request());

        assertThat(result.isApproved()).isTrue();
        assertThat(result.cardLast4()).isEqualTo("1112");
        assertThat(result.approvedAt()).isNotNull();
        assertThat(postCount).hasValue(1);              // 재시도하지 않는다
        assertThat(metric("approved")).isEqualTo(1);
    }

    /** 거절은 정상 응답이다 — 자동 롤백 계산에서 빠지는 라벨로 센다 */
    @Test
    void declinedResultIsNotAFailure() {
        responses.add(Response.ok(paymentJson("FAILED")));

        PaymentResult result = client.request(request());

        assertThat(result.isApproved()).isFalse();
        assertThat(metric("declined")).isEqualTo(1);
        assertThat(metric("timeout")).isZero();
    }

    // --- 응답을 못 받은 경우 ---

    /** 타임아웃 → 같은 결제 ID로 1회 재호출 → 결과를 받으면 그것이 답이다 */
    @Test
    void timeoutRetriesOnceWithTheSamePaymentId() {
        responses.add(Response.slow(paymentJson("APPROVED")));
        responses.add(Response.ok(paymentJson("APPROVED")));

        PaymentResult result = client.request(request());

        assertThat(result.isApproved()).isTrue();
        assertThat(postCount).hasValue(2);              // 1회만 더
        assertThat(result.paymentId()).isEqualTo(PAYMENT_ID);
    }

    /** 재호출도 실패하면 조회로 확인한다 */
    @Test
    void secondFailureFallsBackToLookup() {
        responses.add(Response.slow(paymentJson("APPROVED")));
        responses.add(Response.slow(paymentJson("APPROVED")));
        responses.add(Response.ok(paymentJson("APPROVED")));   // GET

        PaymentResult result = client.request(request());

        assertThat(result.isApproved()).isTrue();
        assertThat(postCount).hasValue(2);
        assertThat(requestedPaths).last().asString().startsWith("GET ");
    }

    /** PROCESSING은 「모른다」다 — 실패로 단정하지 않고 503으로 올린다 (예매는 PENDING 유지) */
    @Test
    void processingResultIsReportedAsUnknown() {
        responses.add(Response.ok(paymentJson("PROCESSING")));

        assertThatThrownBy(() -> client.request(request()))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.PAYMENT_RESULT_UNKNOWN.name());

        assertThat(metric("timeout")).isEqualTo(1);
    }

    @Test
    void unknownUntilTheEndIsReportedAsUnknown() {
        responses.add(Response.slow(paymentJson("APPROVED")));
        responses.add(Response.slow(paymentJson("APPROVED")));
        responses.add(Response.status(500));                   // 조회도 실패

        assertThatThrownBy(() -> client.request(request()))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.PAYMENT_RESULT_UNKNOWN.name());
    }

    // --- 4xx ---

    /** 같은 예매에 결제가 이미 있다(409) → 조회로 현재 상태를 알아낸다 */
    @Test
    void conflictIsResolvedByLookingUpTheCurrentState() {
        responses.add(Response.status(409));
        responses.add(Response.ok(paymentJson("APPROVED")));

        PaymentResult result = client.request(request());

        assertThat(result.isApproved()).isTrue();
        assertThat(postCount).hasValue(1);              // 409에는 재호출하지 않는다
    }

    /** 400은 우리 요청이 잘못된 것이다 — 재시도하지 않고 그대로 올린다 */
    @Test
    void badRequestIsNotRetried() {
        responses.add(Response.status(400));

        assertThatThrownBy(() -> client.request(request()))
            .isInstanceOf(HttpClientErrorException.class);

        assertThat(postCount).hasValue(1);
        assertThat(metric("error")).isEqualTo(1);
    }

    // --- 조회 ---

    @Test
    void lookupReturnsEmptyWhenPaymentDoesNotExist() {
        responses.add(Response.status(404));

        Optional<PaymentResult> result = client.find(UUID.randomUUID().toString());

        assertThat(result).isEmpty();
    }

    @Test
    void lookupReturnsEmptyWhenPaymentIsUnreachable() {
        responses.add(Response.status(500));

        assertThat(client.find(PAYMENT_ID)).isEmpty();
    }

    // --- 취소 ---

    @Test
    void cancelReturnsCanceledPayment() {
        responses.add(Response.ok(paymentJson("CANCELED")));

        PaymentResult result = client.cancel(PAYMENT_ID, PaymentCancelReason.HOLD_EXPIRED);

        assertThat(result.isCanceled()).isTrue();
        assertThat(requestedPaths).last().asString().endsWith("/cancel");
    }

    /** 취소 실패는 503으로 올린다 — 화면·만료 처리가 다시 시도한다 */
    @Test
    void cancelFailureIsServiceUnavailable() {
        responses.add(Response.status(500));

        assertThatThrownBy(() -> client.cancel(PAYMENT_ID, PaymentCancelReason.STALE_PAYMENT))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).code().responseCode())
            .isEqualTo(CommonResponseCode.SERVICE_UNAVAILABLE.name());

        assertThat(metric("error")).isEqualTo(1);
    }
}

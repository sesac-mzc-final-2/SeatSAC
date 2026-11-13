package com.seatsac.payment.portone;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 포트원 V2 REST를 대신하는 JDK HttpServer — {@code GET /payments/{id}}, {@code POST /payments/{id}/cancel}만.
 * 테스트가 결제 상태({@link #payments})와 취소 응답({@link #cancelStatus}·{@link #cancelBody})을 정하고,
 * 받은 요청을 기록한다. 포트원에 가는 것은 전부 이 서버로 와야 한다(테스트 프로필 기본 주소는 닿지 않는 포트).
 */
public final class FakePortOne implements AutoCloseable {

    /** 포트원 쪽 결제 하나 — 우리가 읽는 필드만 */
    public record Remote(String status, long total, String currency, String transactionId, String failureReason) {
        public static Remote paid(long total) {
            return new Remote("PAID", total, "KRW", "tx-" + total, null);
        }

        public static Remote ready() {
            return new Remote("READY", 0, "KRW", null, null);
        }

        public static Remote failed(String reason) {
            return new Remote("FAILED", 0, "KRW", null, reason);
        }

        public static Remote cancelled(long total) {
            return new Remote("CANCELLED", total, "KRW", "tx-" + total, null);
        }

        String json() {
            String failure = failureReason == null ? "null"
                : "{\"reason\":\"" + failureReason + "\",\"pgCode\":\"X\",\"pgMessage\":\"" + failureReason + "\"}";
            return "{\"status\":\"" + status + "\",\"id\":\"p\",\"transactionId\":"
                + (transactionId == null ? "null" : "\"" + transactionId + "\"")
                + ",\"amount\":{\"total\":" + total + ",\"paid\":" + total + ",\"cancelled\":0},\"currency\":\"" + currency
                + "\",\"failure\":" + failure + ",\"ignoredField\":1}";
        }
    }

    public record Received(String method, String path, Map<String, List<String>> headers, String body) {
    }

    public final Map<String, Remote> payments = new ConcurrentHashMap<>();
    public final List<Received> received = new CopyOnWriteArrayList<>();
    /** GET 응답 상태를 강제로 바꿀 때(500 등). 0이면 정상 */
    public volatile int getStatus = 0;
    /** 취소 응답. 기본은 SUCCEEDED이고 성공하면 결제 상태를 CANCELLED로 바꾼다 */
    public volatile int cancelStatus = 200;
    public volatile String cancelBody = "{\"cancellation\":{\"status\":\"SUCCEEDED\",\"id\":\"c-1\",\"totalAmount\":0}}";
    public volatile long delayMillis = 0;

    private final HttpServer server;

    public FakePortOne() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/payments", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Received> cancels() {
        return received.stream().filter(r -> "POST".equals(r.method()) && r.path().endsWith("/cancel")).toList();
    }

    public List<Received> gets() {
        return received.stream().filter(r -> "GET".equals(r.method())).toList();
    }

    public void reset() {
        payments.clear();
        received.clear();
        getStatus = 0;
        cancelStatus = 200;
        cancelBody = "{\"cancellation\":{\"status\":\"SUCCEEDED\",\"id\":\"c-1\",\"totalAmount\":0}}";
        delayMillis = 0;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getPath();
        received.add(new Received(exchange.getRequestMethod(), path, exchange.getRequestHeaders(), body));
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        String[] parts = path.split("/");   // ["", "payments", "{id}"(, "cancel")]
        String paymentId = parts.length > 2 ? parts[2] : "";
        if ("GET".equals(exchange.getRequestMethod())) {
            if (getStatus != 0) {
                respond(exchange, getStatus, "{\"type\":\"INTERNAL\",\"message\":\"boom\"}");
                return;
            }
            Remote remote = payments.get(paymentId);
            if (remote == null) {
                respond(exchange, 404, "{\"type\":\"PAYMENT_NOT_FOUND\",\"message\":\"없음\"}");
                return;
            }
            respond(exchange, 200, remote.json());
            return;
        }
        if (path.endsWith("/cancel")) {
            if (cancelStatus == 200 && cancelBody.contains("SUCCEEDED")) {
                Remote remote = payments.get(paymentId);
                if (remote != null) {
                    payments.put(paymentId, new Remote("CANCELLED", remote.total(), remote.currency(), remote.transactionId(), null));
                }
            }
            respond(exchange, cancelStatus, cancelBody);
            return;
        }
        respond(exchange, 404, "{\"type\":\"NOT_FOUND\"}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

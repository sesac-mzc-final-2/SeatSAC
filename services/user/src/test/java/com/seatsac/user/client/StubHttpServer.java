package com.seatsac.user.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 컨테이너 없이 booking·catalog·Cognito를 대신하는 JDK HttpServer. 받은 요청(경로·쿼리·헤더·본문)을 기록하고,
 * 테스트가 정한 상태·지연·본문으로 답한다.
 */
public final class StubHttpServer implements AutoCloseable {

    public record Received(String method, String path, String query, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            List<String> values = headers.get(name);
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    @FunctionalInterface
    public interface Responder {
        /** @return 응답 본문. 상태는 {@link StubHttpServer#status} */
        String respond(Received request);
    }

    public final List<Received> received = new CopyOnWriteArrayList<>();
    public volatile int status = 200;
    public volatile long delayMillis = 0;
    public volatile String contentType = "application/json";

    private final HttpServer server;

    public StubHttpServer(String path, Responder responder) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> handle(exchange, responder));
        server.start();
    }

    private void handle(HttpExchange exchange, Responder responder) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Received request = new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
            exchange.getRequestURI().getQuery(), exchange.getRequestHeaders(), body);
        received.add(request);
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        String response = responder.respond(request);
        byte[] bytes = response == null ? new byte[0] : response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

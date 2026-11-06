package com.seatsac.common.test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 컨테이너 없이 다른 서비스(booking·catalog·payment·Cognito)를 대신하는 JDK HttpServer.
 * 받은 요청(메서드·경로·쿼리·헤더·본문)을 기록하고, 테스트가 정한 상태·지연·본문으로 답한다.
 *
 * <p>쓰는 방법은 두 가지다.
 * <ul>
 *   <li><b>같은 응답을 계속</b> — {@link Responder}로 본문만 만들고 {@link #status}·{@link #delayMillis}를 바꾼다</li>
 *   <li><b>호출마다 다른 응답</b> — {@link #enqueue(Reply)}로 미리 쌓아 둔다. 재시도·대체 경로처럼
 *       <b>순서</b>가 중요한 테스트에 쓴다. 큐가 비면 {@link Responder}로 돌아간다</li>
 * </ul>
 *
 * <p><b>순서 보장과 지연 격리 중 하나를 고른다.</b>
 * <ul>
 *   <li>{@link #StubHttpServer(String, Responder)} — 한 스레드에서 차례로 처리한다(기본).
 *       {@link #received} 순서 단언이나 <b>응답자가 {@link #status}를 바꾸는</b> 방식은 이쪽이어야
 *       한다 — 동시에 처리하면 공유 필드가 섞인다</li>
 *   <li>{@link #concurrent(String, Responder)} — 스레드풀로 동시에 처리한다. {@link #delayMillis}나
 *       {@link Reply#after(long)}로 <b>타임아웃을 만드는 테스트</b>에 쓴다. 한 스레드로 두면 쉬는
 *       동안 다음 요청이 뒤에 밀려 같이 타임아웃되고, 엉뚱한 테스트가 대체값을 받아 깨진다
 *       (2026-10-26 catalog에서 겪었다)</li>
 * </ul>
 */
public final class StubHttpServer implements AutoCloseable {

    /** 스텁이 받은 요청 한 건 */
    public record Received(String method, String path, String query, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            List<String> values = headers.get(name);
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    /** 미리 쌓아 두는 응답. {@code body}가 null이면 본문 없이 상태만 돌려준다 */
    public record Reply(int status, String body, long delayMillis) {
        public static Reply ok(String body) {
            return new Reply(200, body, 0);
        }

        public static Reply status(int status) {
            return new Reply(status, null, 0);
        }

        public Reply after(long delayMillis) {
            return new Reply(status, body, delayMillis);
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

    private final Deque<Reply> replies = new ConcurrentLinkedDeque<>();
    private final HttpServer server;
    private final ExecutorService pool;

    /**
     * 요청을 <b>한 번에 하나씩</b> 처리하는 스텁(기본).
     *
     * @param path 받을 경로. {@code HttpServer}는 접두사로 맞추므로 {@code /internal/payments}면
     *             그 아래 경로도 같은 핸들러가 받는다 — 여러 엔드포인트는 {@link Received#path()}로 가른다
     */
    public StubHttpServer(String path, Responder responder) throws IOException {
        this(path, responder, Executors.newSingleThreadExecutor());
    }

    /**
     * 요청을 동시에 처리하는 스텁. 지연으로 타임아웃을 만드는 테스트에 쓴다 —
     * 응답자가 {@link #status}를 바꾸거나 {@link #received} 순서에 기대는 테스트에는 쓰지 않는다.
     */
    public static StubHttpServer concurrent(String path, Responder responder) throws IOException {
        return new StubHttpServer(path, responder, Executors.newCachedThreadPool());
    }

    private StubHttpServer(String path, Responder responder, ExecutorService executor) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pool = executor;
        server.setExecutor(pool);
        server.createContext(path, exchange -> handle(exchange, responder));
        server.start();
    }

    /** 다음 호출(들)에 쓸 응답을 쌓는다. 쌓은 순서대로 한 번씩 쓰인다 */
    public StubHttpServer enqueue(Reply reply) {
        replies.add(reply);
        return this;
    }

    /** 받은 요청 수 — 재시도 횟수를 세는 데 쓴다 */
    public int requestCount() {
        return received.size();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange, Responder responder) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Received request = new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
            exchange.getRequestURI().getQuery(), exchange.getRequestHeaders(), body);
        received.add(request);

        Reply queued = replies.poll();
        // 응답자를 <b>먼저</b> 부른다 — 응답자가 status를 세팅하는 방식(user 통합 테스트)이 있어서
        // status를 먼저 읽으면 앞 호출의 값이 나간다
        String replyBody = queued != null ? queued.body() : responder.respond(request);
        int replyStatus = queued != null ? queued.status() : status;
        long wait = queued != null ? queued.delayMillis() : delayMillis;

        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exchange.close();   // close()의 shutdownNow — 멈춘 서버에 쓰지 않는다
                return;
            }
        }
        send(exchange, replyStatus, replyBody);
    }

    /**
     * 응답을 쓴다. 지연 뒤에는 <b>늦더라도 정상 응답을 보낸다</b> — 응답 없이 닫으면 호출자 쪽에
     * 읽기 타임아웃이 걸리지 않아도 연결 오류로 대체 경로를 타, 타임아웃 자체를 검증하지 못한다.
     * 이미 포기한 호출자에게 쓰다 나는 {@code IOException}만 삼킨다(그것까지 핸들러 밖으로 나가면
     * Gradle 로그에 스택이 섞인다). 다른 예외는 그대로 둔다.
     */
    private void send(HttpExchange exchange, int replyStatus, String replyBody) {
        byte[] bytes = replyBody == null ? new byte[0] : replyBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        try {
            exchange.sendResponseHeaders(replyStatus, bytes.length == 0 ? -1 : bytes.length);
            // 본문이 없어도 스트림을 닫아 교환을 끝낸다. 닫지 않으면 keep-alive 연결이
            // 반쯤 열린 채 남아 다음 요청이 <b>앞 요청의 응답을 받는다</b>(상태가 뒤바뀐다)
            try (OutputStream out = exchange.getResponseBody()) {
                if (bytes.length > 0) {
                    out.write(bytes);
                }
            }
        } catch (IOException e) {
            exchange.close();   // 호출자가 이미 끊었다
        }
    }

    @Override
    public void close() {
        server.stop(0);
        pool.shutdownNow();
        replies.clear();
    }

    /** 남은(쓰이지 않은) 응답 — 테스트가 쌓아 둔 것을 다 썼는지 확인할 때 */
    public List<Reply> remainingReplies() {
        return List.copyOf(new ArrayDeque<>(replies));
    }
}

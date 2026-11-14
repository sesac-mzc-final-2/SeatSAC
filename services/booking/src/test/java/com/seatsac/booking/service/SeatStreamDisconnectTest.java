package com.seatsac.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.seatsac.booking.component.CatalogClient;
import com.seatsac.booking.component.ScreeningCache;
import com.seatsac.common.test.MySqlContainerSupport;
import com.seatsac.common.test.ValkeyContainerSupport;
import com.seatsac.common.web.error.ErrorResponder;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * BKG-05 화면이 떠난 SSE 연결 — 실제 Tomcat에서 연결을 끊고 하트비트를 보낸다.
 *
 * <p>화면이 결제 페이지로 넘어가면 좌석 SSE 연결이 끊긴다. 이것은 정상 동작이라 서버 오류
 * (ERROR · 「status=500」)로 남기지 않고, 연결 수만 줄어야 한다. MockMvc는 소켓이 없어
 * 끊김을 흉내 낼 수 없으므로 실제 포트로 연다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({MySqlContainerSupport.class, ValkeyContainerSupport.class})
class SeatStreamDisconnectTest {

    private static final Long SCREENING = 43L;
    private static final Instant NOW = Instant.parse("2026-10-23T03:00:00Z");

    @MockitoBean
    CatalogClient catalogClient;

    @Autowired
    ScreeningCache screeningCache;
    @Autowired
    SeatStreamService seatStreamService;

    @LocalServerPort
    int port;

    private final ListAppender<ILoggingEvent> errorLogs = new ListAppender<>();
    private final Logger errorResponderLog = (Logger) LoggerFactory.getLogger(ErrorResponder.class);

    @BeforeEach
    void setUp() {
        screeningCache.clear();
        given(catalogClient.findScreening(SCREENING)).willReturn(new CatalogClient.Screening(
            SCREENING, 10_004L, 1, NOW.plusSeconds(3600), NOW.plusSeconds(10_000),
            "승부", "건대입구", "1관"));
        errorLogs.start();
        errorResponderLog.addAppender(errorLogs);
    }

    @AfterEach
    void tearDown() {
        errorResponderLog.detachAppender(errorLogs);
        seatStreamService.closeAll();
    }

    @Test
    void closedConnectionIsDroppedWithoutServerErrorLog() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            open(socket);
            assertThat(seatStreamService.connectionCount()).isEqualTo(1);
        }   // 화면이 떠났다 — 소켓을 닫는다

        // 닫힌 소켓에 쓴 첫 바이트는 커널 버퍼에 들어갈 수 있다 — 실패할 때까지 하트비트를 보낸다
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            seatStreamService.heartbeat();
            assertThat(seatStreamService.connectionCount()).isZero();
        });

        Thread.sleep(500);   // 컨테이너가 오류 디스패치를 마칠 시간
        assertThat(errorLogs.list)
            .filteredOn(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
            .as("끊긴 SSE 연결은 서버 오류로 남기지 않는다")
            .isEmpty();
    }

    /** 끊긴 연결 하나 때문에 같은 회차의 다른 화면이 하트비트·이벤트를 놓치면 안 된다 */
    @Test
    void otherViewersKeepReceivingAfterOneLeaves() throws Exception {
        try (Socket staying = new Socket("localhost", port)) {
            BufferedReader stayingIn = open(staying);
            try (Socket leaving = new Socket("localhost", port)) {
                open(leaving);
                assertThat(seatStreamService.connectionCount()).isEqualTo(2);
            }
            // 떠난 연결이 빠질 때까지 하트비트 — 순서상 떠난 연결이 뒤에 있어도, 앞에 있어도 남은 화면은 받아야 한다
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
                seatStreamService.heartbeat();
                assertThat(seatStreamService.connectionCount()).isEqualTo(1);
            });

            staying.setSoTimeout(5_000);
            seatStreamService.heartbeat();
            String line;
            while ((line = stayingIn.readLine()) != null && !line.startsWith(":ping")) {
                // 청크 길이 줄·빈 줄을 넘긴다
            }
            assertThat(line).startsWith(":ping");
        }
    }

    /** 요청을 보내고 {@code :connected}까지 읽는다 — 이후 줄을 읽을 수 있게 리더를 돌려준다 */
    private BufferedReader open(Socket socket) throws Exception {
        OutputStream out = socket.getOutputStream();
        out.write(("GET /screenings/" + SCREENING + "/seats/stream HTTP/1.1\r\n"
            + "Host: localhost\r\nAccept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null && !line.startsWith(":connected")) {
            // 상태 줄·헤더·retry 줄을 넘긴다
        }
        assertThat(line).startsWith(":connected");
        return in;
    }
}

package com.seatsac.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.component.CatalogClient;
import com.seatsac.booking.component.ScreeningCache;
import com.seatsac.booking.component.SeatEventPublisher;
import com.seatsac.booking.enums.SeatEventType;
import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.CommonResponseCode;
import com.seatsac.common.test.MySqlContainerSupport;
import com.seatsac.common.test.ValkeyContainerSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * BKG-05 SSE (명세 5장). 실제 Valkey 발행이 열린 연결까지 흘러가는지 본다.
 *
 * <p>{@code SseEmitter}는 응답에 바로 쓰기 때문에 MockMvc 비동기 요청으로 열어 두고
 * 그 응답 버퍼를 읽는다 — 연결은 끝나지 않으므로 {@code asyncDispatch} 없이 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({MySqlContainerSupport.class, ValkeyContainerSupport.class})
class SeatStreamFlowTest {

    private static final Long SCREENING = 42L;
    private static final Instant NOW = Instant.parse("2026-10-23T03:00:00Z");

    @MockitoBean
    CatalogClient catalogClient;

    @Autowired
    MockMvc mvc;
    @Autowired
    ScreeningCache screeningCache;
    @Autowired
    SeatStreamService seatStreamService;
    @Autowired
    SeatEventPublisher eventPublisher;
    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();   // seq를 1부터
        screeningCache.clear();
        given(catalogClient.findScreening(SCREENING)).willReturn(new CatalogClient.Screening(
            SCREENING, 10_004L, 1, NOW.plusSeconds(3600), NOW.plusSeconds(10_000),
            "승부", "건대입구", "1관"));
        given(catalogClient.findScreening(99L)).willReturn(new CatalogClient.Screening(
            99L, 10_004L, 1, NOW.plusSeconds(3600), NOW.plusSeconds(10_000),
            "승부", "건대입구", "1관"));
    }

    @AfterEach
    void closeConnections() {
        seatStreamService.closeAll();   // 열어 둔 연결을 닫는다(배포 때와 같은 경로)
    }

    @Test
    void streamOpensWithRetryAndNoCacheHeader() throws Exception {
        MvcResult stream = open(SCREENING);

        // SSE 세트 ④ — 캐시되면 화면이 과거 이벤트를 받는다
        assertThat(content(stream))
            .contains("retry:" + BookingPolicy.SSE_RETRY_MILLIS)
            .contains(":connected");
    }

    @Test
    void publishedEventReachesOnlyThatScreening() throws Exception {
        MvcResult watcher = open(SCREENING);
        MvcResult otherScreening = open(99L);

        eventPublisher.publish(SeatEventType.HELD, SCREENING, List.of(101L, 102L), NOW);

        await().atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> assertThat(content(watcher)).contains("event:seat"));

        assertThat(content(watcher))
            .contains("\"type\":\"HELD\"")
            .contains("\"seatIds\":[101,102]")
            .contains("\"screeningId\":42")
            .contains("id:1");                                  // id = seq (INCR 결과)
        assertThat(content(otherScreening)).doesNotContain("event:seat");
    }

    /** 이벤트 본문은 계약(`seat-event.v1.json`)의 필드를 그대로 담는다 */
    @Test
    void eventBodyFollowsTheContract() throws Exception {
        MvcResult watcher = open(SCREENING);

        eventPublisher.publish(SeatEventType.CONFIRMED, SCREENING, List.of(103L), NOW);

        await().atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> assertThat(content(watcher)).contains("event:seat"));

        String data = dataOf(content(watcher));
        assertThat(com.jayway.jsonpath.JsonPath.<Integer>read(data, "$.seq")).isPositive();
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(data, "$.type")).isEqualTo("CONFIRMED");
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(data, "$.occurredAt"))
            .isEqualTo("2026-10-23T12:00:00+09:00");             // KST로 나간다
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(data, "$.traceId"))
            .matches("^[0-9a-f]{32}$");                          // 요청 밖에서도 32자리
    }

    @Test
    void heartbeatSendsPingComment() throws Exception {
        MvcResult watcher = open(SCREENING);

        seatStreamService.heartbeat();

        assertThat(content(watcher)).contains(":ping");
    }

    @Test
    void unknownScreeningIsRejectedBeforeOpeningTheStream() throws Exception {
        given(catalogClient.findScreening(7L)).willThrow(new BusinessException(
            CommonResponseCode.NOT_FOUND, "회차를 찾을 수 없습니다.", List.of()));

        mvc.perform(get("/screenings/7/seats/stream").accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.details").isArray());

        assertThat(seatStreamService.connectionCount()).isZero();
    }

    /** 메트릭은 대시보드·자동 롤백이 이름으로 찾는다 (`service-runtime-contract.md` 5장) */
    @Test
    void connectionGaugeCountsOpenConnections() throws Exception {
        assertThat(gauge()).isZero();

        open(SCREENING);
        open(SCREENING);
        assertThat(gauge()).isEqualTo(2.0);

        seatStreamService.closeAll();
        assertThat(gauge()).isZero();
    }

    /** 마지막 연결이 끊기면 구독을 해제하고, 새 연결이 오면 다시 받는다 */
    @Test
    void resubscribesAfterLastConnectionLeaves() throws Exception {
        open(SCREENING);
        seatStreamService.closeAll();
        assertThat(seatStreamService.connectionCount()).isZero();

        MvcResult second = open(SCREENING);
        eventPublisher.publish(SeatEventType.RELEASED, SCREENING, List.of(101L), NOW);

        await().atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> assertThat(content(second)).contains("\"type\":\"RELEASED\""));
    }

    private MvcResult open(Long screeningId) throws Exception {
        MvcResult stream = mvc.perform(get("/screenings/{id}/seats/stream", screeningId)
                .accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", containsString(MediaType.TEXT_EVENT_STREAM_VALUE)))
            .andExpect(header().string("Cache-Control", containsString("no-cache")))
            .andExpect(request().asyncStarted())
            .andReturn();
        await().atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> assertThat(content(stream)).contains(":connected"));
        return stream;
    }

    private static String content(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    /** 마지막 {@code data:} 줄 */
    private static String dataOf(String stream) {
        return stream.lines().filter(line -> line.startsWith("data:"))
            .reduce((first, second) -> second).orElseThrow().substring("data:".length());
    }

    private double gauge() {
        return meterRegistry.get("booking_sse_connections").gauge().value();
    }
}

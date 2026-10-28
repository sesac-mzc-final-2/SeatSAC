package com.seatsac.user.client;

import com.seatsac.common.test.StubHttpServer;
import static org.assertj.core.api.Assertions.assertThat;

import com.seatsac.common.web.client.InternalRestClientFactory;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * catalog 호출 둘의 결말 — 회차 요약(없는 ID는 빠짐·100개 분할), 극장 확인(10개 분할), 실패는 둘 다 empty(호출자가 대체).
 * 컨테이너 없이 JDK HttpServer로 catalog를 대신한다. 회차 5002·극장 9999는 "없는 ID"다.
 */
class CatalogClientTest {

    private static final Pattern ID = Pattern.compile("\\d+");

    private StubHttpServer catalog;
    private CircuitBreakerRegistry breakers;
    private CatalogClient client;

    @BeforeEach
    void start() throws Exception {
        catalog = new StubHttpServer("/internal", request -> {
            if (request.path().endsWith("/screenings/summaries")) {
                Matcher m = ID.matcher(request.body());
                StringBuilder items = new StringBuilder();
                while (m.find()) {
                    long id = Long.parseLong(m.group());
                    if (id == 5002) {
                        continue;
                    }
                    if (!items.isEmpty()) {
                        items.append(',');
                    }
                    items.append("{\"screeningId\":").append(id).append(",\"movieTitle\":\"영화").append(id)
                        .append("\",\"theaterName\":\"건대입구\",\"auditoriumName\":\"6관\","
                            + "\"startsAt\":\"2026-11-16T14:30:00+09:00\",\"endsAt\":\"2026-11-16T16:25:00+09:00\",\"extra\":1}");
                }
                return "{\"items\":[" + items + "]}";
            }
            String ids = request.query().substring("ids=".length());
            String items = Arrays.stream(ids.split(",")).map(Long::valueOf)
                .filter(id -> id != 9999)
                .map(id -> "{\"theaterId\":" + id + ",\"name\":\"극장" + id + "\"}")
                .collect(Collectors.joining(","));
            return "{\"items\":[" + items + "]}";
        });
        breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
            .slidingWindowSize(4).minimumNumberOfCalls(2).failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(10)).build());
        client = new CatalogClient(new InternalRestClientFactory().create(catalog.baseUrl(), Duration.ofMillis(500)),
            breakers);
    }

    @AfterEach
    void stop() {
        catalog.close();
    }

    @Test
    void summariesMapByScreeningIdAndOmitUnknownIds() {
        Optional<Map<Long, CatalogClient.ScreeningSummary>> summaries = client.screeningSummaries(List.of(5001L, 5002L, 5001L));

        assertThat(summaries).isPresent();
        assertThat(summaries.get()).containsOnlyKeys(5001L);
        assertThat(summaries.get().get(5001L).movieTitle()).isEqualTo("영화5001");
        assertThat(catalog.received).hasSize(1);
        assertThat(catalog.received.get(0).method()).isEqualTo("POST");
        assertThat(catalog.received.get(0).body()).isEqualTo("{\"screeningIds\":[5001,5002]}");   // 중복 제거
    }

    @Test
    void summariesSplitIntoBatchesOfHundred() {
        List<Long> ids = LongStream.rangeClosed(1, 150).boxed().toList();

        Optional<Map<Long, CatalogClient.ScreeningSummary>> summaries = client.screeningSummaries(ids);

        assertThat(summaries).isPresent();
        assertThat(summaries.get()).hasSize(150);
        assertThat(catalog.received).hasSize(2);
    }

    @Test
    void emptyInputDoesNotCallCatalog() {
        assertThat(client.screeningSummaries(List.of())).contains(Map.of());
        assertThat(client.existingTheaterIds(List.of())).contains(Set.of());
        assertThat(catalog.received).isEmpty();
    }

    @Test
    void failuresAreEmptySoCallersFallBack() {
        catalog.status = 503;
        assertThat(client.screeningSummaries(List.of(5001L))).isEmpty();
        assertThat(client.existingTheaterIds(List.of(1004L))).isEmpty();

        // 위 503 두 번으로 서킷이 열렸다 — 닫고 기록을 비워 타임아웃 분기가 실제 HTTP 호출로 검증되게 한다
        breakers.circuitBreaker("catalog").reset();
        catalog.received.clear();
        catalog.status = 200;
        catalog.delayMillis = 1500;
        assertThat(client.existingTheaterIds(List.of(1004L))).isEmpty();
        assertThat(catalog.received).hasSize(1);
    }

    @Test
    void theaterIdsReturnOnlyExistingOnesInBatchesOfTen() {
        Optional<Set<Long>> existing = client.existingTheaterIds(List.of(1004L, 9999L, 1016L));

        assertThat(existing).contains(Set.of(1004L, 1016L));
        assertThat(catalog.received.get(0).query()).isEqualTo("ids=1004,9999,1016");

        catalog.received.clear();
        assertThat(client.existingTheaterIds(LongStream.rangeClosed(1, 11).boxed().toList())).isPresent();
        assertThat(catalog.received).hasSize(2);
        assertThat(catalog.received.get(0).query().split(",")).hasSize(10);
    }
}

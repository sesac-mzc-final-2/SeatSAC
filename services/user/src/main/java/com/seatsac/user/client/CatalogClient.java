package com.seatsac.user.client;

import com.seatsac.common.core.error.BusinessException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * catalog 내부 API 둘 (catalog 내부 계약, 호출자 user).
 * <ul>
 *   <li>{@code POST /internal/screenings/summaries} (최대 100개) — USR-04 예매 내역의 영화·극장·상영관·시각</li>
 *   <li>{@code GET /internal/theaters?ids=} (최대 10개) — USR-05 선호 극장 존재 확인</li>
 * </ul>
 * 둘 다 <b>실패해도 요청을 막지 않는다</b>: 요약은 {@code screening: null}, 극장은 검증 생략(계약).
 * 그래서 실패를 {@link Optional#empty()}로 돌려주고 호출자가 대체한다. 서킷 {@code catalog}(실패율 50%·10초).
 * 없는 ID는 계약대로 결과에서 빠진다(빠진 것은 오류가 아니다).
 */
@Component
public class CatalogClient {

    static final int MAX_SUMMARY_IDS = 100;
    static final int MAX_THEATER_IDS = 10;

    private static final Logger log = LoggerFactory.getLogger(CatalogClient.class);

    private final RestClient catalogRestClient;
    private final CircuitBreaker circuitBreaker;

    public CatalogClient(RestClient catalogRestClient, CircuitBreakerRegistry circuitBreakerRegistry) {
        this.catalogRestClient = catalogRestClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("catalog");
    }

    /** @return 회차 ID → 요약. catalog가 답하지 않으면 empty (호출자는 screening null) */
    public Optional<Map<Long, ScreeningSummary>> screeningSummaries(Collection<Long> screeningIds) {
        if (screeningIds.isEmpty()) {
            return Optional.of(Map.of());
        }
        Map<Long, ScreeningSummary> summaries = new HashMap<>();
        try {
            for (List<Long> batch : partition(screeningIds, MAX_SUMMARY_IDS)) {
                SummaryItems response = circuitBreaker.executeSupplier(() -> catalogRestClient.post()
                    .uri("/internal/screenings/summaries")
                    .body(Map.of("screeningIds", batch))
                    .retrieve()
                    .body(SummaryItems.class));
                if (response != null) {
                    for (ScreeningSummary summary : response.items()) {
                        summaries.put(summary.screeningId(), summary);
                    }
                }
            }
        } catch (CallNotPermittedException | BusinessException | RestClientException e) {
            log.warn("catalog screening summaries unavailable ids={} cause={}: {}", screeningIds.size(),
                e.getClass().getSimpleName(), e.getMessage());
            return Optional.empty();
        }
        return Optional.of(summaries);
    }

    /** @return 존재하는 극장 ID. catalog가 답하지 않으면 empty (호출자는 검증 생략) */
    public Optional<Set<Long>> existingTheaterIds(Collection<Long> theaterIds) {
        if (theaterIds.isEmpty()) {
            return Optional.of(Set.of());
        }
        Set<Long> existing = new HashSet<>();
        try {
            for (List<Long> batch : partition(theaterIds, MAX_THEATER_IDS)) {
                String joined = batch.stream().map(String::valueOf).collect(Collectors.joining(","));
                TheaterItems response = circuitBreaker.executeSupplier(() -> catalogRestClient.get()
                    .uri("/internal/theaters?ids={ids}", joined)
                    .retrieve()
                    .body(TheaterItems.class));
                if (response != null) {
                    response.items().forEach(t -> existing.add(t.theaterId()));
                }
            }
        } catch (CallNotPermittedException | BusinessException | RestClientException e) {
            log.warn("catalog theaters unavailable ids={} cause={}: {}", theaterIds.size(),
                e.getClass().getSimpleName(), e.getMessage());
            return Optional.empty();
        }
        return Optional.of(existing);
    }

    /** 중복을 빼고 상한 크기로 나눈다 */
    private static List<List<Long>> partition(Collection<Long> ids, int limit) {
        List<Long> all = new ArrayList<>(new LinkedHashSet<>(ids));
        List<List<Long>> batches = new ArrayList<>();
        for (int from = 0; from < all.size(); from += limit) {
            batches.add(all.subList(from, Math.min(all.size(), from + limit)));
        }
        return batches;
    }

    /** POST /internal/screenings/summaries 원소. 시각은 응답 DTO에서 +09:00으로 다시 맞춘다 */
    public record ScreeningSummary(
        Long screeningId,
        String movieTitle,
        String theaterName,
        String auditoriumName,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt
    ) {
    }

    /** GET /internal/theaters 원소 */
    public record TheaterRef(Long theaterId, String name) {
    }

    /** {@code { items: [...] }} — 모르는 필드는 무시한다 */
    record SummaryItems(List<ScreeningSummary> items) {
        SummaryItems {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    record TheaterItems(List<TheaterRef> items) {
        TheaterItems {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}

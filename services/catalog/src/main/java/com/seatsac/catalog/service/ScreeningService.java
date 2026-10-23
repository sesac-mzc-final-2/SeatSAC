package com.seatsac.catalog.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.seatsac.catalog.cache.CacheKeys;
import com.seatsac.catalog.cache.CatalogCache;
import com.seatsac.catalog.client.SeatCountClient;
import com.seatsac.catalog.common.CatalogErrorCode;
import com.seatsac.catalog.dto.CachedScreening;
import com.seatsac.catalog.dto.ItemsResponse;
import com.seatsac.catalog.dto.ScreeningResponse;
import com.seatsac.catalog.dto.internal.ScreeningForBookingResponse;
import com.seatsac.catalog.dto.internal.ScreeningSummary;
import com.seatsac.catalog.repository.ScreeningRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * CAT-03. 캐시에는 영업일 전체 회차(잔여 좌석 없음)를 넣고, 요청마다 ① 시작한 회차 제외({@code startsAt > now})
 * ② booking 집계로 잔여 좌석 채우기를 한다. 잔여 좌석은 캐시하지 않는다(CAT-05).
 */
@Service
public class ScreeningService {

    private static final TypeReference<List<CachedScreening>> LIST_TYPE = new TypeReference<>() {
    };

    private final ScreeningRepository screeningRepository;
    private final CatalogCache cache;
    private final SeatCountClient seatCountClient;
    private final Clock clock;

    public ScreeningService(ScreeningRepository screeningRepository, CatalogCache cache,
                            SeatCountClient seatCountClient, Clock clock) {
        this.screeningRepository = screeningRepository;
        this.cache = cache;
        this.seatCountClient = seatCountClient;
        this.clock = clock;
    }

    /** 시간순. 시드 기간 밖·조건에 맞는 회차 없음은 {@code items: []} */
    public ItemsResponse<ScreeningResponse> list(LocalDate date, Long theaterId, Long movieId) {
        Instant now = clock.instant();
        List<CachedScreening> day = cache.getOrLoad(CacheKeys.screenings(date, theaterId, movieId), LIST_TYPE, () ->
            (movieId == null
                ? screeningRepository.findByDateAndTheater(date, theaterId)
                : screeningRepository.findByDateAndTheaterAndMovie(date, theaterId, movieId))
                .stream().map(CachedScreening::from).toList());

        List<CachedScreening> upcoming = day.stream().filter(s -> s.startsAfter(now)).toList();
        return new ItemsResponse<>(withRemainingSeats(upcoming));
    }

    /** 좌석 선택 화면 새로고침·직접 진입. 캐시 키가 따로 없어 DB에서 읽는다(PK + fetch join 한 번) */
    public ScreeningResponse get(Long screeningId) {
        CachedScreening screening = screeningRepository.findWithRelationsById(screeningId)
            .map(CachedScreening::from)
            .orElseThrow(CatalogErrorCode.SCREENING_NOT_FOUND::exception);
        return withRemainingSeats(List.of(screening)).get(0);
    }

    /** 내부 — booking BKG-02·06·07 */
    public ScreeningForBookingResponse forBooking(Long screeningId) {
        return screeningRepository.findWithRelationsById(screeningId)
            .map(ScreeningForBookingResponse::from)
            .orElseThrow(CatalogErrorCode.SCREENING_NOT_FOUND::exception);
    }

    /** 내부 — user USR-04. 없는 ID는 결과에서 빠진다 */
    public ItemsResponse<ScreeningSummary> summaries(List<Long> screeningIds) {
        return new ItemsResponse<>(screeningRepository.findWithRelationsByIds(screeningIds).stream()
            .map(ScreeningSummary::from).toList());
    }

    private List<ScreeningResponse> withRemainingSeats(List<CachedScreening> screenings) {
        if (screenings.isEmpty()) {
            return List.of();
        }
        Optional<Map<Long, Long>> counts = seatCountClient.unavailableCounts(
            screenings.stream().map(CachedScreening::screeningId).toList());
        return screenings.stream()
            .map(s -> s.toResponse(counts.map(c -> c.get(s.screeningId())).orElse(null)))
            .toList();
    }
}

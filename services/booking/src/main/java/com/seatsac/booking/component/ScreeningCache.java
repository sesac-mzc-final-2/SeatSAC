package com.seatsac.booking.component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.seatsac.booking.common.BookingErrorCode;
import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.component.CatalogClient.AuditoriumLayout;
import com.seatsac.booking.component.CatalogClient.Screening;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 회차·좌석 배치 파드 캐시 — 명세 12.3. 선점 p95 300ms를 맞추려고 Valkey가 아니라 파드 메모리에 둔다
 * (좌석 배치는 모든 파드가 같은 값을 읽고 거의 바뀌지 않는다).
 *
 * <p>{@code get(key, loader)}를 쓰므로 같은 키에 요청이 몰려도 <b>Catalog 호출은 1회</b>다
 * — 같은 좌석 100명 동시 요청 데모에서 Catalog를 100번 부르지 않는다.
 */
@Component
@Slf4j
public class ScreeningCache {

    private final CatalogClient catalog;
    private final Cache<Long, Screening> screenings;
    private final Cache<LayoutKey, SeatLayout> layouts;

    public ScreeningCache(CatalogClient catalog) {
        this.catalog = catalog;
        this.screenings = Caffeine.newBuilder()
            .maximumSize(BookingPolicy.SCREENING_CACHE_SIZE)
            .expireAfterWrite(BookingPolicy.SCREENING_CACHE_TTL)
            .build();
        this.layouts = Caffeine.newBuilder()
            .maximumSize(BookingPolicy.LAYOUT_CACHE_SIZE)
            .expireAfterWrite(BookingPolicy.LAYOUT_CACHE_TTL)
            .build();
    }

    /** 없으면 Catalog에서 받아 캐시한다. 404는 {@code SCREENING_NOT_FOUND}, 그 밖 실패는 503 */
    public Screening screening(Long screeningId) {
        return load(() -> screenings.get(screeningId, catalog::findScreening));
    }

    /** (상영관, 배치 버전) 키. 좌석 ID·번호·활성만 추려 담는다 */
    public SeatLayout layout(Long auditoriumId, Integer layoutVersion) {
        return load(() -> layouts.get(new LayoutKey(auditoriumId, layoutVersion),
            key -> SeatLayout.from(catalog.findLayout(key.auditoriumId(), key.layoutVersion()))));
    }

    private static <T> T load(java.util.function.Supplier<T> loader) {
        try {
            T value = loader.get();
            if (value == null) {
                throw BookingErrorCode.CATALOG_UNAVAILABLE.exception();
            }
            return value;
        } catch (RuntimeException e) {
            throw CatalogClient.unavailable(e);
        }
    }

    /** 파드 캐시는 빈 하나를 공유한다. 테스트가 회차 정보를 바꿀 때 비운다 */
    public void clear() {
        screenings.invalidateAll();
        layouts.invalidateAll();
    }

    public record LayoutKey(Long auditoriumId, Integer layoutVersion) {
    }

    /**
     * 좌석 검증과 {@code seat_labels} 생성에 쓰는 최소 정보.
     * 비활성 좌석은 담지 않아 「활성 좌석인지」 확인이 곧 포함 여부 확인이 된다.
     */
    public record SeatLayout(Map<Long, String> activeSeatLabels) {

        public SeatLayout {
            // Map.copyOf는 순서를 보장하지 않는다. 좌석 현황(BKG-01)은 배치 순서로 내려가야 한다
            activeSeatLabels = Collections.unmodifiableMap(new LinkedHashMap<>(activeSeatLabels));
        }

        static SeatLayout from(AuditoriumLayout layout) {
            Map<Long, String> labels = layout.seats().stream()
                .filter(seat -> Boolean.TRUE.equals(seat.active()))
                .collect(Collectors.toMap(AuditoriumLayout.LayoutSeat::seatId,
                    AuditoriumLayout.LayoutSeat::seatLabel, (a, b) -> a, LinkedHashMap::new));
            return new SeatLayout(labels);
        }

        public boolean containsAll(List<Long> seatIds) {
            return activeSeatLabels.keySet().containsAll(seatIds);
        }

        /** 요청 좌석의 번호를 요청 순서대로 (seat_labels 저장용) */
        public List<String> labelsOf(List<Long> seatIds) {
            return seatIds.stream().map(activeSeatLabels::get).toList();
        }

        public List<Long> seatIds() {
            return List.copyOf(activeSeatLabels.keySet());
        }

        public int size() {
            return activeSeatLabels.size();
        }
    }
}

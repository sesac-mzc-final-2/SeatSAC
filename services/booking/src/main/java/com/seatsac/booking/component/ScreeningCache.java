package com.seatsac.booking.component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.seatsac.booking.common.BookingErrorCode;
import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.component.CatalogClient.AuditoriumLayout;
import com.seatsac.booking.component.CatalogClient.Screening;
import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.CommonResponseCode;
import com.seatsac.common.core.error.ErrorDetail;
import java.time.Clock;
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
    private final Clock clock;
    private final Cache<Long, Screening> screenings;
    private final Cache<LayoutKey, SeatLayout> layouts;

    public ScreeningCache(CatalogClient catalog, Clock clock) {
        this.catalog = catalog;
        this.clock = clock;
        this.screenings = Caffeine.newBuilder()
            .maximumSize(BookingPolicy.SCREENING_CACHE_SIZE)
            .expireAfterWrite(BookingPolicy.SCREENING_CACHE_TTL)
            .build();
        this.layouts = Caffeine.newBuilder()
            .maximumSize(BookingPolicy.LAYOUT_CACHE_SIZE)
            .expireAfterWrite(BookingPolicy.LAYOUT_CACHE_TTL)
            .build();
    }

    /**
     * 없으면 Catalog에서 받아 캐시한다. 404는 {@code SCREENING_NOT_FOUND}, 그 밖 실패는 503.
     * 시작 시각이 없는 응답도 503이다(캐시하지 않는다) — 선점·결제·취소가 모두 시작 시각으로 판정하는데,
     * 모르는 채로 「시작 전」으로 보면 마감·환불 조건을 건너뛴다.
     */
    public Screening screening(Long screeningId) {
        return load(() -> screenings.get(screeningId, id -> {
            Screening screening = catalog.findScreening(id);
            if (screening != null && screening.startsAt() == null) {
                log.warn("회차 시작 시각 없음 screeningId={}", id);
                throw BookingErrorCode.CATALOG_UNAVAILABLE.exception();
            }
            return screening;
        }));
    }

    /**
     * 선점·결제용 회차 — 예매 마감(상영 시작 5분 전, {@link BookingPolicy#BOOKING_CUTOFF})이면
     * 400 {@code SCREENING_STARTED}. 시각은 회차를 읽은 <b>뒤</b>에 구한다(Catalog 조회가 길어져도
     * 판정이 늦어지지 않게).
     */
    public Screening openForBooking(Long screeningId) {
        return requireOpen(screening(screeningId));
    }

    /** {@link #openForBooking}의 판정만 — 이미 읽은 회차에 쓴다 */
    public Screening requireOpen(Screening screening) {
        if (screening.isClosedForBooking(clock.instant())) {
            throw BookingErrorCode.BOOKING_CLOSED.exception(ErrorDetail.of("screeningId",
                screening.screeningId(), BookingErrorCode.BOOKING_CLOSED.name()));
        }
        return screening;
    }

    /**
     * <b>이미 있는 예매</b>의 회차(결제·취소). Catalog가 404로 답해도 503이다 — 예매가 가리키는 회차가
     * 없다는 것은 시드 재적재 같은 운영 문제이고, 404로 답하면 화면이 「예매를 찾을 수 없음」으로 안내한다.
     */
    public Screening screeningOfBooking(Long bookingId, Long screeningId) {
        try {
            return screening(screeningId);
        } catch (BusinessException e) {
            if (e.code().responseCode().equals(CommonResponseCode.NOT_FOUND.name())) {
                log.warn("예매의 회차를 Catalog에서 찾지 못함 bookingId={} screeningId={}", bookingId, screeningId);
                throw BookingErrorCode.CATALOG_UNAVAILABLE.exception();
            }
            throw e;
        }
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

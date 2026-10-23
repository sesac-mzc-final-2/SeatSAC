package com.seatsac.catalog.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.catalog.cache.CacheKeys;
import com.seatsac.catalog.dto.ScreeningResponse.AuditoriumRef;
import com.seatsac.catalog.dto.ScreeningResponse.MovieRef;
import com.seatsac.catalog.dto.ScreeningResponse.TheaterRef;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/** 코드 값 → 계약 enum 변환, 잔여 좌석 계산·출처, 시각 +09:00, 캐시 키 형식(규약 4장). */
@JsonTest
class CatalogDtoTest {

    @Autowired
    ObjectMapper mapper;

    private static final CachedScreening SCREENING = new CachedScreening(5001L,
        new MovieRef(1L, "영화", ViewGrade.AGE_12), new TheaterRef(1004L, "건대입구"),
        new AuditoriumRef(406L, "6관", ScreenType.NORMAL), 1, LocalDate.of(2026, 11, 16),
        Instant.parse("2026-11-16T05:30:00Z"), Instant.parse("2026-11-16T07:25:00Z"), "2D", 184);

    @Test
    void codesMapToContractEnums() throws Exception {
        assertThat(ViewGrade.fromCode(0)).isEqualTo(ViewGrade.ALL);
        assertThat(ViewGrade.fromCode(12)).isEqualTo(ViewGrade.AGE_12);
        assertThat(ViewGrade.fromCode(15)).isEqualTo(ViewGrade.AGE_15);
        assertThat(ViewGrade.fromCode(19)).isEqualTo(ViewGrade.AGE_19);
        assertThat(ViewGrade.fromCode(null)).isEqualTo(ViewGrade.AGE_19);   // 모르면 가장 높은 등급
        assertThat(mapper.writeValueAsString(ViewGrade.AGE_12)).isEqualTo("\"12\"");
        assertThat(mapper.readValue("\"ALL\"", ViewGrade.class)).isEqualTo(ViewGrade.ALL);   // 캐시 왕복

        assertThat(ScreenType.fromCode(986)).isEqualTo(ScreenType.RECLINER);
        assertThat(ScreenType.fromCode(100)).isEqualTo(ScreenType.NORMAL);
        assertThat(ScreenType.fromCode(null)).isEqualTo(ScreenType.NORMAL);

        assertThat(SeatType.fromCode(1)).isEqualTo(SeatType.NORMAL);
        assertThat(SeatType.fromCode(190)).isEqualTo(SeatType.RECLINER);
        assertThat(SeatType.fromCode(290)).isEqualTo(SeatType.UNKNOWN);
        assertThat(SeatType.fromCode(40)).isEqualTo(SeatType.UNKNOWN);
    }

    @Test
    void remainingSeatsComeFromBookingOrTotal() {
        ScreeningResponse fromBooking = SCREENING.toResponse(13L);
        assertThat(fromBooking.remainingSeats()).isEqualTo(171);
        assertThat(fromBooking.remainingSeatsSource()).isEqualTo(RemainingSeatsSource.BOOKING);

        ScreeningResponse fallback = SCREENING.toResponse(null);
        assertThat(fallback.remainingSeats()).isEqualTo(184);
        assertThat(fallback.remainingSeatsSource()).isEqualTo(RemainingSeatsSource.TOTAL);

        // 집계가 총 좌석을 넘는 순간(배치 변경 직후 등)에도 음수는 내보내지 않는다
        assertThat(SCREENING.toResponse(999L).remainingSeats()).isZero();
    }

    @Test
    void startedScreeningsAreFilteredByStartsAt() {
        assertThat(SCREENING.startsAfter(Instant.parse("2026-11-16T05:29:59Z"))).isTrue();
        assertThat(SCREENING.startsAfter(Instant.parse("2026-11-16T05:30:00Z"))).isFalse();
    }

    @Test
    void responseTimesAreKstAndSurviveCacheRoundTrip() throws Exception {
        String json = mapper.writeValueAsString(SCREENING.toResponse(0L));
        assertThat(json).contains("\"startsAt\":\"2026-11-16T14:30:00+09:00\"")
            .contains("\"businessDate\":\"2026-11-16\"")
            .contains("\"viewGrade\":\"12\"");

        // 캐시에 들어가는 형태(Instant)도 왕복한다
        CachedScreening back = mapper.readValue(mapper.writeValueAsString(SCREENING), CachedScreening.class);
        assertThat(back).isEqualTo(SCREENING);
    }

    @Test
    void cacheKeysFollowConventions() {
        assertThat(CacheKeys.movies("NOW_SHOWING", 0, 20)).isEqualTo("catalog:movies:NOW_SHOWING:0:20");
        assertThat(CacheKeys.movie(12L)).isEqualTo("catalog:movie:12");
        assertThat(CacheKeys.theaters(true)).isEqualTo("catalog:theaters:true:all");
        assertThat(CacheKeys.theaters(false)).isEqualTo("catalog:theaters:false:all");   // region은 키에 넣지 않는다
        assertThat(CacheKeys.screenings(LocalDate.of(2026, 11, 16), 1004L, null)).isEqualTo("catalog:screenings:2026-11-16:1004:all");
        assertThat(CacheKeys.screenings(LocalDate.of(2026, 11, 16), 1004L, 12L)).isEqualTo("catalog:screenings:2026-11-16:1004:12");
        assertThat(CacheKeys.layout(406L, 1)).isEqualTo("catalog:layout:406:1");
    }

    @Test
    void posterUrlIsTmdbOrNull() {
        assertThat(MovieSummary.posterUrl("/abc.jpg")).isEqualTo("https://image.tmdb.org/t/p/w500/abc.jpg");
        assertThat(MovieSummary.posterUrl(null)).isNull();
        assertThat(MovieSummary.posterUrl(" ")).isNull();
    }
}

package com.seatsac.catalog.schedule;

import com.seatsac.catalog.cache.CatalogCache;
import com.seatsac.catalog.config.CatalogProperties;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CAT-03 상영 일정 이어 붙이기 — 시드 편성(롯데 하루 편성을 기간 동안 반복, {@code lotte-seed})이 끝나도
 * 상영시간표가 비지 않게, 오늘(KST)부터 {@code app.schedule.days}일 안에 회차가 없는 영업일을 채운다.
 *
 * <ul>
 *   <li>채우는 방법: 그 날짜보다 앞선 가장 최근 영업일의 회차를 그대로 복사하고 날짜·시각만 옮긴다
 *       (상영관·영화·회차 순번·원본 표기 그대로 — 시드도 하루 편성을 날짜만 바꿔 반복한 것이라 같은 규칙이다).
 *       이미 회차가 하나라도 있는 날은 건드리지 않는다(시드·사람이 넣은 편성이 이긴다)</li>
 *   <li>새 회차 ID = {@code 영업일 epochDay × 100000 + 그날 안 순번} — 파드 두 개가 동시에 돌아도 같은 ID·같은
 *       유일 키({@code uk_screening})가 나와 {@code INSERT IGNORE}가 한쪽을 버린다. 시드 ID(1~8,360)와 겹치지 않는다</li>
 *   <li>그래도 두 파드가 같은 날을 동시에 쓰지 않게 MySQL {@code GET_LOCK}을 잡고, 못 잡으면 이번 차례는 건너뛴다</li>
 *   <li>넣은 날짜의 회차 목록 캐시({@code catalog:screenings:{date}:*})를 지운다 — 빈 목록이 10분 동안 남지 않게</li>
 *   <li>기동 1분 뒤 첫 실행, 이후 {@code app.schedule.interval}(기본 1시간)마다. 실패는 WARN 한 줄이고 다음 차례에 다시 한다</li>
 * </ul>
 */
@Component
public class ScheduleExtender {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final long ID_PER_DAY = 100_000L;
    static final String LOCK_NAME = "catalog:schedule-extend";

    private static final Logger log = LoggerFactory.getLogger(ScheduleExtender.class);

    private static final String COPY_SQL = """
        INSERT IGNORE INTO screening (screening_id, auditorium_id, layout_version, movie_id, business_date, play_sequence,
          starts_at, ends_at, raw_start_time, raw_end_time, source_movie_code, film_format, subtitle_type,
          source_translation_code)
        SELECT ? + ROW_NUMBER() OVER (ORDER BY s.screening_id), s.auditorium_id, s.layout_version, s.movie_id, ?,
          s.play_sequence, s.starts_at + INTERVAL ? DAY, s.ends_at + INTERVAL ? DAY, s.raw_start_time, s.raw_end_time,
          s.source_movie_code, s.film_format, s.subtitle_type, s.source_translation_code
        FROM screening s WHERE s.business_date = ?
        """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CatalogCache cache;
    private final Clock clock;
    private final CatalogProperties.Schedule properties;

    public ScheduleExtender(JdbcTemplate jdbc, TransactionTemplate tx, CatalogCache cache, Clock clock,
                            CatalogProperties properties) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.cache = cache;
        this.clock = clock;
        this.properties = properties.schedule();
    }

    @Scheduled(initialDelayString = "${app.schedule.initial-delay:1m}", fixedDelayString = "${app.schedule.interval:1h}")
    public void run() {
        if (!properties.enabled()) {
            return;
        }
        try {
            extend();
        } catch (RuntimeException e) {
            log.warn("schedule extend failed cause={}: {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /** 채운 영업일 목록을 돌려준다(없으면 빈 목록, 잠금을 못 잡아도 빈 목록) */
    public List<LocalDate> extend() {
        LocalDate today = LocalDate.now(clock.withZone(KST));
        List<LocalDate> filled = tx.execute(status -> {
            Integer locked = jdbc.queryForObject("SELECT GET_LOCK(?, 0)", Integer.class, LOCK_NAME);
            if (locked == null || locked != 1) {
                return List.of();
            }
            try {
                return fill(today);
            } finally {
                jdbc.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, LOCK_NAME);
            }
        });
        for (LocalDate date : filled) {
            cache.evictByPrefix("catalog:screenings:" + date + ":");
        }
        return filled;
    }

    private List<LocalDate> fill(LocalDate today) {
        List<LocalDate> filled = new ArrayList<>();
        for (int i = 0; i < properties.days(); i++) {
            LocalDate date = today.plusDays(i);
            Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM screening WHERE business_date = ?", Integer.class, Date.valueOf(date));
            if (existing != null && existing > 0) {
                continue;
            }
            Date source = jdbc.queryForObject(
                "SELECT MAX(business_date) FROM screening WHERE business_date < ?", Date.class, Date.valueOf(date));
            if (source == null) {
                continue;   // 앞선 편성이 없다(빈 DB) — 복사할 것이 없다
            }
            LocalDate sourceDate = source.toLocalDate();
            long shift = ChronoUnit.DAYS.between(sourceDate, date);
            int rows = jdbc.update(COPY_SQL, date.toEpochDay() * ID_PER_DAY, Date.valueOf(date), shift, shift,
                Date.valueOf(sourceDate));
            if (rows > 0) {
                filled.add(date);
                log.info("schedule extended date={} from={} screenings={}", date, sourceDate, rows);
            }
        }
        return filled;
    }
}

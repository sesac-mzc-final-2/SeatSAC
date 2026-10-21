package com.seatsac.common.core.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 시간 규칙: DB는 UTC, API 응답은 +09:00, 날짜만 쓰는 값과 영업일은 Asia/Seoul. 모두 null이면 null. */
public final class Times {
    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    public static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private Times() {
    }

    /** DB에서 읽은 UTC Instant → API용 +09:00 */
    public static OffsetDateTime toApi(Instant utc) {
        return utc == null ? null : utc.atOffset(KST);
    }

    /** API 입력(+09:00 등 어떤 오프셋이든) → DB 저장용 UTC Instant */
    public static Instant toDb(OffsetDateTime api) {
        return api == null ? null : api.toInstant();
    }

    /**
     * API 응답의 날짜만 쓰는 값: 서울 기준 날짜. JSON에서는 {@code YYYY-MM-DD}로 나간다.
     * 영업일 계산이 따로 필요하면 {@link #businessDate(Instant)}를 쓴다.
     */
    public static LocalDate toApiDate(Instant utc) {
        return utc == null ? null : utc.atZone(SEOUL).toLocalDate();
    }

    /** 영업일(business_date): 서울 기준 날짜 */
    public static LocalDate businessDate(Instant at) {
        return at == null ? null : at.atZone(SEOUL).toLocalDate();
    }
}

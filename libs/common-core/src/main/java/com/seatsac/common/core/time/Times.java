package com.seatsac.common.core.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 시간 규칙: DB는 UTC, API 응답은 +09:00, 영업일은 Asia/Seoul. */
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

    /** 영업일(business_date): 서울 기준 날짜 */
    public static LocalDate businessDate(Instant at) {
        return at.atZone(SEOUL).toLocalDate();
    }
}

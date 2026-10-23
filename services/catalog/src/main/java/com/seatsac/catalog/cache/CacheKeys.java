package com.seatsac.catalog.cache;

import java.time.LocalDate;

/**
 * Valkey 키 — 규약 4장 표의 catalog 소유 키만 만든다. 새 키는 표에 먼저 추가한다.
 * <pre>
 * catalog:movies:{status}:{page}:{size}
 * catalog:movie:{id}
 * catalog:theaters:{hasLayout}:all       (region은 키에 넣지 않고 메모리에서 거른다 — 키 수 제한)
 * catalog:screenings:{date}:{theaterId}:{movieId|all}
 * catalog:layout:{auditoriumId}:{version}
 * </pre>
 */
public final class CacheKeys {

    public static final String ALL = "all";

    private CacheKeys() {
    }

    public static String movies(String status, int page, int size) {
        return "catalog:movies:" + status + ":" + page + ":" + size;
    }

    public static String movie(Long movieId) {
        return "catalog:movie:" + movieId;
    }

    public static String theaters(boolean hasLayout) {
        return "catalog:theaters:" + hasLayout + ":" + ALL;
    }

    public static String screenings(LocalDate date, Long theaterId, Long movieId) {
        return "catalog:screenings:" + date + ":" + theaterId + ":" + (movieId == null ? ALL : movieId);
    }

    public static String layout(Long auditoriumId, int version) {
        return "catalog:layout:" + auditoriumId + ":" + version;
    }
}

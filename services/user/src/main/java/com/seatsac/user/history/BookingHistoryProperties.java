package com.seatsac.user.history;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 6.3.4b 예매 내역 출처 — 서비스 실행 계약 3장 user 행.
 * <ul>
 *   <li>{@code APP_BOOKING_HISTORY_MODE} → {@code app.booking-history.mode}: {@code sync}(기본, booking 내부 호출 — 지금과 같다)
 *       · {@code local}(복제본 {@code booking_history}. 사용자별 첫 조회 때 한 번 sync로 채운 뒤 복제본만 — API 명세서 v1.5 23-1)</li>
 *   <li>{@code app.booking-history.fill-max-pages}: 첫 조회 채우기가 booking에서 읽는 최대 쪽 수(쪽당 50건)</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.booking-history")
public record BookingHistoryProperties(
    @DefaultValue("sync") Mode mode,
    @DefaultValue("20") int fillMaxPages
) {

    public enum Mode { SYNC, LOCAL }

    public boolean local() {
        return mode == Mode.LOCAL;
    }
}

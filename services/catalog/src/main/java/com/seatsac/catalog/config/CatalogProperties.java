package com.seatsac.catalog.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * catalog가 읽는 설정 — 서비스 실행 계약 3장의 이름 그대로.
 * <ul>
 *   <li>{@code APP_CACHE_TTL} → {@code app.cache.ttl} (기본 10분, CAT-05)</li>
 *   <li>{@code APP_SERVICES_BOOKING_URL} → {@code app.services.booking-url}</li>
 *   <li>{@code APP_SERVICES_BOOKING_READ_TIMEOUT} → {@code app.services.booking-read-timeout} (기본 300ms, CAT-03 잔여 좌석)</li>
 *   <li>{@code APP_SCHEDULE_ENABLED}·{@code APP_SCHEDULE_DAYS}·{@code APP_SCHEDULE_INTERVAL} → {@code app.schedule.*}
 *       (상영 일정 이어 붙이기 — 기본 켜짐·오늘부터 7일·1시간마다, {@code ScheduleExtender})</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app")
public record CatalogProperties(Cache cache, Services services, @DefaultValue Schedule schedule) {

    public record Cache(@DefaultValue("10m") Duration ttl) {
    }

    public record Services(
        @DefaultValue("http://booking:8080") String bookingUrl,
        @DefaultValue("300ms") Duration bookingReadTimeout
    ) {
    }

    public record Schedule(@DefaultValue("true") boolean enabled, @DefaultValue("7") int days) {
    }
}

package com.seatsac.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GatewayWebUnitTest {

    @Test
    void CloudFront_Viewer_Address에서_포트를_뗀다() {
        assertThat(TraceWebFilter.stripPort("198.51.100.10:46532")).isEqualTo("198.51.100.10");
        assertThat(TraceWebFilter.stripPort("2001:db8::1:46532")).isEqualTo("2001:db8::1");
        assertThat(TraceWebFilter.stripPort("[2001:db8::1]:46532")).isEqualTo("2001:db8::1");
        assertThat(TraceWebFilter.stripPort("198.51.100.10")).isEqualTo("198.51.100.10");
    }

    @Test
    void 경로_조작을_막는다() {
        assertThat(PathGuardWebFilter.isUnsafe("/api/movies/../internal/x")).isTrue();
        assertThat(PathGuardWebFilter.isUnsafe("/api/movies/./1")).isTrue();
        assertThat(PathGuardWebFilter.isUnsafe("/api/movies/%2e%2e")).isTrue();  // 이중 인코딩이 디코딩 후 남은 경우
        assertThat(PathGuardWebFilter.isUnsafe("/api/movies;jsessionid=1")).isTrue();
        assertThat(PathGuardWebFilter.isUnsafe("/api/movies\\1")).isTrue();
        assertThat(PathGuardWebFilter.isUnsafe("/api/screenings/7/seats")).isFalse();
    }

    @Test
    void api_아래_internal_actuator_세그먼트는_막는다() {
        assertThat(PathGuardWebFilter.isBlocked("/api/internal/bookings")).isTrue();
        assertThat(PathGuardWebFilter.isBlocked("/api/screenings/internal/1")).isTrue();
        assertThat(PathGuardWebFilter.isBlocked("/api/movies/actuator/health")).isTrue();
        assertThat(PathGuardWebFilter.isBlocked("/actuator/health/readiness")).isFalse(); // Gateway 자신의 헬스 체크
        assertThat(PathGuardWebFilter.isBlocked("/api/movies/internals")).isFalse();
    }
}

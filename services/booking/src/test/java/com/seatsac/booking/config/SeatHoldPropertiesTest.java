package com.seatsac.booking.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 선점 TTL 설정 {@code app.seat-hold.ttl-seconds} — 기본값·범위 (런타임 계약 3장 APP_SEAT_HOLD_TTL_SECONDS) */
class SeatHoldPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
        .withUserConfiguration(SeatHoldConfig.class);

    @Test
    void defaultsTo300Seconds() {
        runner.run(context -> assertThat(context.getBean(SeatHoldProperties.class).holdTtl())
            .isEqualTo(Duration.ofSeconds(300)));
    }

    @Test
    void readsTtlFromSetting() {
        runner.withPropertyValues("app.seat-hold.ttl-seconds=60")
            .run(context -> assertThat(context.getBean(SeatHoldProperties.class).holdTtl())
                .isEqualTo(Duration.ofSeconds(60)));
    }

    /** 범위 경계 30·600초는 받는다 */
    @Test
    void acceptsBoundaryValues() {
        runner.withPropertyValues("app.seat-hold.ttl-seconds=30")
            .run(context -> assertThat(context.getBean(SeatHoldProperties.class).holdTtl())
                .isEqualTo(Duration.ofSeconds(30)));
        runner.withPropertyValues("app.seat-hold.ttl-seconds=600")
            .run(context -> assertThat(context.getBean(SeatHoldProperties.class).holdTtl())
                .isEqualTo(Duration.ofSeconds(600)));
    }

    /** 잘못된 ConfigMap 값을 조용히 쓰지 않는다 — 기동이 실패한다 */
    @Test
    void rejectsValuesOutsideRange() {
        runner.withPropertyValues("app.seat-hold.ttl-seconds=10")
            .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("app.seat-hold.ttl-seconds=601")
            .run(context -> assertThat(context).hasFailed());
    }
}

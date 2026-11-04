package com.seatsac.booking.component;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatsac.booking.common.RedisKeys;
import com.seatsac.booking.config.RedisConfig;
import com.seatsac.booking.config.SeatHoldConfig;
import com.seatsac.common.test.ValkeyContainerSupport;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 설정으로 선점 TTL을 줄이면(데모 60초) Valkey 좌석·user-hold 키의 EX도 그 값을 따른다 */
@DataRedisTest(properties = "app.seat-hold.ttl-seconds=60")
@Import({ValkeyContainerSupport.class, RedisConfig.class, SeatHoldConfig.class, SeatHoldRedisRepository.class})
class SeatHoldTtlSettingTest {

    private static final Long SCREENING = 7L;
    private static final String USER = "33333333-3333-4333-8333-333333333333";

    @Autowired
    SeatHoldRedisRepository repository;

    @Autowired
    StringRedisTemplate redis;

    @Test
    void seatAndUserHoldKeysUseConfiguredTtl() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        String holdId = UUID.randomUUID().toString();

        assertThat(repository.hold(SCREENING, USER, holdId, List.of(301L),
            Instant.now().plusSeconds(60)).isOk()).isTrue();

        assertThat(redis.getExpire(RedisKeys.seat(SCREENING, 301L), TimeUnit.SECONDS)).isBetween(55L, 60L);
        assertThat(redis.getExpire(RedisKeys.userHold(SCREENING, USER), TimeUnit.SECONDS)).isBetween(55L, 60L);
    }
}

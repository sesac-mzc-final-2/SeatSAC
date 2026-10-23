package com.seatsac.booking.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Valkey Lua 스크립트 빈 — `redis-usage.md` 6장.
 * 스크립트는 파일로 두고 기동 시 한 번 읽는다(Spring이 SHA로 캐시해 EVALSHA로 보낸다).
 */
@Configuration(proxyBeanMethods = false)
public class RedisConfig {

    /** 좌석 선점. 반환 {@code {'OK'}} · {@code {'ACTIVE_HOLD', holdId}} · {@code {'SEAT_TAKEN', 좌석ID...}} */
    @Bean
    @SuppressWarnings("unchecked")
    public RedisScript<List> holdScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/hold.lua"));
        script.setResultType(List.class);
        return script;
    }

    /** 선점 해제. 반환 = 지운 좌석 키 개수 */
    @Bean
    public RedisScript<Long> releaseScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/release.lua"));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 멱등 키를 내가 잡은 처리권일 때만 바꾼다. 반환 1 = 바꿨다, 0 = 남의 처리권이라 그대로 뒀다.
     * 단일 명령으로 안 되는 「값을 보고 쓰기」라서 Lua를 쓴다(`redis-usage.md` 8장).
     */
    @Bean
    public RedisScript<Long> idempotencyWriteScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/idem-write.lua"));
        script.setResultType(Long.class);
        return script;
    }
}

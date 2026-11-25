package com.seatsac.coupon.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/** Valkey Lua 스크립트 — 멱등 쓰기는 booking과 같은 파일({@code redis/idem-write.lua}) */
@Configuration(proxyBeanMethods = false)
public class RedisConfig {

    /** 멱등 키를 내가 잡은 처리권일 때만 바꾼다. 반환 1 = 바꿨다, 0 = 남의 처리권이라 그대로 뒀다 */
    @Bean
    public RedisScript<Long> idempotencyWriteScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/idem-write.lua"));
        script.setResultType(Long.class);
        return script;
    }

    /** 재고 차감 — 키가 있을 때만 DECR. 반환 {1, 값} · {0} 키 없음 */
    @Bean
    @SuppressWarnings("rawtypes")
    public RedisScript<List> stockDecrScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/stock-decr.lua"));
        script.setResultType(List.class);
        return script;
    }

    /** 재고 되돌림 — 키가 있을 때만 INCR(없는 키를 1로 만들지 않는다). 반환 {1, 값} · {0} 키 없음 */
    @Bean
    @SuppressWarnings("rawtypes")
    public RedisScript<List> stockIncrScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/stock-incr.lua"));
        script.setResultType(List.class);
        return script;
    }
}

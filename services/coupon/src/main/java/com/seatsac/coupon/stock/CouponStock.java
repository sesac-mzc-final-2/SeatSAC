package com.seatsac.coupon.stock;

import com.seatsac.coupon.common.RedisKeys;
import com.seatsac.coupon.entity.CouponEvent;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.LongSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 선착순 재고 {@code coupon:stock:{eventId}} — DB까지 오는 요청을 줄이는 <b>앞단 거름망</b>이다(최종 방어선은 DB의
 * 조건부 UPDATE·UK). 불변식은 {@code Valkey 재고 + 발급 수 ≤ total_quantity}(data-model 7-2): 결과를 모르는 {@code DECR}은
 * 되돌리지 않아 덜 발급되는 쪽으로 틀린다.
 *
 * <p>Valkey 오류는 그대로 올린다({@code DataAccessException}) — 부르는 쪽이 503으로 바꾸고 되돌리지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponStock {

    /** 키 TTL = 종료 + 1일 (conventions 4장) */
    static final Duration KEEP_AFTER_END = Duration.ofDays(1);

    private final StringRedisTemplate redis;
    private final RedisScript<List> stockDecrScript;
    private final RedisScript<List> stockIncrScript;
    private final Clock clock;

    /** 이벤트를 만들 때 — 이미 있으면 그대로 둔다(NX) */
    public void create(CouponEvent event) {
        redis.opsForValue().setIfAbsent(RedisKeys.stock(event.getEventId()),
            String.valueOf(event.getTotalQuantity()), ttl(event));
    }

    /**
     * 1장 차감. 키가 없으면 {@code total − 발급 수}로 다시 만든 뒤 한 번 더 시도한다(재시작·장애 조치로 사라진 경우).
     * 다시 만든 값은 진행 중이던 발급을 세지 못해 실제보다 클 수 있지만, DB가 총수량을 넘기지 않는다.
     *
     * @return 차감 뒤 값. 0 이상이면 이번 요청이 재고를 잡았다, 음수면 매진(부르는 쪽이 {@link #giveBack}으로 되돌린다)
     */
    public long take(CouponEvent event, LongSupplier issuedInDb) {
        String key = RedisKeys.stock(event.getEventId());
        Long value = decr(key);
        if (value == null) {
            long rebuilt = Math.max(0, event.getTotalQuantity() - issuedInDb.getAsLong());
            Boolean created = redis.opsForValue().setIfAbsent(key, String.valueOf(rebuilt), ttl(event));
            log.warn("재고 키가 없어 다시 만든다 eventId={} 값={} 새로만듦={}", event.getEventId(), rebuilt, created);
            value = decr(key);
            if (value == null) {
                throw new IllegalStateException("재고 키를 만들었는데 바로 사라졌다 eventId=" + event.getEventId());
            }
        }
        return value;
    }

    /**
     * {@link #take}가 성공한 뒤에만 부른다 — 결과가 음수였거나 DB 발급이 실패했을 때. 그 사이 키가 사라졌으면 되돌리지
     * 않는다(없는 키를 1로 만들면 재고가 그 값에 굳는다 — 다음 take가 DB로 다시 만들게 둔다)
     */
    public void giveBack(long eventId) {
        List<?> result = redis.execute(stockIncrScript, List.of(RedisKeys.stock(eventId)));
        if (result == null || result.isEmpty() || ((Number) result.get(0)).longValue() == 0) {
            log.warn("되돌릴 재고 키가 없다 — 다음 발급이 DB로 다시 만든다 eventId={}", eventId);
        }
    }

    /** 표시용 남은 수량 — 키 값, 없으면 {@code total − 발급 수}. 음수는 0. Valkey 오류면 DB 값으로 답한다 */
    public int remaining(CouponEvent event) {
        long fromDb = Math.max(0, event.getTotalQuantity() - event.getIssuedQuantity());
        try {
            String value = redis.opsForValue().get(RedisKeys.stock(event.getEventId()));
            return (int) (value == null ? fromDb : Math.max(0, Long.parseLong(value)));
        } catch (RuntimeException e) {
            log.warn("재고 키를 읽지 못해 DB 값으로 답한다 eventId={}: {}", event.getEventId(), e.getMessage());
            return (int) fromDb;
        }
    }

    private Long decr(String key) {
        List<?> result = redis.execute(stockDecrScript, List.of(key));
        if (result == null || result.isEmpty() || ((Number) result.get(0)).longValue() == 0) {
            return null;
        }
        return ((Number) result.get(1)).longValue();
    }

    private Duration ttl(CouponEvent event) {
        Duration left = Duration.between(clock.instant(), event.getEndsAt().plus(KEEP_AFTER_END));
        return left.isNegative() || left.isZero() ? Duration.ofMinutes(1) : left;
    }
}

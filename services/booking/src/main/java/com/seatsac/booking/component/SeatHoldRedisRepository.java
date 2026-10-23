package com.seatsac.booking.component;

import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.common.RedisKeys;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Valkey 선점 — `redis-usage.md` 5·6장. <b>단순 외부 호출만</b> 한다(지침 4장).
 * 트랜잭션 안에서 부르지 않는다. 발행(INCR·PUBLISH)은 커밋 후 흐름 서비스가 한다.
 */
@Component
@RequiredArgsConstructor
public class SeatHoldRedisRepository implements SeatHoldStrategy {

    private static final String OK = "OK";
    private static final String ACTIVE_HOLD = "ACTIVE_HOLD";
    private static final String SEAT_TAKEN = "SEAT_TAKEN";

    private final StringRedisTemplate redis;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> holdScript;
    private final RedisScript<Long> releaseScript;

    @Override
    @SuppressWarnings("unchecked")
    public HoldOutcome hold(Long screeningId, String userId, String holdId, List<Long> seatIds,
                            Instant expiresAt) {
        List<String> keys = keys(screeningId, userId, seatIds);

        // ARGV: holdId, TTL초, 만료 ms, 장부 멤버, 좌석 ID...(KEYS[3..]과 같은 순서)
        List<String> args = new ArrayList<>(List.of(
            holdId,
            String.valueOf(BookingPolicy.HOLD_TTL.toSeconds()),
            String.valueOf(expiresAt.toEpochMilli()),
            RedisKeys.holdExpiryMember(screeningId, holdId)));
        seatIds.forEach(seatId -> args.add(String.valueOf(seatId)));

        List<String> result = redis.execute(holdScript, keys, args.toArray());
        if (result == null || result.isEmpty()) {
            throw new IllegalStateException("hold.lua 반환이 비어 있다 screeningId=" + screeningId);
        }
        return switch (result.get(0)) {
            case OK -> HoldOutcome.ok();
            case ACTIVE_HOLD -> HoldOutcome.activeHold(result.get(1));
            case SEAT_TAKEN -> HoldOutcome.seatTaken(result.subList(1, result.size()).stream()
                .map(Long::valueOf).toList());
            default -> throw new IllegalStateException("hold.lua 알 수 없는 반환 " + result.get(0));
        };
    }

    @Override
    public long release(Long screeningId, String userId, String holdId, List<Long> seatIds) {
        Long removed = redis.execute(releaseScript, keys(screeningId, userId, seatIds),
            holdId, RedisKeys.holdExpiryMember(screeningId, holdId));
        return removed == null ? 0L : removed;
    }

    /**
     * 선점 중인 좌석을 한 번에 읽는다 (BKG-01 5단계). {@code KEYS seat:*}는 쓰지 않는다.
     *
     * @return 선점된 좌석 ID → holdId. 키가 없는 좌석은 결과에 없다
     */
    public Map<Long, String> findHeldSeats(Long screeningId, List<Long> seatIds) {
        if (seatIds.isEmpty()) {
            return Map.of();
        }
        List<String> values = redis.opsForValue()
            .multiGet(seatIds.stream().map(seatId -> RedisKeys.seat(screeningId, seatId)).toList());
        if (values == null) {
            return Map.of();
        }
        return java.util.stream.IntStream.range(0, seatIds.size())
            .filter(i -> values.get(i) != null)
            .boxed()
            .collect(Collectors.toMap(seatIds::get, values::get));
    }

    /** KEYS[1] user-hold · KEYS[2] hold-expiry · KEYS[3..] 좌석 키 */
    private static List<String> keys(Long screeningId, String userId, List<Long> seatIds) {
        List<String> keys = new ArrayList<>(seatIds.size() + 2);
        keys.add(RedisKeys.userHold(screeningId, userId));
        keys.add(RedisKeys.holdExpiry());
        seatIds.forEach(seatId -> keys.add(RedisKeys.seat(screeningId, seatId)));
        return keys;
    }

    @Override
    public Optional<Instant> expiryOf(Long screeningId, String holdId) {
        Double score = redis.opsForZSet()
            .score(RedisKeys.holdExpiry(), RedisKeys.holdExpiryMember(screeningId, holdId));
        return score == null ? Optional.empty() : Optional.of(Instant.ofEpochMilli(score.longValue()));
    }

    @Override
    public List<ExpiringHold> dueHolds(Instant now, int offset, int limit) {
        Set<String> members = redis.opsForZSet().rangeByScore(RedisKeys.holdExpiry(),
            Double.NEGATIVE_INFINITY, now.toEpochMilli(), offset, limit);
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        return members.stream().map(RedisKeys::parseHoldExpiryMember)
            .flatMap(Optional::stream)
            .map(parsed -> new ExpiringHold(parsed.screeningId(), parsed.holdId()))
            .toList();
    }

    @Override
    public long forget(Long screeningId, String holdId) {
        Long removed = redis.opsForZSet().remove(RedisKeys.holdExpiry(),
            RedisKeys.holdExpiryMember(screeningId, holdId));
        return removed == null ? 0L : removed;
    }
}

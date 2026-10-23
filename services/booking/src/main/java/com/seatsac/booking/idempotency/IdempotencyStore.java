package com.seatsac.booking.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.common.RedisKeys;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 멱등 키 저장소 — Valkey {@code idem:{userId}:{Idempotency-Key}}, TTL 24시간.
 * 단일 명령({@code SET NX EX})으로 되는 일이라 Lua를 쓰지 않는다(`redis-usage.md` 8장).
 *
 * <p>키 안에 사용자 ID를 두어 다른 사용자가 같은 {@code Idempotency-Key}를 보내도
 * 남의 예매 ID·금액이 담긴 응답을 받지 못한다(N25).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyStore {

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    /** 요청 본문 해시 — 같은 바이트면 같은 값이라야 하므로 직렬화한 본문을 그대로 해싱한다 */
    public String hash(byte[] requestBody) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(requestBody));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없다", e);
        }
    }

    /**
     * 처리권을 잡는다. 비어 있으면 {@code IN_PROGRESS}로 선점하고 {@link Optional#empty()},
     * 이미 있으면 그 값을 돌려준다(호출자가 409 또는 응답 재생을 고른다).
     *
     * <p>{@code IN_PROGRESS}는 <b>짧은 TTL</b>이다
     * ({@link BookingPolicy#IDEMPOTENCY_IN_PROGRESS_TTL}) — 처리 도중 파드가 죽으면 키가 남는데,
     * 24시간이면 그 키로 들어오는 재시도가 하루 동안 409를 받는다. 첫 응답을 저장할 때
     * 24시간으로 늘린다.
     *
     * <p>Valkey를 읽지 못하면 그대로 올려 보낸다 — 멱등 보장을 못 하는 쓰기는 받지 않는다.
     */
    public Optional<IdempotencyRecord> begin(String userId, String key, String requestHash) {
        String redisKey = RedisKeys.idempotency(userId, key);
        String fresh = serialize(IdempotencyRecord.inProgress(requestHash));

        if (Boolean.TRUE.equals(redis.opsForValue()
            .setIfAbsent(redisKey, fresh, BookingPolicy.IDEMPOTENCY_IN_PROGRESS_TTL))) {
            return Optional.empty();
        }

        String stored = redis.opsForValue().get(redisKey);
        if (stored == null) {
            // 확인하는 사이 24시간 TTL이 끝났다. 한 번 더 잡아 보고, 그래도 안 되면 처리 중으로 본다
            if (Boolean.TRUE.equals(redis.opsForValue()
                .setIfAbsent(redisKey, fresh, BookingPolicy.IDEMPOTENCY_IN_PROGRESS_TTL))) {
                return Optional.empty();
            }
            return Optional.of(IdempotencyRecord.inProgress(requestHash));
        }
        return Optional.of(deserialize(stored, requestHash));
    }

    /**
     * 첫 응답을 저장한다(4xx도 저장 — 같은 키로 다시 와도 같은 거절을 받는다).
     *
     * <p>여기서 실패해도 응답은 그대로 내보낸다. 예매는 이미 커밋됐으니 저장 실패로 500을 내면
     * 사용자가 성공한 예매를 못 받는다. 그 경우 키는 {@code IN_PROGRESS}로 남아 60초 동안
     * 같은 키 재시도에 409를 돌려주고, TTL이 끝나면 다시 실행된다 — 그때는 {@code user-hold}와
     * RDS {@code PENDING}이 있어 409 {@code ACTIVE_HOLD_EXISTS}로 막힌다.
     */
    public void complete(String userId, String key, String requestHash, int httpStatus, String body) {
        try {
            redis.opsForValue().set(RedisKeys.idempotency(userId, key),
                serialize(IdempotencyRecord.done(requestHash, httpStatus, body)), BookingPolicy.IDEMPOTENCY_TTL);
        } catch (DataAccessException e) {
            log.warn("멱등 응답 저장 실패 — 같은 키로 다시 오면 재실행된다 status={}", httpStatus, e);
        }
    }

    /** 5xx로 끝났으면 키를 지워 재시도를 허용한다 (C11) */
    public void discard(String userId, String key) {
        try {
            redis.delete(RedisKeys.idempotency(userId, key));
        } catch (DataAccessException e) {
            log.warn("멱등 키 삭제 실패 — 24시간 뒤 TTL로 사라진다", e);
        }
    }

    private String serialize(IdempotencyRecord record) {
        try {
            return mapper.writeValueAsString(record);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("멱등 저장 값을 직렬화할 수 없다", e);
        }
    }

    /** 남의 코드가 넣은 값이나 형식이 바뀐 옛 값이면 처리 중으로 보고 409를 내보낸다(응답을 섞지 않는다) */
    private IdempotencyRecord deserialize(String stored, String requestHash) {
        try {
            return mapper.readValue(stored, IdempotencyRecord.class);
        } catch (JsonProcessingException e) {
            log.warn("멱등 저장 값을 읽을 수 없다 — 처리 중으로 본다", e);
            return IdempotencyRecord.inProgress(requestHash);
        }
    }
}

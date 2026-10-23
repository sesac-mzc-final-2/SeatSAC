package com.seatsac.booking.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.common.RedisKeys;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 멱등 키 저장소 — Valkey {@code idem:{userId}:{Idempotency-Key}}.
 *
 * <p>키 안에 사용자 ID를 두어 다른 사용자가 같은 {@code Idempotency-Key}를 보내도
 * 남의 예매 ID·금액이 담긴 응답을 받지 못한다(N25).
 *
 * <p>TTL이 두 가지다 — 처리 중({@code IN_PROGRESS})은 짧게, 첫 응답({@code DONE})은 24시간.
 * 그리고 <b>처리권(owner)</b>을 둔다: 처리 중 TTL이 끝난 뒤 같은 키로 새 요청이 들어와 제 응답을
 * 저장할 수 있는데, 그때 늦게 끝난 원래 요청이 그냥 덮어쓰면 남의 응답이 사라진다.
 * 그래서 저장·삭제는 {@code idem-write.lua}로 <b>내 처리권일 때만</b> 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyStore {

    private static final String SET = "SET";
    private static final String DELETE = "DEL";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final RedisScript<Long> idempotencyWriteScript;

    /** 요청 본문 해시 — 같은 바이트면 같은 값이라야 하므로 직렬화한 본문을 그대로 해싱한다 */
    public String hash(byte[] requestBody) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(requestBody));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없다", e);
        }
    }

    /**
     * 처리권을 잡는다.
     *
     * <p>Valkey를 읽지 못하면 그대로 올려 보낸다 — 멱등 보장을 못 하는 쓰기는 받지 않는다.
     *
     * @return 처음 온 요청이면 {@link Begin#acquired()}가 참이고 {@code owner}가 담긴다.
     *         이미 있으면 {@code existing}에 그 값이 담긴다(호출자가 409 또는 응답 재생을 고른다).
     */
    public Begin begin(String userId, String key, String requestHash) {
        String redisKey = RedisKeys.idempotency(userId, key);
        String owner = UUID.randomUUID().toString();
        String fresh = serialize(IdempotencyRecord.inProgress(requestHash, owner));

        if (Boolean.TRUE.equals(redis.opsForValue()
            .setIfAbsent(redisKey, fresh, BookingPolicy.IDEMPOTENCY_IN_PROGRESS_TTL))) {
            return Begin.acquired(owner);
        }

        String stored = redis.opsForValue().get(redisKey);
        if (stored == null) {
            // 확인하는 사이 TTL이 끝났다. 한 번 더 잡아 보고, 그래도 안 되면 처리 중으로 본다
            if (Boolean.TRUE.equals(redis.opsForValue()
                .setIfAbsent(redisKey, fresh, BookingPolicy.IDEMPOTENCY_IN_PROGRESS_TTL))) {
                return Begin.acquired(owner);
            }
            return Begin.existing(IdempotencyRecord.inProgress(requestHash, null));
        }
        return Begin.existing(deserialize(stored, requestHash));
    }

    /**
     * 첫 응답을 저장한다(4xx도 저장 — 같은 키로 다시 와도 같은 거절을 받는다).
     * TTL은 24시간으로 늘린다.
     *
     * <p>내 처리권이 아니면(TTL이 끝나고 다른 요청이 그 키를 쓰고 있으면) <b>아무것도 하지 않는다.</b>
     * 저장에 실패해도 응답은 그대로 내보낸다 — 예매는 이미 커밋됐으니 저장 실패로 500을 내면
     * 사용자가 성공한 예매를 못 받는다.
     */
    public void complete(String userId, String key, String owner, String requestHash, int httpStatus,
                         String body) {
        String value = serialize(IdempotencyRecord.done(requestHash, owner, httpStatus, body));
        if (!write(userId, key, owner, SET, value, BookingPolicy.IDEMPOTENCY_TTL.toSeconds())) {
            log.warn("멱등 응답을 저장하지 못했다 — 내 처리권이 아니거나 키가 만료됐다 status={}",
                httpStatus);
        }
    }

    /** 5xx로 끝났으면 키를 지워 재시도를 허용한다 (C11). 내 처리권일 때만 지운다 */
    public void discard(String userId, String key, String owner) {
        write(userId, key, owner, DELETE, "", 0);
    }

    private boolean write(String userId, String key, String owner, String mode, String value,
                          long ttlSeconds) {
        try {
            Long changed = redis.execute(idempotencyWriteScript,
                List.of(RedisKeys.idempotency(userId, key)),
                owner, mode, value, String.valueOf(ttlSeconds));
            return changed != null && changed == 1L;
        } catch (DataAccessException e) {
            // 키는 IN_PROGRESS로 남는다 → 그 TTL(선점과 같은 300초) 동안 같은 키 재시도가
            // 409 IDEMPOTENCY_IN_PROGRESS를 받고, 그 뒤에는 다시 실행된다
            log.warn("멱등 키를 쓰지 못했다 mode={} — 처리 중 상태로 남아 {}초 뒤 만료된다", mode,
                BookingPolicy.IDEMPOTENCY_IN_PROGRESS_TTL.toSeconds(), e);
            return false;
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
            return IdempotencyRecord.inProgress(requestHash, null);
        }
    }

    /**
     * 처리권 획득 결과.
     *
     * @param owner    내가 잡았을 때만 채워진다. 저장·삭제에 그대로 넘긴다
     * @param existing 이미 있던 값 (잡지 못했을 때만)
     */
    public record Begin(String owner, IdempotencyRecord existing) {

        public static Begin acquired(String owner) {
            return new Begin(owner, null);
        }

        public static Begin existing(IdempotencyRecord record) {
            return new Begin(null, record);
        }

        public boolean acquired() {
            return owner != null;
        }

        public Optional<IdempotencyRecord> existingRecord() {
            return Optional.ofNullable(existing);
        }
    }
}

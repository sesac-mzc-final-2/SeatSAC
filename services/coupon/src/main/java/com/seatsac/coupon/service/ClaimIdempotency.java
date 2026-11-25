package com.seatsac.coupon.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.common.core.error.ErrorDetail;
import com.seatsac.coupon.common.CouponErrorCode;
import com.seatsac.coupon.common.RedisKeys;
import com.seatsac.coupon.config.CouponProperties;
import com.seatsac.coupon.dto.CouponIssueResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 발급 요청 멱등 {@code coupon:idem:{userId}:{Idempotency-Key}} — booking {@code idem:}과 같은 모양(conventions 4장).
 * 처리권을 {@code SET NX}로 잡고(처리 중), 끝나면 첫 결과(201 또는 409)를 저장해 같은 키 재요청에 그대로 돌려준다.
 * 저장·삭제는 내가 잡은 처리권일 때만({@code idem-write.lua}) — 늦게 끝난 요청이 남의 결과를 덮지 않게.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaimIdempotency {

    /** 처리 중 표시 TTL — 파드가 처리 중에 죽어도 이만큼 뒤에는 같은 키로 다시 할 수 있다 */
    static final Duration IN_PROGRESS_TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;
    private final RedisScript<Long> idempotencyWriteScript;
    private final ObjectMapper objectMapper;
    private final CouponProperties properties;

    /**
     * 저장 값. {@code requestHash}는 이 API에서는 eventId다(본문이 없다). {@code issue}·{@code error}는 DONE일 때 하나만.
     */
    public record Record(String status, String requestHash, String owner, CouponIssueResponse issue,
                         String error, List<ErrorDetail> details) {

        static final String IN_PROGRESS = "IN_PROGRESS";
        static final String DONE = "DONE";

        boolean done() {
            return DONE.equals(status);
        }
    }

    /** {@link #begin} 결과 — 처리권을 잡았거나(owner) 저장된 첫 결과를 돌려줄 차례 */
    public record Begin(String owner, Record replay) {
        public boolean isReplay() {
            return replay != null;
        }
    }

    /**
     * @throws com.seatsac.common.core.error.BusinessException 같은 키·다른 eventId(409 KEY_CONFLICT), 처리 중(409 IN_PROGRESS)
     */
    public Begin begin(String userId, String key, long eventId) {
        String redisKey = RedisKeys.idempotency(userId, key);
        String hash = String.valueOf(eventId);
        String owner = UUID.randomUUID().toString();
        Boolean claimed = redis.opsForValue().setIfAbsent(redisKey,
            write(new Record(Record.IN_PROGRESS, hash, owner, null, null, null)), IN_PROGRESS_TTL);
        if (Boolean.TRUE.equals(claimed)) {
            return new Begin(owner, null);
        }
        Record existing = read(redis.opsForValue().get(redisKey));
        if (existing == null) {
            // 사이에 만료됐다 — 한 번 더 잡아 본다
            claimed = redis.opsForValue().setIfAbsent(redisKey,
                write(new Record(Record.IN_PROGRESS, hash, owner, null, null, null)), IN_PROGRESS_TTL);
            if (Boolean.TRUE.equals(claimed)) {
                return new Begin(owner, null);
            }
            throw CouponErrorCode.IDEMPOTENCY_IN_PROGRESS.exception();
        }
        if (existing.requestHash() == null) {
            throw CouponErrorCode.IDEMPOTENCY_IN_PROGRESS.exception();   // 읽지 못한 값 — 처리 중으로 본다
        }
        if (!hash.equals(existing.requestHash())) {
            throw CouponErrorCode.IDEMPOTENCY_KEY_CONFLICT.exception();
        }
        if (!existing.done()) {
            throw CouponErrorCode.IDEMPOTENCY_IN_PROGRESS.exception();
        }
        return new Begin(null, existing);
    }

    public void completeIssued(String userId, String key, long eventId, String owner, CouponIssueResponse issue) {
        save(userId, key, owner, new Record(Record.DONE, String.valueOf(eventId), owner, issue, null, null));
    }

    public void completeError(String userId, String key, long eventId, String owner, CouponErrorCode error,
                              List<ErrorDetail> details) {
        save(userId, key, owner, new Record(Record.DONE, String.valueOf(eventId), owner, null, error.name(), details));
    }

    /** 결과를 정하지 못했다(503·예상 못 한 오류) — 지워서 같은 키로 다시 할 수 있게 한다 */
    public void abandon(String userId, String key, String owner) {
        try {
            redis.execute(idempotencyWriteScript, List.of(RedisKeys.idempotency(userId, key)), owner, "DEL");
        } catch (RuntimeException e) {
            log.warn("멱등 키를 지우지 못했다 — {} 뒤 풀린다: {}", IN_PROGRESS_TTL, e.getMessage());
        }
    }

    private void save(String userId, String key, String owner, Record record) {
        try {
            redis.execute(idempotencyWriteScript, List.of(RedisKeys.idempotency(userId, key)), owner, "SET",
                write(record), String.valueOf(properties.idempotencyTtl().toSeconds()));
        } catch (RuntimeException e) {
            // 결과는 이미 정해졌다(DB 커밋). 저장만 못 했으니 같은 키 재요청은 다시 처리되고 UK·상한이 같은 답을 준다
            log.warn("멱등 결과를 저장하지 못했다: {}", e.getMessage());
        }
    }

    private String write(Record record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Record read(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, Record.class);
        } catch (JsonProcessingException e) {
            log.warn("멱등 값을 읽지 못했다 — 처리 중으로 본다");
            return new Record(Record.IN_PROGRESS, null, null, null, null, null);
        }
    }
}

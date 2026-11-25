package com.seatsac.coupon.service;

import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.CommonResponseCode;
import com.seatsac.common.core.error.ErrorDetail;
import com.seatsac.common.core.http.Headers;
import com.seatsac.common.core.time.Times;
import com.seatsac.coupon.common.CouponErrorCode;
import com.seatsac.coupon.common.CouponHeaders;
import com.seatsac.coupon.dto.CouponIssueResponse;
import com.seatsac.coupon.entity.CouponEvent;
import com.seatsac.coupon.entity.CouponEventState;
import com.seatsac.coupon.entity.CouponIssue;
import com.seatsac.coupon.repository.CouponEventRepository;
import com.seatsac.coupon.repository.CouponIssueRepository;
import com.seatsac.coupon.stock.CouponStock;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * CPN-02 선착순 발급(22-2, coupon.openapi.yaml claim). 판정 순서:
 * 등급(403, 재고·DB를 건드리지 않음) → 멱등 처리권 → 이벤트 시각(409 NOT_OPEN) → 이미 받았나(409 ALREADY_CLAIMED)
 * → Valkey {@code DECR}(음수면 되돌리고 매진) → DB 한 트랜잭션(상한 +1 · INSERT).
 *
 * <p>재고 되돌림({@code INCR})은 {@code DECR}이 성공한 뒤 결과가 음수이거나 DB 발급이 실패했을 때만 — 결과를 모르는
 * {@code DECR}(Valkey 오류)은 되돌리지 않는다(초과 발급 대신 덜 발급, data-model 7-2).
 * 매진 분기에서도 이 사용자의 기존 발급을 먼저 본다 — 받은 사람에게는 SOLD_OUT 대신 ALREADY_CLAIMED(이미 받은 ID).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponClaimService {

    private static final Pattern UUID_FORMAT =
        Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final CouponEventRepository events;
    private final CouponIssueRepository issues;
    private final CouponStock stock;
    private final ClaimTx claimTx;
    private final ClaimIdempotency idempotency;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public CouponIssueResponse claim(String userId, String gradeHeader, long eventId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(CommonResponseCode.VALIDATION_FAILED,
                List.of(ErrorDetail.required(Headers.IDEMPOTENCY_KEY)));
        }
        if (!UUID_FORMAT.matcher(idempotencyKey).matches()) {
            throw CouponErrorCode.INVALID_IDEMPOTENCY_KEY.exception(
                ErrorDetail.of(Headers.IDEMPOTENCY_KEY, null, "INVALID_FORMAT"));
        }
        CouponEvent event = events.findById(eventId).orElseThrow(CouponErrorCode.EVENT_NOT_FOUND::exception);
        String grade = CouponHeaders.gradeOrNormal(gradeHeader);
        if (!grade.equals(event.getEligibleGrade())) {
            count("not_eligible");
            throw CouponErrorCode.COUPON_NOT_ELIGIBLE.exception(ErrorDetail.of("grade", grade));
        }

        ClaimIdempotency.Begin begin;
        try {
            begin = idempotency.begin(userId, idempotencyKey, eventId);
        } catch (DataAccessException e) {
            throw new BusinessException(CouponErrorCode.STOCK_UNAVAILABLE, e);
        }
        if (begin.isReplay()) {
            return replay(begin.replay());
        }

        String owner = begin.owner();
        try {
            CouponIssueResponse issued = claimOnce(event, userId);
            idempotency.completeIssued(userId, idempotencyKey, eventId, owner, issued);
            return issued;
        } catch (BusinessException e) {
            if (e.code() instanceof CouponErrorCode code && e.code().status() == 409) {
                idempotency.completeError(userId, idempotencyKey, eventId, owner, code, e.details());
            } else {
                idempotency.abandon(userId, idempotencyKey, owner);
            }
            throw e;
        } catch (RuntimeException e) {
            idempotency.abandon(userId, idempotencyKey, owner);
            throw e;
        }
    }

    private CouponIssueResponse claimOnce(CouponEvent event, String userId) {
        Instant now = clock.instant();
        CouponEventState state = event.stateAt(now);
        if (state != CouponEventState.OPEN) {
            count("closed");
            throw CouponErrorCode.COUPON_EVENT_NOT_OPEN.exception(
                ErrorDetail.of("eventId", String.valueOf(event.getEventId()), state.name()));
        }
        Optional<CouponIssue> mine = issues.findByEventIdAndUserId(event.getEventId(), userId);
        if (mine.isPresent()) {
            throw alreadyClaimed(mine.get());
        }

        long left;
        try {
            left = stock.take(event, () -> issues.countByEventId(event.getEventId()));
        } catch (DataAccessException e) {
            // DECR 결과를 모른다 — 되돌리지 않는다(덜 발급되는 쪽)
            throw new BusinessException(CouponErrorCode.STOCK_UNAVAILABLE, e);
        }
        if (left < 0) {
            stock.giveBack(event.getEventId());
            throw soldOutOrAlreadyClaimed(event, userId);
        }

        Optional<CouponIssue> issued;
        try {
            issued = claimTx.issue(event, userId, now);
        } catch (DataIntegrityViolationException e) {
            stock.giveBack(event.getEventId());
            CouponIssue existing = issues.findByEventIdAndUserId(event.getEventId(), userId).orElseThrow(() -> e);
            throw alreadyClaimed(existing);
        } catch (RuntimeException e) {
            // DB 발급 실패 — DECR은 성공했으니 되돌린다(DB가 총수량의 최종 방어선이라 되돌려도 넘겨 발급하지 않는다)
            stock.giveBack(event.getEventId());
            throw e;
        }
        if (issued.isEmpty()) {
            stock.giveBack(event.getEventId());
            throw soldOutOrAlreadyClaimed(event, userId);
        }
        count("issued");
        log.info("쿠폰 발급 eventId={} couponIssueId={}", event.getEventId(), issued.get().getCouponIssueId());
        return CouponIssueResponse.of(issued.get(), event, now);
    }

    private BusinessException soldOutOrAlreadyClaimed(CouponEvent event, String userId) {
        Optional<CouponIssue> mine = issues.findByEventIdAndUserId(event.getEventId(), userId);
        if (mine.isPresent()) {
            return alreadyClaimed(mine.get());
        }
        count("sold_out");
        return CouponErrorCode.COUPON_SOLD_OUT.exception();
    }

    private BusinessException alreadyClaimed(CouponIssue issue) {
        count("duplicate");
        return CouponErrorCode.COUPON_ALREADY_CLAIMED.exception(ErrorDetail.of("couponIssueId", issue.getCouponIssueId()));
    }

    /** 같은 키·같은 eventId 재요청 — 첫 결과를 그대로 */
    private CouponIssueResponse replay(ClaimIdempotency.Record record) {
        if (record.issue() != null) {
            CouponIssueResponse issue = record.issue();
            // 저장·읽기를 거치면 오프셋이 UTC로 바뀔 수 있다 — 응답 규칙(+09:00)대로 되돌린다
            return new CouponIssueResponse(issue.couponIssueId(), issue.eventId(), issue.title(), issue.discountRate(),
                issue.state(), issue.expiresAt() == null ? null : issue.expiresAt().withOffsetSameInstant(Times.KST));
        }
        CouponErrorCode code = CouponErrorCode.valueOf(record.error());
        List<ErrorDetail> details = record.details() == null ? List.of() : record.details();
        throw new BusinessException(code, code.message(), details);
    }

    private void count(String result) {
        meterRegistry.counter("coupon_claim_total", "result", result).increment();
    }
}

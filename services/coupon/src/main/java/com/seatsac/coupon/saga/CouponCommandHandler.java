package com.seatsac.coupon.saga;

import com.seatsac.common.core.trace.TraceIds;
import com.seatsac.common.core.trace.TraceKeys;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * saga 명령 처리(6.3.2, 22-4) — coupon은 참여자다. 명령 3종(예약·사용·반환)을 inbox로 한 번만 처리한다.
 * 형식이 틀린 명령은 {@link MalformedCommandException}(리스너가 지운다, 04 5장), DB 오류 등 처리 중 장애만 예외로 올라가
 * 재배달 3회 뒤 DLQ로 간다. 업무 결과(거절)는 예외가 아니라 결과 메시지다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponCommandHandler {

    private static final Set<String> KNOWN_TYPES = Set.of(CouponMessages.RESERVATION_REQUESTED,
        CouponMessages.USE_REQUESTED, CouponMessages.RELEASE_REQUESTED);

    private final CouponMessages messages;
    private final CouponCommandTx tx;
    private final ProcessedMessageRepository inbox;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    /** 처리 결과(지표 라벨) */
    public enum Result { OK, REJECTED, DUPLICATE }

    /**
     * @throws MalformedCommandException 형식이 틀린 명령(재배달해도 같다 → 리스너가 지운다)
     */
    public Result handle(String body) {
        CouponMessages.Command command;
        try {
            command = messages.parse(body);
        } catch (MalformedCommandException e) {
            count("unknown", "error");
            throw e;
        }
        MDC.put(TraceKeys.MDC_TRACE_ID, TraceIds.isValid(command.traceId()) ? command.traceId() : TraceIds.newId());
        String result = "error";
        try {
            requireFields(command);
            if (inbox.existsById(command.eventId())) {
                result = "duplicate";
                log.info("이미 처리한 명령 — 결과를 다시 내지 않는다 eventId={} type={}", command.eventId(), command.type());
                return Result.DUPLICATE;
            }
            Result outcome = dispatch(command);
            result = outcome == Result.REJECTED ? "rejected" : "ok";
            return outcome;
        } catch (DataIntegrityViolationException e) {
            // inbox에 이 명령이 있을 때만 「같은 명령의 동시 처리」다. 그 밖의 유일 키 충돌(같은 checkoutId의 다른 명령이
            // 동시에 예약 행을 만들었다 등)은 처리되지 않은 것이라 예외를 올려 재배달한다 — 다음 배달은 생긴 행을 보고 처리한다
            if (inbox.existsById(command.eventId())) {
                result = "duplicate";
                return Result.DUPLICATE;
            }
            log.warn("명령 처리 중 유일 키 충돌 — 재배달한다 eventId={} type={}", command.eventId(), command.type());
            throw e;
        } finally {
            count(typeLabel(command.type()), result);
            MDC.remove(TraceKeys.MDC_TRACE_ID);
        }
    }

    private Result dispatch(CouponMessages.Command command) {
        return switch (command.type()) {
            case CouponMessages.RESERVATION_REQUESTED ->
                tx.reserve(command, clock.instant()) == CouponCommandTx.Outcome.REJECTED ? Result.REJECTED : Result.OK;
            case CouponMessages.USE_REQUESTED -> {
                tx.use(command, clock.instant());
                yield Result.OK;
            }
            case CouponMessages.RELEASE_REQUESTED -> {
                tx.release(command, clock.instant());
                yield Result.OK;
            }
            default -> throw new MalformedCommandException("모르는 명령 type=" + command.type());
        };
    }

    /** 계약의 payload 필수 값 — 없으면 재배달해도 같으니 형식 오류 */
    private static void requireFields(CouponMessages.Command command) {
        if (command.checkoutId() == null || command.couponIssueId() == null) {
            throw new MalformedCommandException("checkoutId·couponIssueId가 없다 type=" + command.type());
        }
        if (command.checkoutId().length() > CouponMessages.ID_MAX
            || command.couponIssueId().length() > CouponMessages.COUPON_ISSUE_ID_MAX) {
            throw new MalformedCommandException("checkoutId·couponIssueId가 저장 열보다 길다 type=" + command.type());
        }
        if (CouponMessages.RESERVATION_REQUESTED.equals(command.type())
            && (command.userId() == null || command.bookingId() == null)) {
            throw new MalformedCommandException("예약 명령에 userId·bookingId가 없다");
        }
    }

    static String typeLabel(String type) {
        return KNOWN_TYPES.contains(type) ? type : "unknown";
    }

    private void count(String type, String result) {
        meterRegistry.counter("coupon_command_total", "type", type, "result", result).increment();
    }
}

package com.seatsac.booking.service;

import com.seatsac.booking.common.BookingErrorCode;
import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.component.CatalogClient.Screening;
import com.seatsac.booking.component.ScreeningCache;
import com.seatsac.booking.component.ScreeningCache.SeatLayout;
import com.seatsac.booking.component.SeatEventPublisher;
import com.seatsac.booking.component.SeatHoldStrategy;
import com.seatsac.booking.component.SeatHoldStrategy.HoldOutcome;
import com.seatsac.booking.dto.request.SeatHoldRequest;
import com.seatsac.booking.dto.response.SeatHoldResponse;
import com.seatsac.booking.entity.Booking;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.booking.enums.SeatEventType;
import com.seatsac.booking.enums.SeatStatus;
import com.seatsac.booking.service.BookingStateService.PendingBooking;
import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.CommonResponseCode;
import com.seatsac.common.core.error.ErrorDetail;
import com.seatsac.common.core.time.Times;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * BKG-02 좌석 선점 흐름 — 명세 5장, `redis-usage.md` 5장. <b>트랜잭션이 없다.</b>
 * Valkey·Catalog 호출과 트랜잭션 서비스를 순서대로 부르는 것만 한다(지침 4장).
 *
 * <pre>
 * 1. 회차·좌석 검증 (파드 캐시, 없으면 Catalog)
 * 2. holdId 발급, expiresAt = now + 300초
 * 3. hold.lua  (좌석 키 + user-hold + ZADD hold-expiry 한 번에)
 * 4. tx        booking(PENDING) + booking_seat  → 커밋
 *              실패 시 release.lua로 정리하고 발행 없이 409
 * 5. 커밋 후   INCR seat-seq → PUBLISH HELD
 * </pre>
 *
 * 멱등 처리(`idem:` 키)는 3.2.4에서 인터셉터로 붙인다.
 *
 * <p>지표 {@code booking_seat_hold_total}(런타임 계약 5장) — 라벨 {@code result}:
 * <ul>
 *   <li>{@code success} 예매 PENDING 커밋(이벤트 발행 전에 센다 — 발행이 실패해도 선점은 성립)</li>
 *   <li>{@code conflict} 409 {@code SEAT_UNAVAILABLE} — 데모 「같은 좌석 100명 → 성공 1 · 충돌 99」</li>
 *   <li>{@code active_hold} 409 {@code ACTIVE_HOLD_EXISTS}</li>
 *   <li>{@code error} 그 밖의 실패(Catalog 503·Valkey·RDS 오류 = 5xx)</li>
 * </ul>
 * 4xx 거절(좌석 검증·시작된 회차·없는 회차)은 선점을 시도한 게 아니라 <b>세지 않는다</b> — 신호등이
 * 사용자 입력 오류로 빨개지면 안 된다. 멱등 재생은 인터셉터가 응답해 여기까지 오지 않는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SeatHoldService {

    private final Clock clock;
    private final ScreeningCache screeningCache;
    private final SeatHoldStrategy seatHold;
    private final BookingStateService bookingState;
    private final BookingRepositoryReader bookingReader;
    private final SeatEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    public SeatHoldResponse hold(String userId, SeatHoldRequest request) {
        Instant now = clock.instant();
        Held held;
        try {
            held = validateAndHold(userId, request, now);
        } catch (RuntimeException e) {
            resultOf(e).ifPresent(this::count);
            throw e;
        }
        count("success");

        // 커밋 후에만 발행한다 (redis-usage.md 1장)
        eventPublisher.publish(SeatEventType.HELD, request.screeningId(), held.seatIds(), now);
        return SeatHoldResponse.from(held.booking(), held.seatIds());
    }

    /** 1~4단계. 돌아오면 예매가 PENDING으로 커밋된 것이다 */
    private Held validateAndHold(String userId, SeatHoldRequest request, Instant now) {
        List<Long> seatIds = validateSeats(request);

        Screening screening = screeningCache.screening(request.screeningId());
        if (screening.hasStarted(now)) {
            throw BookingErrorCode.SCREENING_CLOSED.exception(
                ErrorDetail.of("screeningId", request.screeningId(), BookingErrorCode.SCREENING_CLOSED.name()));
        }
        SeatLayout layout = screeningCache.layout(screening.auditoriumId(), screening.layoutVersion());
        if (!layout.containsAll(seatIds)) {
            throw BookingErrorCode.INVALID_SEATS.exception(seatIds.stream()
                .filter(seatId -> !layout.activeSeatLabels().containsKey(seatId))
                .map(seatId -> ErrorDetail.of("seatIds", seatId, "NOT_IN_LAYOUT"))
                .toList());
        }

        return new Held(holdAndSave(userId, request.screeningId(), seatIds, layout, now, true), seatIds);
    }

    /** 커밋된 선점 — 발행·응답에 쓴다 */
    private record Held(Booking booking, List<Long> seatIds) {
    }

    /** 지표 라벨. 4xx 거절은 비어 있다(세지 않는다) — 클래스 주석 참고 */
    private static Optional<String> resultOf(RuntimeException e) {
        if (!(e instanceof BusinessException business)) {
            return Optional.of("error");   // DataAccessException 등 — 500으로 나간다
        }
        CommonResponseCode code = business.code().commonCode();
        return switch (code) {
            case SEAT_UNAVAILABLE -> Optional.of("conflict");
            case ACTIVE_HOLD_EXISTS -> Optional.of("active_hold");
            default -> code.status() >= 500 ? Optional.of("error") : Optional.empty();
        };
    }

    private void count(String result) {
        meterRegistry.counter("booking_seat_hold_total", "result", result).increment();
    }

    /**
     * 3~4단계. {@code user-hold}가 있으면 RDS를 확인해 409 또는 고아 키 정리로 갈라진다.
     *
     * @param retryAfterOrphan 고아 키를 정리한 뒤 한 번만 다시 시도한다(무한 재시도 방지)
     */
    private Booking holdAndSave(String userId, Long screeningId, List<Long> seatIds, SeatLayout layout,
                                Instant now, boolean retryAfterOrphan) {
        String holdId = UUID.randomUUID().toString();
        Instant expiresAt = now.plus(BookingPolicy.HOLD_TTL);

        HoldOutcome outcome = seatHold.hold(screeningId, userId, holdId, seatIds, expiresAt);
        switch (outcome.status()) {
            case SEAT_TAKEN -> throw seatUnavailable(screeningId, outcome.takenSeatIds());
            case ACTIVE_HOLD -> {
                Optional<Booking> active = bookingReader.findPendingByHoldId(outcome.activeHoldId(), now);
                if (active.isPresent()) {
                    Booking pending = active.get();
                    throw BookingErrorCode.ACTIVE_HOLD_EXISTS.exception(
                        ErrorDetail.of("bookingId", pending.getBookingId()),
                        ErrorDetail.of("expiresAt", Times.toApi(pending.getExpiresAt())));
                }
                // 아직 커밋 중일 수 있다: hold.lua는 RDS 커밋 전에 user-hold·장부를 쓴다.
                // 같은 사용자의 두 번째 요청(더블클릭·재시도)을 고아로 보고 지우면 첫 요청의
                // 선점 1건 제한과 만료 장부가 깨진다 → 만들어진 지 얼마 안 됐으면 409로 돌려준다
                if (isCommitInFlight(screeningId, outcome.activeHoldId(), now)) {
                    throw BookingErrorCode.ACTIVE_HOLD_EXISTS.exception(
                        ErrorDetail.of("screeningId", screeningId, "HOLD_IN_PROGRESS"));
                }

                // 고아 키 (B3): Lua는 성공했는데 RDS 저장이 실패했거나 파드가 죽은 경우.
                // 좌석 키는 알 수 없어 그대로 두고(TTL로 사라진다) user-hold·장부만 정리한 뒤 다시 시도한다.
                log.info("고아 선점 키 정리 screeningId={} holdId={}", screeningId, outcome.activeHoldId());
                seatHold.release(screeningId, userId, outcome.activeHoldId(), List.of());
                if (!retryAfterOrphan) {
                    throw BookingErrorCode.ACTIVE_HOLD_EXISTS.exception();
                }
                return holdAndSave(userId, screeningId, seatIds, layout, now, false);
            }
            default -> { /* OK — 아래에서 저장한다 */ }
        }

        try {
            return bookingState.createPending(new PendingBooking(userId, screeningId, holdId, seatIds,
                layout.labelsOf(seatIds), expiresAt), now);
        } catch (RuntimeException e) {
            // 유일 제약 위반 등. Valkey를 되돌리고 이벤트는 발행하지 않는다.
            // 되돌리기가 실패해도 원래 예외를 그대로 던진다 — 409를 500으로 바꾸면 안 된다
            // (남은 좌석 키는 TTL 300초로 사라진다)
            try {
                seatHold.release(screeningId, userId, holdId, seatIds);
            } catch (DataAccessException cleanupFailure) {
                log.warn("선점 되돌리기 실패 screeningId={} seats={} cause={}: {}", screeningId, seatIds,
                    cleanupFailure.getClass().getSimpleName(), cleanupFailure.getMessage());
            }
            throw e;
        }
    }

    /**
     * 장부 점수(만료 시각)에서 선점 생성 시각을 구해, 아직 RDS 커밋이 끝나지 않았을 수 있는
     * 창인지 본다. 장부에 멤버가 없으면(이미 정리됐거나 장부만 날아간 경우) 고아로 본다.
     */
    private boolean isCommitInFlight(Long screeningId, String activeHoldId, Instant now) {
        return seatHold.expiryOf(screeningId, activeHoldId)
            .map(expiresAt -> expiresAt.minus(BookingPolicy.HOLD_TTL))
            .filter(createdAt -> createdAt.isAfter(now.minus(BookingPolicy.HOLD_COMMIT_GRACE)))
            .isPresent();
    }

    /** 좌석마다 왜 막혔는지 채운다 — RDS가 CONFIRMED면 RESERVED, 그 밖은 HELD (명세 3장 우선순위) */
    private BusinessException seatUnavailable(Long screeningId, List<Long> takenSeatIds) {
        List<Long> reserved = bookingReader.findSeatIdsByStatus(screeningId, takenSeatIds, BookingStatus.CONFIRMED);
        return BookingErrorCode.SEAT_UNAVAILABLE.exception(takenSeatIds.stream()
            .map(seatId -> ErrorDetail.of("seatIds", seatId,
                (reserved.contains(seatId) ? SeatStatus.RESERVED : SeatStatus.HELD).name()))
            .toList());
    }

    /** 좌석 수·중복은 애너테이션으로 못 보는 부분만 본다 (1~4석은 DTO가 검증) */
    private static List<Long> validateSeats(SeatHoldRequest request) {
        List<Long> seatIds = request.seatIds();
        LinkedHashSet<Long> unique = new LinkedHashSet<>(seatIds);
        if (unique.size() != seatIds.size()) {
            throw BookingErrorCode.INVALID_SEATS.exception(
                ErrorDetail.of("seatIds", seatIds.size(), "DUPLICATED"));
        }
        return List.copyOf(unique);
    }
}

package com.seatsac.booking.component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 좌석 선점 방식 — ADR-45. 지금은 Valkey 구현({@link SeatHoldRedisRepository})만 있고,
 * db-lock 비교 구현은 설계 초안만 둔다(구현·측정은 4.2 여유 시).
 *
 * <p>구현체는 <b>비즈니스 로직을 갖지 않는다</b>(지침 4장 component 규칙) — 키를 쓰고 결과를
 * 돌려주는 것까지만 하고, 고아 키 판정·409 변환·이벤트 발행은 흐름 서비스가 한다.
 */
public interface SeatHoldStrategy {

    /**
     * 요청 좌석 전부를 한 번에 잡는다(전부 성공 또는 전부 실패).
     *
     * @param expiresAt {@code now + 300초}. 만료 장부 점수로도 쓴다
     */
    HoldOutcome hold(Long screeningId, String userId, String holdId, List<Long> seatIds, Instant expiresAt);

    /**
     * 저장 값이 {@code holdId}일 때만 지운다. {@code seatIds}가 비어 있어도 된다
     * (고아 키 정리는 좌석을 모르고 {@code user-hold}·장부만 지운다).
     *
     * @return 지운 좌석 키 개수
     */
    long release(Long screeningId, String userId, String holdId, List<Long> seatIds);

    /**
     * 만료 장부에 적힌 만료 시각. 선점이 언제 만들어졌는지(= 만료 시각 − TTL) 알아야
     * 「RDS에 없는 선점」이 고아인지 아직 커밋 중인지 가를 수 있다.
     */
    Optional<Instant> expiryOf(Long screeningId, String holdId);

    /** 선점 결과. 상태에 따라 채워지는 필드가 다르다 */
    record HoldOutcome(Status status, String activeHoldId, List<Long> takenSeatIds) {

        public enum Status {
            /** 선점 성공 */
            OK,
            /** 이 회차에 진행 중 선점이 있다 → RDS 확인 후 409 또는 고아 키 정리 */
            ACTIVE_HOLD,
            /** 이미 잡힌 좌석이 있다 → 409 SEAT_UNAVAILABLE */
            SEAT_TAKEN
        }

        public static HoldOutcome ok() {
            return new HoldOutcome(Status.OK, null, List.of());
        }

        public static HoldOutcome activeHold(String activeHoldId) {
            return new HoldOutcome(Status.ACTIVE_HOLD, activeHoldId, List.of());
        }

        public static HoldOutcome seatTaken(List<Long> takenSeatIds) {
            return new HoldOutcome(Status.SEAT_TAKEN, null, List.copyOf(takenSeatIds));
        }

        public boolean isOk() {
            return status == Status.OK;
        }
    }

    /**
     * 만료 장부에서 점수가 지난 멤버를 묶어 가져온다 (안전망 1).
     *
     * <p>{@code offset}이 필요한 이유: 결제 중이라 보류한 멤버는 장부에 그대로 남는다. 늘 앞에서부터
     * {@code limit}개만 읽으면 보류 멤버가 그 자리를 채웠을 때 <b>뒤에 있는 만료 건을 영영 못 본다.</b>
     * 스캔은 보류·고아 멤버를 지나 다음 쪽으로 넘어간다.
     */
    List<ExpiringHold> dueHolds(Instant now, int offset, int limit);

    /** 만료 장부에서 멤버를 지운다. <b>처리를 끝낸 뒤에</b> 부른다 (C13) */
    long forget(Long screeningId, String holdId);

    /** 만료 장부의 멤버 — {@code {회차ID}:{holdId}} */
    record ExpiringHold(Long screeningId, String holdId) {
    }
}

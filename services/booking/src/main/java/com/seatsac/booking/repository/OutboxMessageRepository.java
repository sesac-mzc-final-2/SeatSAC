package com.seatsac.booking.repository;

import com.seatsac.booking.entity.OutboxMessage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 아웃박스 — 전송은 릴레이가 <b>조건부 UPDATE로 한 건씩 점유</b>해서 한다(지침 4장).
 *
 * <p>수정 쿼리마다 {@code @Transactional}을 둔 것은 의도다 — <b>점유·결과 기록이 각각 바로
 * 커밋되어야</b> 전송(네트워크)이 트랜잭션 밖에서 일어난다. 하나로 묶으면 SQS 응답을 기다리는
 * 동안 행 잠금과 Hikari 연결 5개 중 하나를 붙잡는다.
 *
 * <p>행을 잠근 채로 SQS를 부르지 않는다({@code FOR UPDATE} 없음). 점유 UPDATE를 커밋한 뒤
 * 네트워크 호출을 하므로 Hikari 연결 5개를 호출 시간만큼 붙잡지 않고, 파드가 호출 중에 죽어도
 * 점유가 만료되면({@code next_attempt_at}) 다음 주기가 이어받는다.
 */
public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, Long> {

    Optional<OutboxMessage> findByMessageKey(String messageKey);

    /**
     * 보낼 차례가 된 메시지. {@code PENDING}(다음 시도 시각 지남)과
     * <b>점유가 만료된 {@code SENDING}</b>(파드가 호출 중에 죽은 경우)을 함께 집는다.
     *
     * <p>오래 기다린 것부터 본다. 한 주기 처리량은 부르는 쪽이
     * {@link com.seatsac.booking.common.BookingPolicy#OUTBOX_RELAY_BATCH}로 묶는다.
     */
    @Query("""
        select m from OutboxMessage m
         where m.status in (com.seatsac.booking.enums.OutboxStatus.PENDING,
                            com.seatsac.booking.enums.OutboxStatus.SENDING)
           and m.nextAttemptAt <= :now
         order by m.nextAttemptAt asc, m.outboxId asc
        """)
    List<OutboxMessage> findDue(@Param("now") Instant now, Pageable pageable);

    /**
     * 점유. 상태와 시각을 조건으로 걸어 <b>여러 파드가 같은 행을 집어도 한 곳만 1을 받는다</b>
     * (미리보기 파드에서도 릴레이가 돈다 — 런타임 계약 2장).
     *
     * <p>{@code attempt_count}를 점유 때 올린다. 호출 중에 죽어도 횟수가 늘어나므로
     * 재시도 한도가 무한정 늘어나지 않는다(펜싱).
     *
     * @return 1이면 내가 보낼 차례, 0이면 다른 파드가 먼저 집었거나 이미 끝난 메시지다
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update OutboxMessage m
           set m.status = com.seatsac.booking.enums.OutboxStatus.SENDING,
               m.attemptCount = m.attemptCount + 1,
               m.nextAttemptAt = :claimUntil,
               m.updatedAt = :now
         where m.outboxId = :outboxId
           and m.status in (com.seatsac.booking.enums.OutboxStatus.PENDING,
                            com.seatsac.booking.enums.OutboxStatus.SENDING)
           and m.nextAttemptAt <= :now
        """)
    int claim(@Param("outboxId") Long outboxId, @Param("claimUntil") Instant claimUntil,
              @Param("now") Instant now);

    /** 전송 성공. 점유한 쪽만 기록한다 */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update OutboxMessage m
           set m.status = com.seatsac.booking.enums.OutboxStatus.SENT,
               m.sentAt = :now,
               m.lastError = null,
               m.updatedAt = :now
         where m.outboxId = :outboxId
           and m.status = com.seatsac.booking.enums.OutboxStatus.SENDING
        """)
    int markSent(@Param("outboxId") Long outboxId, @Param("now") Instant now);

    /** 전송 실패 — 다음 시도 시각을 두고 되돌린다 */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update OutboxMessage m
           set m.status = com.seatsac.booking.enums.OutboxStatus.PENDING,
               m.nextAttemptAt = :nextAttemptAt,
               m.lastError = :lastError,
               m.updatedAt = :now
         where m.outboxId = :outboxId
           and m.status = com.seatsac.booking.enums.OutboxStatus.SENDING
        """)
    int markRetry(@Param("outboxId") Long outboxId, @Param("nextAttemptAt") Instant nextAttemptAt,
                  @Param("lastError") String lastError, @Param("now") Instant now);

    /** 재시도 한도 초과 — 더 보내지 않는다(경보 대상) */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update OutboxMessage m
           set m.status = com.seatsac.booking.enums.OutboxStatus.FAILED,
               m.lastError = :lastError,
               m.updatedAt = :now
         where m.outboxId = :outboxId
           and m.status = com.seatsac.booking.enums.OutboxStatus.SENDING
        """)
    int markFailed(@Param("outboxId") Long outboxId, @Param("lastError") String lastError,
                   @Param("now") Instant now);

    /** 아직 보내지 못한 건수 — 지표 {@code booking_outbox_pending} */
    @Query("""
        select count(m) from OutboxMessage m
         where m.status in (com.seatsac.booking.enums.OutboxStatus.PENDING,
                            com.seatsac.booking.enums.OutboxStatus.SENDING)
        """)
    long countUnsent();
}

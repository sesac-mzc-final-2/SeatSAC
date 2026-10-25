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
     * 재시도 한도가 무한정 늘어나지 않는다(펜싱). 그 값은 <b>결과를 기록할 때 조건으로 쓰는
     * 토큰</b>이기도 하다 — 내 점유가 만료되고 다른 파드가 이어받은 뒤에 내가 늦게 결과를
     * 쓰면 그쪽 상태를 덮어쓰게 되므로, {@code markSent} 등이 이 값을 함께 본다.
     *
     * <p>{@code attemptCount = :expectedAttempt}(조회 시점 값)를 조건에 넣어 <b>compare-and-set</b>으로
     * 만든다. 이것이 없으면 조회와 점유 사이에 다른 파드가 집었다가 점유까지 만료된 경우
     * 내 점유도 성공하면서 DB 값은 조회값+2가 되는데 내 토큰은 조회값+1이라, 결과 기록이
     * 0행이 되어 <b>전송은 끝났는데 행이 {@code SENDING}에 남는다</b>. 조건을 걸면 그런 행은
     * 점유가 0행으로 거절되고, 점유 중인 파드가 처리하거나 다음 주기가 다시 집는다.
     *
     * <p>{@code attemptCount < :maxAttempts} 조건이 필요한 이유: 점유만 하고 전송 전에 죽은
     * 파드는 실패를 기록하지 못한다. 그 행은 점유가 만료되면 다시 집히는데, 이 조건이 없으면
     * <b>한도와 무관하게 영원히 재점유</b>된다. 한도에 닿은 행은
     * {@link #failExhausted}가 닫는다.
     *
     * @return 1이면 내가 보낼 차례, 0이면 다른 파드가 먼저 집었거나 한도에 닿은 메시지다
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
           and m.attemptCount < :maxAttempts
           and m.attemptCount = :expectedAttempt
        """)
    int claim(@Param("outboxId") Long outboxId, @Param("claimUntil") Instant claimUntil,
              @Param("now") Instant now, @Param("maxAttempts") int maxAttempts,
              @Param("expectedAttempt") int expectedAttempt);

    /**
     * 점유만 하고 전송 전에 죽어 <b>한도에 닿은 채 {@code SENDING}으로 남은</b> 행을 닫는다.
     * 조건(점유 만료 + 한도 도달)을 UPDATE에 걸어, 여러 파드가 동시에 봐도 한 곳만 1을 받는다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update OutboxMessage m
           set m.status = com.seatsac.booking.enums.OutboxStatus.FAILED,
               m.lastError = :lastError,
               m.updatedAt = :now
         where m.outboxId = :outboxId
           and m.status = com.seatsac.booking.enums.OutboxStatus.SENDING
           and m.nextAttemptAt <= :now
           and m.attemptCount >= :maxAttempts
        """)
    int failExhausted(@Param("outboxId") Long outboxId, @Param("lastError") String lastError,
                      @Param("now") Instant now, @Param("maxAttempts") int maxAttempts);

    /** 전송 성공. <b>내 점유</b>({@code attemptCount})일 때만 기록한다 */
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
           and m.attemptCount = :attempt
        """)
    int markSent(@Param("outboxId") Long outboxId, @Param("attempt") int attempt,
                 @Param("now") Instant now);

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
           and m.attemptCount = :attempt
        """)
    int markRetry(@Param("outboxId") Long outboxId, @Param("attempt") int attempt,
                  @Param("nextAttemptAt") Instant nextAttemptAt,
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
           and m.attemptCount = :attempt
        """)
    int markFailed(@Param("outboxId") Long outboxId, @Param("attempt") int attempt,
                   @Param("lastError") String lastError, @Param("now") Instant now);

    /** 아직 보내지 못한 건수 — 지표 {@code booking_outbox_pending} */
    @Query("""
        select count(m) from OutboxMessage m
         where m.status in (com.seatsac.booking.enums.OutboxStatus.PENDING,
                            com.seatsac.booking.enums.OutboxStatus.SENDING)
        """)
    long countUnsent();
}

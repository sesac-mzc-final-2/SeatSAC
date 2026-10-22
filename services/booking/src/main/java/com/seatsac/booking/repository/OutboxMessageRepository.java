package com.seatsac.booking.repository;

import com.seatsac.booking.entity.OutboxMessage;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 아웃박스 — 잡기(FOR UPDATE SKIP LOCKED)·결과 기록·정리 쿼리는 릴레이 단계(3.2.6)에서 추가한다.
 */
public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, Long> {

    Optional<OutboxMessage> findByMessageKey(String messageKey);
}

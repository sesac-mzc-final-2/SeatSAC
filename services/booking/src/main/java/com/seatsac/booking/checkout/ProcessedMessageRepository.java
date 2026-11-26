package com.seatsac.booking.checkout;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, String> {

    /** 보존 기간이 지난 inbox 행을 오래된 것부터 {@code limit}건 지운다 — 잠금을 짧게 두려고 나눠 지운다 */
    @Modifying
    @Query(value = "DELETE FROM processed_message WHERE received_at < :before ORDER BY received_at LIMIT :limit",
        nativeQuery = true)
    int deleteReceivedBefore(@Param("before") Instant before, @Param("limit") int limit);
}

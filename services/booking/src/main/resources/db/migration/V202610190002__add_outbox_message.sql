-- 예매 완료 알림 아웃박스 (ERD 6차 3.3, 알림 결정 문서 10장)
-- 확정(BKG-06)의 조건부 UPDATE가 1행일 때만 같은 트랜잭션에서 INSERT한다.
-- uk_outbox_event_aggregate: 한 예매에 알림이 두 건 생기지 않게 하는 2차 방어선.

CREATE TABLE outbox_message (
  outbox_id       BIGINT       NOT NULL AUTO_INCREMENT,
  message_key     CHAR(36)     NOT NULL COMMENT '메시지 eventId(UUID). 재전송해도 같은 값',
  event_type      VARCHAR(40)  NOT NULL COMMENT 'BOOKING_CONFIRMED',
  aggregate_id    BIGINT       NOT NULL COMMENT '예매 ID (FK 없음)',
  payload         JSON         NOT NULL COMMENT '메시지 본문. 트랜잭션 전에 만든 값',
  trace_id        CHAR(32)     NULL COMMENT 'X-Trace-Id → SQS 속성 traceId',
  status          VARCHAR(20)  NOT NULL COMMENT 'PENDING·SENDING·SENT·FAILED',
  attempt_count   INT          NOT NULL DEFAULT 0 COMMENT '잡은 횟수. 펜싱·재시도 한도',
  next_attempt_at DATETIME(3)  NOT NULL COMMENT 'PENDING: 다음 시도, SENDING: 점유 만료 (UTC)',
  last_error      VARCHAR(500) NULL,
  sent_at         DATETIME(3)  NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (outbox_id),
  UNIQUE KEY uk_outbox_message_key (message_key),
  UNIQUE KEY uk_outbox_event_aggregate (event_type, aggregate_id),
  KEY idx_outbox_status_next_attempt (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

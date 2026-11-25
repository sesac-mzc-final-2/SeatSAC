-- 6.2.3 체크아웃 saga 참여자 (ADR-116·117, 설계 02) — 확장 단계만. 기존 동기 경로는 이 표들을 쓰지 않는다.

-- 명령 inbox: 같은 eventId는 한 번만 처리한다(SQS 표준 큐 = 중복 배달 전제)
CREATE TABLE processed_message (
  message_id   CHAR(36)    NOT NULL COMMENT '명령 envelope eventId',
  message_type VARCHAR(60) NOT NULL COMMENT 'payment.charge-requested 등',
  received_at  DATETIME(3) NOT NULL,
  PRIMARY KEY (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 결과 outbox → SQS booking-replies. booking outbox_message와 같은 구조(+ aggregate_key)
CREATE TABLE outbox_message (
  outbox_id       BIGINT       NOT NULL AUTO_INCREMENT,
  message_key     CHAR(36)     NOT NULL COMMENT '결과 envelope eventId(UUID). 재전송해도 같은 값',
  event_type      VARCHAR(60)  NOT NULL COMMENT 'payment.approved 등 — 메시지 type 그대로',
  aggregate_id    BIGINT       NOT NULL COMMENT '예매 ID (FK 없음)',
  aggregate_key   VARCHAR(80)  NOT NULL DEFAULT '' COMMENT '결과를 낳은 원인 — 명령 eventId. 같은 명령의 결과는 한 행',
  payload         JSON         NOT NULL COMMENT 'envelope 전체',
  trace_id        CHAR(32)     NULL,
  correlation_id  CHAR(36)     NULL COMMENT 'checkoutId → SQS 속성 correlationId',
  status          VARCHAR(20)  NOT NULL COMMENT 'PENDING·SENDING·SENT·FAILED',
  attempt_count   INT          NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(3)  NOT NULL,
  last_error      VARCHAR(500) NULL,
  sent_at         DATETIME(3)  NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (outbox_id),
  UNIQUE KEY uk_outbox_message_key (message_key),
  UNIQUE KEY uk_outbox_event_aggregate (event_type, aggregate_id, aggregate_key),
  KEY idx_outbox_status_next_attempt (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- saga 명령으로 만든 결제 표시(D7) · booking이 놓은 결제(released_at, D16). 동기 경로 결제는 행이 없다
CREATE TABLE saga_payment (
  payment_id  CHAR(36)    NOT NULL,
  checkout_id CHAR(36)    NOT NULL,
  booking_id  BIGINT      NOT NULL,
  released_at DATETIME(3) NULL COMMENT 'payment.release-requested를 받은 시각. 이후 PAID면 FAILED(BOOKING_EXPIRED)',
  created_at  DATETIME(3) NOT NULL,
  PRIMARY KEY (payment_id),
  KEY idx_saga_payment_checkout (checkout_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ADR-24 11/24 보완(06): 살아 있는 결제(PROCESSING·APPROVED)만 예매당 1건. 실패·취소 행은 여러 개 허용(saga 재결제).
-- 인덱스 교체라 배포 1회 — 옛 버전도 「살아 있는 결제 2건」은 똑같이 막힌다(옛 코드는 같은 예매에 두 번째 결제를 만들지 않는다)
ALTER TABLE payment
  ADD COLUMN live_booking_id BIGINT
    AS (CASE WHEN status IN ('PROCESSING', 'APPROVED') THEN booking_id END) STORED
    COMMENT '살아 있는 결제일 때만 booking_id',
  ADD UNIQUE KEY uk_payment_live_booking (live_booking_id),
  DROP INDEX uk_payment_booking_id,
  ADD KEY idx_payment_booking (booking_id);

-- 6.2.1 체크아웃 saga (ADR-116·117, 설계 01·04) — 확장 단계만. 기존 동기 경로는 이 표들을 쓰지 않는다.

CREATE TABLE checkout_saga (
  checkout_id       CHAR(36)     NOT NULL COMMENT 'UUID = 메시지 correlationId',
  booking_id        BIGINT       NOT NULL,
  user_id           CHAR(36)     NOT NULL,
  method            VARCHAR(20)  NOT NULL COMMENT 'TOSSPAY·CARD',
  state             VARCHAR(30)  NOT NULL,
  step              VARCHAR(40)  NOT NULL COMMENT '마지막으로 끝낸 단계(표시용)',
  attempt           INT          NOT NULL DEFAULT 1 COMMENT '이 예매의 몇 번째 체크아웃인지(상한 3)',
  coupon_issue_id   VARCHAR(64)  NULL COMMENT 'booking은 해석하지 않는다(coupon이 판정)',
  discount_kind     VARCHAR(10)  NULL COMMENT 'RATE·AMOUNT',
  discount_value    INT          NULL,
  original_amount   INT          NOT NULL,
  discount_amount   INT          NOT NULL DEFAULT 0,
  final_amount      INT          NOT NULL,
  payment_id        CHAR(36)     NULL,
  checkout_json     JSON         NULL COMMENT 'READY_TO_PAY 결제창 값(payment.prepared.checkout)',
  failure_code      VARCHAR(40)  NULL,
  failure_reason    VARCHAR(100) NULL,
  step_deadline_at  DATETIME(3)  NULL COMMENT '스윕 기준. 끝난 상태면 NULL',
  verify_attempt    INT          NOT NULL DEFAULT 0,
  last_unknown      VARCHAR(30)  NULL COMMENT '마지막 결과 불명 사유 — 재확인 간격 계산',
  refund_notice     VARCHAR(30)  NULL COMMENT 'WILL_REFUND_IF_PAID·REFUND_IN_PROGRESS',
  trace_id          CHAR(32)     NULL,
  created_at        DATETIME(3)  NOT NULL,
  updated_at        DATETIME(3)  NOT NULL,
  -- 진행 중 체크아웃은 예매당 1건. 끝난 상태(CONFIRMED·FAILED·EXPIRED·COUPON_REJECTED·PAYMENT_RETRYABLE)는 NULL이라 여러 행 허용.
  -- 상태 목록을 바꾸면 이 식도 새 V 파일로 같이 바꾼다
  active_booking_id BIGINT AS (CASE WHEN state IN ('STARTED', 'COUPON_RESERVING', 'PAYMENT_PREPARING', 'READY_TO_PAY',
                                                    'CHARGING', 'VERIFYING', 'COMPENSATING') THEN booking_id END) STORED,
  PRIMARY KEY (checkout_id),
  UNIQUE KEY uk_checkout_active_booking (active_booking_id),
  KEY idx_checkout_booking (booking_id, created_at),
  KEY idx_checkout_sweep (state, step_deadline_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 결과 inbox: 같은 eventId는 한 번만 처리한다
CREATE TABLE processed_message (
  message_id   CHAR(36)    NOT NULL COMMENT '결과 envelope eventId',
  message_type VARCHAR(60) NOT NULL,
  received_at  DATETIME(3) NOT NULL,
  PRIMARY KEY (message_id),
  KEY idx_processed_received (received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 아웃박스: saga 명령도 같은 표에. 기존 행·이전 버전 파드의 행은 aggregate_key=''·destination=NOTIFICATION이라
-- 기존 유일 보장(예매당 알림 1건)이 그대로다 — 블루/그린 중 두 버전 공존 가능
ALTER TABLE outbox_message
  ADD COLUMN aggregate_key  VARCHAR(80) NOT NULL DEFAULT '' AFTER aggregate_id,
  ADD COLUMN destination    VARCHAR(40) NOT NULL DEFAULT 'NOTIFICATION' AFTER aggregate_key,
  ADD COLUMN correlation_id CHAR(36)    NULL AFTER trace_id,
  DROP INDEX uk_outbox_event_aggregate,
  ADD UNIQUE KEY uk_outbox_event_aggregate (event_type, aggregate_id, aggregate_key);

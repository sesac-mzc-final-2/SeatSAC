-- 6.3.2 coupon_db (ADR-118, API 명세서 v1.5 22장, data-model 7-2). 첫 스키마라 확장·전환·정리 구분이 없다.
-- 이 파일은 coupon_migrator 계정으로 Flyway 훅 Job이 실행한다.

-- 쿠폰 이벤트. 관리자(ops-api → POST /internal/coupon-events)만 만든다
CREATE TABLE coupon_event (
  event_id        BIGINT       NOT NULL AUTO_INCREMENT,
  title           VARCHAR(100) NOT NULL,
  discount_rate   INT          NOT NULL COMMENT '할인율(%) 1~99 — 결제 금액은 1원 이상',
  eligible_grade  VARCHAR(20)  NOT NULL COMMENT 'VIP',
  total_quantity  INT          NOT NULL COMMENT '선착순 수량',
  issued_quantity INT          NOT NULL DEFAULT 0 COMMENT '발급 수. 발급 트랜잭션이 issued_quantity < total_quantity일 때만 +1 — 총수량의 최종 방어선',
  starts_at       DATETIME(3)  NOT NULL,
  ends_at         DATETIME(3)  NOT NULL COMMENT '= 받은 쿠폰의 expires_at',
  created_at      DATETIME(3)  NOT NULL,
  PRIMARY KEY (event_id),
  KEY idx_coupon_event_period (starts_at, ends_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 이벤트 생성 직렬화용 보호 행(1행 고정). 생성이 이 행을 FOR UPDATE로 잠근 뒤 기간 겹침을 확인·저장한다 —
-- 표가 비어 있어도 잠글 행이 있어야 동시 생성 두 건이 둘 다 「겹침 없음」을 보지 않는다
CREATE TABLE coupon_event_lock (
  lock_id TINYINT NOT NULL,
  PRIMARY KEY (lock_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO coupon_event_lock (lock_id) VALUES (1);

-- 받은 쿠폰. 1인 1매의 최종 방어선은 UK (event_id, user_id)
CREATE TABLE coupon_issue (
  coupon_issue_id CHAR(36)    NOT NULL COMMENT 'coupon 발급 UUID — 체크아웃 couponIssueId',
  event_id        BIGINT      NOT NULL,
  user_id         CHAR(36)    NOT NULL COMMENT 'Cognito sub',
  claim_key       CHAR(36)    NULL COMMENT '발급한 요청의 Idempotency-Key — 멱등 결과를 저장하지 못했을 때 같은 키 재요청에 201을 다시 주려고',
  state           VARCHAR(20) NOT NULL COMMENT 'ISSUED·RESERVED·USED·EXPIRED',
  expires_at      DATETIME(3) NOT NULL COMMENT '= 이벤트 종료 시각',
  issued_at       DATETIME(3) NOT NULL,
  reserved_at     DATETIME(3) NULL,
  used_at         DATETIME(3) NULL,
  updated_at      DATETIME(3) NOT NULL,
  PRIMARY KEY (coupon_issue_id),
  UNIQUE KEY uk_coupon_issue_event_user (event_id, user_id),
  KEY idx_coupon_issue_user_state (user_id, state),
  KEY idx_coupon_issue_state_expires (state, expires_at),
  CONSTRAINT fk_coupon_issue_event FOREIGN KEY (event_id) REFERENCES coupon_event (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 체크아웃 1건 = 1행. 반환이 예약보다 먼저 오면 RELEASED 행을 먼저 만들어 늦은 예약을 거절한다(22-4).
-- 거절한 예약도 결과(reason)를 남겨 같은 checkoutId의 재시도에 같은 결과를 다시 보낸다
CREATE TABLE coupon_reservation (
  checkout_id     CHAR(36)    NOT NULL,
  coupon_issue_id VARCHAR(64) NOT NULL COMMENT '명령 값 그대로(coupon에 없는 값일 수 있다)',
  booking_id      BIGINT      NULL,
  state           VARCHAR(20) NOT NULL COMMENT 'RESERVED·RELEASED·USED·REJECTED',
  reject_reason   VARCHAR(40) NULL COMMENT 'REJECTED일 때 NOT_OWNER·NOT_ISSUED·EXPIRED·ALREADY_RELEASED',
  discount_rate   INT         NULL COMMENT 'RESERVED·USED일 때 예약한 할인율',
  event_id        BIGINT      NULL,
  created_at      DATETIME(3) NOT NULL,
  updated_at      DATETIME(3) NOT NULL,
  PRIMARY KEY (checkout_id),
  KEY idx_coupon_reservation_issue (coupon_issue_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 명령 inbox: 같은 eventId는 한 번만 처리한다(SQS 표준 큐 = 중복 배달 전제)
CREATE TABLE processed_message (
  message_id   CHAR(36)    NOT NULL COMMENT '명령 envelope eventId',
  message_type VARCHAR(60) NOT NULL COMMENT 'coupon.reservation-requested 등',
  received_at  DATETIME(3) NOT NULL,
  PRIMARY KEY (message_id),
  KEY idx_processed_received (received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 결과 outbox → SQS booking-replies. payment outbox_message와 같은 구조
CREATE TABLE outbox_message (
  outbox_id       BIGINT       NOT NULL AUTO_INCREMENT,
  message_key     CHAR(36)     NOT NULL COMMENT '결과 envelope eventId(UUID). 재전송해도 같은 값',
  event_type      VARCHAR(60)  NOT NULL COMMENT 'coupon.reserved 등 — 메시지 type 그대로',
  aggregate_id    BIGINT       NOT NULL COMMENT '예매 ID (FK 없음)',
  aggregate_key   VARCHAR(80)  NOT NULL DEFAULT '' COMMENT '결과를 낳은 명령 eventId. 같은 명령의 결과는 한 행',
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

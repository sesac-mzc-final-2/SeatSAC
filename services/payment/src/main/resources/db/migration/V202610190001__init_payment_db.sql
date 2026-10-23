-- payment_db 초기 스키마 (ERD 6차 3.4장 · Payment 명세 6장)
-- payment_migrator 계정으로 ArgoCD PreSync Job이 실행한다. 시드(lotte-seed)는 여기 넣지 않는다.
-- 시각은 모두 UTC DATETIME(3). 응답에서 +09:00으로 바꾼다.

CREATE TABLE IF NOT EXISTS payment (
    payment_id        CHAR(36)     NOT NULL COMMENT 'booking이 발급한 UUID',
    booking_id        BIGINT       NOT NULL COMMENT 'booking_db.booking.booking_id (FK 없음)',
    amount            INT          NOT NULL COMMENT '원 단위. 15,000의 배수',
    method            VARCHAR(20)  NOT NULL COMMENT 'CARD',
    card_last4        CHAR(4)      NULL     COMMENT '끝 4자리만. 전체 번호는 저장하지 않는다',
    status            VARCHAR(20)  NOT NULL COMMENT 'PROCESSING·APPROVED·FAILED·CANCELED',
    failure_code      VARCHAR(40)  NULL     COMMENT 'CARD_DECLINED·INSUFFICIENT_BALANCE·PROCESSING_TIMEOUT',
    cancel_reason     VARCHAR(40)  NULL     COMMENT 'HOLD_EXPIRED·BOOKING_CANCELED·STALE_PAYMENT',
    pg_provider       VARCHAR(20)  NOT NULL DEFAULT 'MOCK',
    pg_order_id       VARCHAR(64)  NULL     COMMENT '외부 PG 이식 대비',
    pg_transaction_id VARCHAR(64)  NULL     COMMENT '외부 PG 이식 대비',
    approved_at       DATETIME(3)  NULL,
    canceled_at       DATETIME(3)  NULL,
    created_at        DATETIME(3)  NOT NULL,
    updated_at        DATETIME(3)  NOT NULL,
    PRIMARY KEY (payment_id),
    -- 예매당 결제 1회 (ADR-24). 같은 예매에 다른 결제 ID가 오면 이 제약이 막는다
    UNIQUE KEY uk_payment_booking_id (booking_id),
    UNIQUE KEY uk_payment_pg_transaction_id (pg_transaction_id),
    -- 기동 시 PROCESSING 정리가 범위만 읽는다
    KEY idx_payment_status_created_at (status, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '모의 결제 (외부 PG 없음, pg_provider=MOCK)';

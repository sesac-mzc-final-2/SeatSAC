-- booking_db 초기 스키마 (ERD 6차 3.3·4장 = booking 명세 7장). 확장 → 전환 → 정리 규칙 (ADR-33)
-- 이 파일은 booking_migrator 계정으로 PreSync Job이 실행한다. 시드(lotte-seed)는 여기 넣지 않는다.
-- 시각 컬럼은 모두 UTC (RDS time_zone=UTC, JDBC connectionTimeZone=UTC, 앱 Clock.systemUTC()).

CREATE TABLE booking (
  booking_id         BIGINT       NOT NULL AUTO_INCREMENT,
  user_id            CHAR(36)     NOT NULL COMMENT 'Cognito sub',
  screening_id       BIGINT       NOT NULL COMMENT 'catalog.screening (FK 없음)',
  status             VARCHAR(20)  NOT NULL COMMENT 'PENDING·CONFIRMED·PAYMENT_FAILED·EXPIRED·CANCELED',
  hold_id            CHAR(36)     NOT NULL COMMENT '선점 ID(UUID). Valkey 선점 키의 저장 값',
  current_payment_id CHAR(36)     NULL COMMENT '결제 ID(UUID). 예매당 1회',
  seat_count         INT          NOT NULL COMMENT '1~4',
  amount             INT          NOT NULL COMMENT 'seat_count × 15000',
  seat_labels        JSON         NOT NULL COMMENT '["J7","J8"]. 생성 후 변경 없음',
  expires_at         DATETIME(6)  NOT NULL COMMENT '선점 만료 (UTC)',
  confirmed_at       DATETIME(6)  NULL,
  canceled_at        DATETIME(6)  NULL,
  created_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (booking_id),
  UNIQUE KEY uk_booking_hold_id (hold_id),
  UNIQUE KEY uk_booking_current_payment_id (current_payment_id),
  KEY idx_booking_status_expires_at (status, expires_at),
  KEY idx_booking_user_created_at (user_id, created_at),
  KEY idx_booking_screening_id (screening_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 점유 중(PENDING·CONFIRMED)인 좌석만 남는다. uk_booking_seat_screening_seat가 이중 예매 최종 방어선
CREATE TABLE booking_seat (
  booking_id   BIGINT NOT NULL,
  screening_id BIGINT NOT NULL,
  seat_id      BIGINT NOT NULL COMMENT 'catalog.seat (FK 없음)',
  PRIMARY KEY (booking_id, seat_id),
  UNIQUE KEY uk_booking_seat_screening_seat (screening_id, seat_id),
  CONSTRAINT fk_booking_seat_booking FOREIGN KEY (booking_id) REFERENCES booking (booking_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

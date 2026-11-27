-- 6.3.4b 예매 내역 복제본 (API 명세서 v1.5 23-1, data-model 7-2). APP_BOOKING_HISTORY_MODE=local일 때 읽는다
-- booking_history: booking.confirmed·booking.canceled(SNS booking-events → SQS user-booking-events)로 채운다.
--   CANCELED가 이긴다 — canceled가 먼저 오면 confirmed_at이 빈 CANCELED 행을 먼저 만들고, 늦게 온 confirmed는 상세만 채운다.
--   confirmed_at이 빈 행(확정 내용을 아직 받지 못한 행)은 목록에 내지 않는다
-- 상세 열(seat_labels·seat_count·amount·created_at)은 응답 모양(MyBooking)을 그대로 만들려고 둔다. amount는 쿠폰 뒤 최종 금액
-- 확장만(새 테이블). 이전 버전 파드는 이 테이블을 모른다
CREATE TABLE booking_history (
  booking_id BIGINT NOT NULL COMMENT 'booking.booking ID 참조(FK 없음)',
  user_id CHAR(36) NOT NULL,
  status VARCHAR(20) NOT NULL COMMENT 'CONFIRMED·CANCELED',
  screening_id BIGINT NOT NULL,
  seat_labels JSON NULL,
  seat_count TINYINT NULL,
  amount BIGINT NULL COMMENT '결제 금액(쿠폰 할인 뒤). 이전 메시지에 없으면 NULL',
  discount_amount BIGINT NULL,
  created_at DATETIME(3) NULL COMMENT '예매 생성 시각 — 첫 조회 채우기만 안다. 이벤트로 만든 행은 NULL(응답은 confirmed_at)',
  confirmed_at DATETIME(3) NULL,
  canceled_at DATETIME(3) NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (booking_id),
  KEY idx_booking_history_user_confirmed (user_id, confirmed_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 사용자별 첫 조회 채우기 기록 — 행이 있으면 그 뒤로는 복제본만 읽는다
CREATE TABLE booking_history_sync (
  user_id CHAR(36) NOT NULL,
  synced_at DATETIME(3) NOT NULL,
  PRIMARY KEY (user_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- inbox — 같은 eventId는 한 번만 반영한다(ADR-117, booking·coupon·payment와 같은 구조)
CREATE TABLE processed_message (
  message_id CHAR(36) NOT NULL,
  message_type VARCHAR(60) NOT NULL,
  received_at DATETIME(3) NOT NULL,
  PRIMARY KEY (message_id),
  KEY idx_processed_received (received_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 6.3.4a catalog 변경 이벤트 (ADR-117·120, data-model 7-2) — 확장만. 기존 테이블(시드 대상)은 건드리지 않는다
--
-- catalog에는 쓰기 API가 없다. 회차·영화는 lotte-seed 적재·TMDB 보충 SQL(catalog_migrator)로만 바뀐다.
-- 그래서 앱이 주기적으로 원본과 아래 장부를 비교해(CatalogChangeScanner) 바뀐 행마다
-- 「장부 version + 1」과 outbox 행을 같은 트랜잭션에 쓴다.
--   · version을 screening·movie 열로 두지 않는 이유: 시드 재적재는 행을 지웠다 다시 넣어 열 값이 처음으로 돌아가고,
--     지워진 행은 마지막 version을 남길 곳이 없다(계약: 삭제 뒤에도 마지막 version 보존, 다시 생기면 더 큰 version).
--   · 트리거를 쓰지 않는 이유: RDS(바이너리 로그 켜짐)에서 SUPER 없이 트리거를 만들려면 파라미터 그룹
--     log_bin_trust_function_creators=1이 필요하고, 페이로드(극장명·좌석 수)는 조인이 필요하다.

-- 회차·영화마다 마지막으로 내보낸 상태. 원본 행이 지워져도 이 행은 남는다(deleted=1)
CREATE TABLE change_ledger (
  aggregate_type VARCHAR(20)  NOT NULL COMMENT 'SCREENING·MOVIE',
  aggregate_id   BIGINT       NOT NULL COMMENT 'screening_id 또는 movie_id (FK 없음 — 원본이 지워져도 남는다)',
  version        BIGINT       NOT NULL COMMENT '메시지 payload.version. 바뀔 때마다 +1(삭제·재생성 포함), 줄지 않는다',
  content_hash   CHAR(64)     NULL COMMENT '마지막으로 보낸 공개 필드의 SHA-256. 삭제면 NULL',
  deleted        TINYINT      NOT NULL DEFAULT 0,
  changed_at     DATETIME(3)  NOT NULL COMMENT 'UTC. 변경을 찾은 시각 = payload.changedAt',
  PRIMARY KEY (aggregate_type, aggregate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- booking outbox_message와 같은 구조(destination 열만 없다 — 보내는 곳은 SNS catalog-events 하나)
-- uk_outbox_event_aggregate: aggregate_key = version → 변경 1번 = 새 행 1개, 두 파드가 같은 변경을 두 번 넣지 못한다
CREATE TABLE outbox_message (
  outbox_id       BIGINT       NOT NULL AUTO_INCREMENT,
  message_key     CHAR(36)     NOT NULL COMMENT 'envelope eventId(UUID). 재전송해도 같은 값',
  event_type      VARCHAR(60)  NOT NULL COMMENT 'screening.changed·movie.changed',
  aggregate_id    BIGINT       NOT NULL COMMENT 'screeningId 또는 movieId',
  aggregate_key   VARCHAR(80)  NOT NULL DEFAULT '' COMMENT '{version}',
  payload         JSON         NOT NULL COMMENT 'envelope 전체',
  trace_id        CHAR(32)     NULL,
  correlation_id  CHAR(36)     NULL COMMENT 'catalog 이벤트는 saga가 아니라 항상 NULL',
  status          VARCHAR(20)  NOT NULL COMMENT 'PENDING·SENDING·SENT·FAILED',
  attempt_count   INT          NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(3)  NOT NULL COMMENT 'PENDING: 다음 시도, SENDING: 점유 만료 (UTC)',
  last_error      VARCHAR(500) NULL,
  sent_at         DATETIME(3)  NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (outbox_id),
  UNIQUE KEY uk_outbox_message_key (message_key),
  UNIQUE KEY uk_outbox_event_aggregate (event_type, aggregate_id, aggregate_key),
  KEY idx_outbox_status_next_attempt (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

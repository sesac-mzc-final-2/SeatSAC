-- D18(설계 02 6-2) 포트원 재확인 백오프 — 확장 단계(기본값이 있어 옛 버전 파드는 이 열을 모른 채 그대로 동작한다).
-- 포트원 응답이 없어 결제창 미완료 정리를 미룬 행, 환불이 아직 끝나지 않은 행을 행마다 1 → 2 → 4 → 8 → 15분, 이후 15분마다 다시 본다
ALTER TABLE payment
  ADD COLUMN check_attempt INT NOT NULL DEFAULT 0 COMMENT '포트원 재확인을 미룬 횟수(결제창 미완료 정리·환불 재시도)',
  ADD COLUMN next_check_at DATETIME(3) NULL COMMENT '이 시각 전에는 스윕이 다시 보지 않는다. NULL이면 기존 기준(생성 7분·손댄 지 60초)';

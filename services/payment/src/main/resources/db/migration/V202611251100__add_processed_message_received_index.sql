-- 명령 inbox 7일 정리(설계 04 4장)의 조회·삭제 인덱스 — 확장 단계. 정리 스윕은 received_at < now − 7일 행을 1,000건씩 지운다
ALTER TABLE processed_message
  ADD KEY idx_processed_received (received_at);

-- 회차 좌석 이벤트의 다음 번호 (BKG-05) — seat-seq:{회차}
--
-- 키가 없으면 ARGV[1](앱이 구한 「현재 시각 ms × 1000」)로 먼저 두고 INCR 한다.
-- 왜: Valkey는 복제본·스냅샷이 없어(Single-AZ) 재시작·노드 교체 때 키가 사라진다. 그때 1부터 다시 세면
-- 이미 열려 있던 화면은 새 이벤트를 「지난 번호」로 보고 버린다(seq <= lastSeq). 시각 기반 시작값은
-- 사라지기 전 마지막 번호보다 항상 크므로(1ms에 1000건 미만이면) 화면이 빈틈으로 보고 BKG-01을 다시 받는다.
-- 확인과 설정·증가를 한 스크립트로 묶어 두 파드가 동시에 첫 이벤트를 내도 서로 덮지 않는다.
--
-- KEYS[1]  seat-seq:{회차ID}
-- ARGV[1]  키가 없을 때의 시작값 (정수 문자열)
-- 반환     이번 이벤트의 seq

if redis.call('EXISTS', KEYS[1]) == 0 then
  redis.call('SET', KEYS[1], ARGV[1])
end
return redis.call('INCR', KEYS[1])

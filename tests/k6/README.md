# k6 — 클러스터 안 Job 으로 실행 (증설 노드), gateway Service 를 직접 호출

| 시나리오 | 목표 (명세서 10장) |
| --- | --- |
| `seat-hold-contention.js` | 같은 좌석 100명 동시 → 성공 정확히 1명, p95 300ms |
| `browse.js` | 조회 캐시 적중 p95 100ms |
| `deploy-during-load.js` | 블루/그린 전환 중 오류율 0% (결제 경로 포함) |

토큰: Cognito 테스트 계정으로 미리 발급해 환경변수 `K6_TOKENS` 로 전달 (인증 API 요청 수 제한).

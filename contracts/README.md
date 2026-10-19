# contracts — 서비스 간 약속

| 폴더 | 내용 | CI |
| --- | --- | --- |
| `openapi/` | 서비스별 외부 API (`servers: /api`), `internal/`은 내부 API | springdoc 생성본과 diff — 필드 삭제·타입 변경·필수화는 실패 |
| `events/` | Valkey Pub/Sub · SQS 메시지 JSON Schema | 발행·소비 테스트가 스키마 검증 |
| `schemas/` | 오류 응답 형식 | Java·Python 테스트가 같은 파일을 읽음 |

규칙: **설계 먼저**(yaml → PR 합의 → 구현). 버전 없음, 하위 호환(필드 추가만). 깨지는 변경은 새 엔드포인트로 (ADR-32).

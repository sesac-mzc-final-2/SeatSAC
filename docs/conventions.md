# 네이밍·공통 규약 (conventions)

> 기준 2026-10-22 (회의록2 결정 반영, ADR-49~64). 03a 명명 규칙 개정을 확정한 최종본. 03a 이후 결정(Cognito ADR-37, API 명세서 v1.1, 2.2.4 v0.3, ERD 6차)을 반영해 03a와 다른 곳은 ⚠️로 표시했다.
> 범위: 서비스 6개(gateway·catalog·booking·user·payment + ⚠️ ai) + Lambda 2개(notification·cognito-presignup) (ADR-51·82).
> ⚠️ 11/5 AI 재도입(ADR-82~87): `ai`는 Python(FastAPI) 사용자 챗봇. 기존 5개 서비스·RDS·기존 Valkey는 건드리지 않는다. 아래 ⚠️AI 표시 행이 새로 생긴 규칙이다.
> 이 문서가 노션 회의록과 다르면 이 문서를 따르고, 바꿀 때는 PR로 고친다.

---

## 1. 서비스·포트·경로

| 서비스 | 언어 | 컨테이너·Service 포트 | 로컬 호스트 포트 | 클러스터 주소 |
| --- | --- | --- | --- | --- |
| gateway | Java (Spring Cloud Gateway, WebFlux) | 8080 | 8080 | `http://gateway:8080` |
| catalog | Java (Spring MVC) | 8080 | 8081 | `http://catalog:8080` |
| booking | Java | 8080 | 8082 | `http://booking:8080` |
| user | Java | 8080 | 8083 | `http://user:8080` |
| payment | Java | 8080 | 8084 | `http://payment:8080` |
| ⚠️AI ai | **Python 3.12 (FastAPI·uvicorn)** | 8080 | 8085 | `http://ai:8080` |
| frontend | React (Vite) | 없음 (S3 + CloudFront) | 5173 (`/api` → 8080 프록시) | — |
| MySQL 8.4 / Valkey 8.2 / ElasticMQ (로컬) | — | 3306 / 6379 / 9324 | 같음 | RDS / ElastiCache(TLS) / SQS |
| ⚠️AI ai Valkey (로컬, `valkey/valkey-bundle` — 벡터 검색 모듈 포함) | — | 6379 | 6380 | ElastiCache `cinema-staging-ai-valkey`(TLS) |

| 경로 | 규칙 |
| --- | --- |
| 외부 API | `https://{배포ID}.cloudfront.net/api/{경로}` → ALB → gateway가 `/api` 제거(StripPrefix=1) |
| 내부 API | `/internal/**` — Gateway 라우팅 금지, NetworkPolicy로 호출자 제한 |
| 헬스 | `/actuator/health/liveness`, `/actuator/health/readiness` (ALB는 gateway readiness만). ⚠️AI ai(Python)도 **같은 경로**로 맞춘다 — 프로브·PodMonitor 템플릿 공용 |
| 메트릭 | `/actuator/prometheus` (ai도 같은 경로, `prometheus_client`) |
| Gateway 404 | `/api/internal/**`, `/api/actuator/**`, `/api/payments/**`, `/api/auth/**`, ⚠️AI `/api/ai/**`(플래그 `APP_AI_ENABLED=false`일 때 — 라우트 자체가 없다), `/api/admin/ai/**`(만들지 않음, 챗봇은 사용자만). ⚠️AI 켜져 있을 때 `/api/ai/**` 라우트는 metadata `response-timeout: 28000`(ms) — 전역 15초의 예외(SSE 라우트와 같은 방식, `docs/service-runtime-contract.md` 9-1 시간 예산) |

## 2. 코드·API 이름

| 대상 | 규칙 | 예 |
| --- | --- | --- |
| URL 경로 | 소문자 복수 명사, 단어는 `-`, ID는 `{camelCase}` | `/screenings/{screeningId}/seats`, `/seat-counts` |
| JSON 필드·쿼리 파라미터 | camelCase | `screeningId`, `hasLayout` |
| 코드 값(enum)·오류 코드·`reason` | UPPER_SNAKE | `PAYMENT_FAILED`, `SEAT_UNAVAILABLE` |
| 이벤트 `type` | 좌석 이벤트는 UPPER (`HELD`), SQS 메시지는 `{도메인}.{과거분사}` | `booking.confirmed` |
| `operationId` | camelCase 동사 시작 | `holdSeats`, `getBooking` |
| 페이징 응답 | `{ content, page, size, totalElements, totalPages }`, `page` 0부터, `size` 기본 20·최대 50 | — |
| 페이징 없는 목록 | ⚠️ `{ items }` (2.2.4 D8, API 명세서 1-3의 "JSON 배열"을 대체) | `GET /theaters` |
| Java 패키지 | `com.seatsac.{service}`, 공통은 `com.seatsac.common.{core,web,test}` | `com.seatsac.booking.hold` |
| ⚠️AI Python 패키지 | `services/ai/src/seatsac_ai/{api,snapshot,llm,sql,cache,quota}` — `libs/`(Java)는 쓰지 않는다. 오류 형식·traceId 규칙은 `seatsac_ai.common`에서 같은 계약(`error.v1.json`)으로 구현 | `seatsac_ai.sql.validator` |
| 계약 파일 | `contracts/openapi/{service}.openapi.yaml`, `openapi/internal/{service}.internal.openapi.yaml`, `events/{이름}.v{N}.json`, `schemas/{이름}.v{N}.json` | — |
| OpenAPI 확장 | `x-feature-id`, `x-required-role: ADMIN`, `x-caller` | — |

## 3. 데이터베이스

| 스키마 | 소유 | 앱 계정 (DML) | 마이그레이션 계정 (DDL) |
| --- | --- | --- | --- |
| `catalog_db` | catalog | `catalog_app` | `catalog_migrator` |
| `booking_db` | booking | `booking_app` | `booking_migrator` |
| `user_db` | user | `user_app` | `user_migrator` |
| `payment_db` | payment | `payment_app` | `payment_migrator` |

- 테이블·컬럼 `snake_case`, 테이블 단수형. 다른 스키마 FK 없음(ID 컬럼만)
- 상태 컬럼은 `VARCHAR` + 앱 enum (MySQL ENUM 금지 — 블루/그린 중 값 추가가 어려움)
- 문자셋 `utf8mb4` / `utf8mb4_0900_ai_ci`
- Flyway: `services/{svc}/src/main/resources/db/migration/V{yyyyMMddHHmm}__{설명}.sql`. 확장 → 전환 → 정리 순서로, 컬럼 삭제·이름 변경은 다음 배포에
- 시드(롯데시네마 배치·편성)는 앱 레포 Flyway에 넣지 않는다 → 비공개 `lotte-seed`
- ⚠️AI `ai`는 **RDS에 접근하지 않는다**(계정·스키마·리포트 뷰 없음, ADR-60 유지). 검색 대상은 파드 메모리 안의 SQLite 스냅샷이고, 공개 API로만 채운다(`docs/data-model.md` 6장)

## 4. Valkey 키·채널

| 키 | 소유 | TTL | 값·비고 |
| --- | --- | --- | --- |
| `catalog:movies:*`, `catalog:movie:{id}`, `catalog:theaters:*`, `catalog:screenings:{date}:{theaterId}:{movieId\|all}`, `catalog:layout:{auditoriumId}:{version}` | catalog | 10분 | 변경 시 삭제. 잔여 좌석은 캐시 금지 |
| `seat:{screeningId}:{seatId}` | booking | 선점 TTL (`APP_SEAT_HOLD_TTL_SECONDS`, 기본 300초) | holdId(UUID) |
| `user-hold:{screeningId}:{userId}` | booking | 선점 TTL (기본 300초) | 회차당 진행 중 선점 1건 |
| `seat-seq:{screeningId}` | booking | 없음 | 회차별 이벤트 일련번호. 이벤트마다 +1, **키가 없으면 현재 시각 ms × 1000부터** 시작(`seq-next.lua`) — Valkey 재시작으로 키가 사라져도 번호가 뒤로 가지 않아 열린 화면이 빈틈으로 보고 다시 받는다 |
| `hold-expiry` (sorted set) | booking | 없음 | 멤버 `{screeningId}:{holdId}`, 점수 = 만료 시각 |
| ⚠️ `idem:{userId}:{Idempotency-Key}` | booking | 24시간 | 요청 해시 + 처리 상태 + 첫 응답 (03a `idem:{키}` 대체) |

| Pub/Sub 채널 | 발행 | 구독 | 메시지 |
| --- | --- | --- | --- |
| `seat-events:{screeningId}` | booking (RDS 커밋 후) | booking 각 파드 → SSE | `contracts/events/seat-event.v1.json` ⚠️ `screeningId` 포함 |

- ⚠️ 03a의 `refresh:{사용자ID}:{토큰ID}`는 **삭제**(Cognito가 Refresh Token 관리). 요청 수 제한 `rl:`은 만들지 않는다(ADR-62)
- 새 키는 `{서비스}:{대상}:{id}` 형식으로 만들고 이 표에 소유를 추가한다

#### ⚠️AI ai Valkey (`cinema-staging-ai-valkey`, ai 전용 — 위 표의 기존 Valkey와 **다른 클러스터**)

| 키 | TTL | 값·비고 |
| --- | --- | --- |
| `ai:exact:{sha256(정규화 질문)}` | 1시간 | 정확 일치 캐시: SQL 템플릿 + 슬롯 + 경로(프리셋·NL2SQL). **결과는 캐시하지 않는다**(스냅샷에서 다시 실행) |
| `ai:semcache:{id}` (HASH, 인덱스 `ai_semcache_idx`) | 1시간 | 의미 캐시: 슬롯을 `{time}`·`{date}`·`{theater}`·`{movie}`·`{n}`으로 바꾼 질문의 임베딩(384차원 FLOAT32, COSINE) + SQL 템플릿 + 슬롯 종류 |
| `ai:session:{userId}:{sessionId}` | 30분 | 후속 질문 문맥(직전 질문·SQL·슬롯). 파드가 바뀌어도 유지. **키에 `X-User-Id`를 넣어 사용자별로 격리**한다 — 다른 사용자의 `sessionId`를 보내도 그 사용자 키에는 닿지 않는다. `sessionId`는 서버가 UUID로 발급하고, 요청의 `sessionId`가 이 사용자 키에 없으면(만료·남의 것·위조) 새 세션으로 시작한다(오류 아님) |
| `ai:quota:min:{userId}:{yyyyMMddHHmm}` · `ai:quota:day:{userId}:{yyyyMMdd}` | 60초 · 1일 | 사용 한도 카운터(분당 10·하루 100). gateway 요청 수 제한(ADR-62)과 별개인 **ai 앱 안** 한도 |
| `ai:inflight:{userId}` | **40초** | 사용자당 동시 1건. `SET NX PX 40000`, 값은 요청마다 새 토큰. 끝나면 **값이 내 토큰일 때만** 지운다(Lua 비교 후 `DEL` — 늦게 끝난 요청이 다음 요청의 잠금을 지우지 않게). TTL 40초가 gateway 라우트 28초·요청 전체 기한 25초(`APP_AI_REQUEST_DEADLINE_SECONDS`)보다 길어 갱신 없이도 처리 중 만료되지 않는다. 파드가 죽어 못 지운 경우만 TTL로 풀린다 |
| `ai:semcheck:0~2` (HASH, 인덱스 `ai_semcheck_idx`) | 120초 | 확인용(런북 6-G-6 의미 캐시 벡터 검색 확인). `HSET`·`EXPIRE`를 `MULTI/EXEC`로 함께 걸고 실행 끝에 키·인덱스를 지운다. 앱은 쓰지 않는다 |

- "내 예매" 같은 **개인 질문은 캐시하지 않는다**(다른 사용자에게 적중 방지)
- 파라미터 그룹: `maxmemory-policy noeviction`, `reserved-memory-percent 50`(t4g.micro에서 벡터 검색 필수 조건), TLS. `allkeys-lru`로 두면 메모리가 찰 때 한도·동시 1건 키(`ai:quota:*`·`ai:inflight:*`)도 지워져 한도를 우회할 수 있어서 **아무 키도 지우지 않는** `noeviction`으로 한다
- 그래서 **모든 `ai:*` 키에 TTL**을 단다(위 표). 의미 캐시는 앱이 최대 5,000건으로 제한(넘으면 저장하지 않음 — 1건 약 2.5KB, 합 약 13MB로 가용 메모리 약 250MB의 5%)
- 메모리가 차서 쓰기가 실패하면: 캐시 저장 실패는 **무시**(적중 안 됨과 같음), 한도·동시 1건 키 쓰기 실패는 **거부**(503 `AI_LLM_UNAVAILABLE`, `reason` `QUOTA_STORE_UNAVAILABLE`) — 한도 확인 없이 Claude를 부르지 않는다. 경보: 메모리 80%

## 5. SQS

| 대상 | 이름 |
| --- | --- |
| 알림 큐 | `cinema-staging-notification-events` |
| DLQ | `cinema-staging-notification-events-dlq` (maxReceiveCount 3) |
| 메시지 속성 | `type`, `traceId` (String) |
| 본문 | envelope `{eventId, type, version, occurredAt, producer, traceId, payload}` — `contracts/events/booking.confirmed.v1.json` |
| 발송 | ⚠️ booking 아웃박스(`booking_db.outbox_message`) + 릴레이(파드마다 1초). 요청 스레드에서 보내지 않음 |

## 6. 오류·헤더·시간·ID·로그

| 대상 | 규칙 |
| --- | --- |
| 오류 응답 | ⚠️ `{ code, message, traceId, details[] }` — `details`는 **항상 배열**(없으면 `[]`), 원소 `{ field, value?, reason? }`. 화면 분기는 `code`로 한다 — 단 **401 `UNAUTHORIZED`는 `details[].reason`으로 나눈다**(재로그인으로 풀리지 않는 user의 `TOKEN_REJECTED`·`IDENTITY_MISMATCH`, 아래 공통 오류 코드 표). `message`는 개발용 (`contracts/schemas/error.v1.json`) |
| 헤더 | `Authorization: Bearer {Cognito Access Token}`, `X-User-Id`(= `sub`), `X-User-Role`(`USER`·`ADMIN`), `X-Trace-Id`, `Idempotency-Key`, `CloudFront-Viewer-Address`, `X-Payment-Mock-Key`(모의 결제 허용 키 — k6·분석 Job만, 값은 booking Secret, ADR-112), 포트원 웹훅 `webhook-id`·`webhook-timestamp`·`webhook-signature`. 외부에서 들어온 `X-User-*`·`X-Trace-Id`는 Gateway가 지우거나 덮어쓴다 |
| traceId | ⚠️ 소문자 16진수 **32자리**(UUID 하이픈 제거). Gateway가 요청마다 생성, 응답 헤더에도. 요청 없는 작업(만료 스캔·릴레이)은 새로 생성 |
| 시간 | DB `DATETIME(3)` **UTC**(RDS `time_zone=UTC`, 앱 `Clock.systemUTC()`, 상태 전이 조건에 `NOW()` 대신 앱이 구한 `:now`). API·메시지는 ISO-8601 `+09:00`. `business_date`·`date` 파라미터·화면은 Asia/Seoul |
| ID | PK `BIGINT AUTO_INCREMENT`(Java `Long`, OpenAPI `int64`). ⚠️ 사용자 ID = Cognito `sub`(`CHAR(36)`, `String`). holdId·paymentId·eventId = UUID v4 `CHAR(36)` |
| 금액 | 정수(원). 1석 15,000원, `amount = seatCount × 15000` |
| 로그 | stdout JSON 한 줄: `timestamp`(+09:00), `level`, `service`, `traceId`, `userId`(있으면), `message`, `error`(있으면). 카드 번호·토큰·비밀번호 금지 |

### 공통 오류 코드

| HTTP | code | 어디서 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED`, `SCREENING_STARTED` | 전체 / booking |
| 401 | `UNAUTHORIZED` | gateway |
| 401 | `UNAUTHORIZED` | user — 프로필을 처음 만들 때 Access Token으로 Cognito `GetUser`를 부른다(ADR-80). 구분은 `code`가 아니라 **`details[].reason`**: `TOKEN_REQUIRED` 토큰 없음(Gateway의 토큰 없음 401과 같은 이름이라 화면은 구분하지 않는다) · `TOKEN_REJECTED` Cognito가 토큰을 거절(`NotAuthorizedException`) · `IDENTITY_MISMATCH` 토큰의 `sub`이 `X-User-Id`와 다름. `TOKEN_REJECTED`는 범위 누락처럼 **설정 문제면 다시 로그인해도 같다**(Gateway 검증 뒤 토큰이 폐기된 드문 경우만 새 토큰으로 풀린다). 화면은 토큰 갱신 1회 뒤에도 `TOKEN_REJECTED`·`IDENTITY_MISMATCH`면 재로그인으로 보내지 않고 안내로 끝낸다(로그인 반복 방지) |
| 403 | `FORBIDDEN` | gateway·서비스 |
| 404 | `NOT_FOUND` | 전체 |
| 401 | `UNAUTHORIZED`(reason `WEBHOOK_SIGNATURE_INVALID`) | payment 웹훅 |
| 409 | `SEAT_UNAVAILABLE`, `ACTIVE_HOLD_EXISTS`, `BOOKING_STATE_CONFLICT`, `PAYMENT_ALREADY_REQUESTED`, `IDEMPOTENCY_KEY_CONFLICT`, `IDEMPOTENCY_IN_PROGRESS` | booking |
| 409 | `SCHEDULE_CONFLICT`, `SEAT_SOLD` | catalog |
| 409 | `PAYMENT_CONFLICT`, `PAYMENT_NOT_APPROVED` | payment (내부) |
| 500 | `INTERNAL_ERROR` | 전체 |
| 503 | `SERVICE_UNAVAILABLE` | gateway·서비스 |
| 503 | `PAYMENT_RESULT_UNKNOWN` | booking |
| 503 | `BOOKING_UNAVAILABLE`, ⚠️ `IDENTITY_UNAVAILABLE` | user (`IDENTITY_UNAVAILABLE` = Cognito 호출 실패·타임아웃·장애 — **다시 시도하면 될 수 있다**. 토큰 자체가 거절된 경우는 위 401) |
| 409 | ⚠️AI `AI_QUERY_IN_PROGRESS` | ai — 같은 사용자의 질문이 처리 중(동시 1건) |
| 429 | ⚠️AI `AI_QUOTA_EXCEEDED` | ai — 사용 한도 초과. `details[].reason` `PER_MINUTE`·`PER_DAY`. gateway 요청 수 제한(ADR-62 미도입)과 다른, ai 앱 안의 비용 보호 |
| 503 | ⚠️AI `AI_LLM_UNAVAILABLE` | ai — Claude API 타임아웃·과부하·지출 한도·서킷 열림. `details[].reason` `TIMEOUT`·`OVERLOADED`·`RATE_LIMITED`·`SPEND_LIMIT`·`CIRCUIT_OPEN`·`QUOTA_STORE_UNAVAILABLE`(ai Valkey에 한도를 기록하지 못함) |
| 503 | ⚠️AI `AI_SNAPSHOT_NOT_READY` | ai — 기동 직후 SQLite 스냅샷 첫 적재 전 |

새 코드는 이 표와 해당 OpenAPI 응답 `description`에 함께 추가한다. ⚠️AI ai 코드는 `services/ai` 안에서 정의하고 `libs/`의 `CommonResponseCode`는 바꾸지 않는다(Python이라 `libs/`를 쓰지 않음). 형식은 `error.v1.json` 그대로

## 7. 메트릭

- 이름 `{service}_{대상}_{단위}`, 라벨은 `result`·`route` 등 값 종류가 적은 것만(사용자 ID·좌석 ID 금지)
- 필수 메트릭과 라벨 값은 `docs/service-runtime-contract.md` 5장
- ⚠️AI ai(Python)도 HTTP 지표는 `http_server_requests_seconds_*{application="ai",uri,status,method}` 이름으로 낸다(기록 규칙·신호등 공용). AI 지표는 `ai_*`(5장)

## 8. 환경변수·설정·Secret

| 구분 | 규칙 | 예 |
| --- | --- | --- |
| Spring 표준 | 바인딩 이름 그대로 | `SPRING_DATASOURCE_URL`, `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_SSL_ENABLED` |
| 앱 고유 | `APP_` | `APP_SEAT_HOLD_TTL_SECONDS=300`, `APP_SEAT_HOLD_STRATEGY=redis` |
| 다른 서비스 주소·타임아웃 | `APP_SERVICES_{NAME}_URL`, `_CONNECT_TIMEOUT`, `_READ_TIMEOUT` | `APP_SERVICES_PAYMENT_URL=http://payment:8080` |
| 인증 | ⚠️ `APP_COGNITO_ISSUER_URI`, `APP_COGNITO_CLIENT_ID` (gateway 앱 설정 `app.cognito.*`, Spring 표준 `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`는 쓰지 않음. 03a `APP_JWT_SECRET`·`jwt-secret` 삭제) | — |
| AWS | SDK 표준 `AWS_REGION=ap-northeast-2`. 자격 증명은 Pod Identity, 액세스 키 금지 | — |
| frontend | `VITE_` (빌드에 포함 = 공개 값만). 개발 전용(빌드에 안 들어감): `DEV_API_PROXY` — Vite 프록시 `/api` 대상(기본 `http://localhost:8080`, 목 서버는 `npm run dev:mock`) | `VITE_API_BASE_URL=/api`, `VITE_COGNITO_AUTHORITY` |
| K8s Secret (Terraform 생성) | `{service}-secrets`(앱), `{service}-migrator-secrets`(Flyway Job만) | `booking-secrets` |
| ConfigMap | `{service}-config`(gitops, 엔드포인트는 `${키}`만), ⚠️ `cinema-infra-endpoints`(Terraform, 엔드포인트 — 키는 `docs/service-runtime-contract.md` 3장) | `${DB_HOST}` |
| 프로필 | `local`, `staging` | `SPRING_PROFILES_ACTIVE=staging` |
| ⚠️AI ai(Python) | `APP_AI_*`(pydantic-settings). **Python은 `${키}` 치환이 없으므로** `cinema-infra-endpoints`의 키(`AI_VALKEY_HOST`·`PUBLIC_API_BASE_URL`)를 이름 그대로 읽는다. 프로필은 `APP_ENV=local\|staging` | `APP_AI_MODEL=claude-sonnet-5-5` |
| ⚠️AI gateway 플래그 | `APP_AI_ENABLED`(gateway `{svc}-config`, 기본 `false`) — `app.ai.enabled`. 꺼져 있으면 `/api/ai/**` 라우트가 없다 → 404 | — |
| ⚠️AI Claude API 키 | staging: Secret `ai-secrets`의 `ANTHROPIC_API_KEY`(Terraform sensitive 변수 → K8s Secret, tfstate는 암호화 S3). **로컬: 개발 전용 키를 따로 발급해 `services/ai/.env`**(`.gitignore` 대상, Git·문서·PR 금지). 지출 한도는 Anthropic 콘솔에서 월 $20 | — |

> `VITE_`에 비밀 값 금지. Secret 값은 Git·문서에 두지 않는다(Terraform 랜덤 생성 → K8s Secret, tfstate는 암호화 S3). ⚠️AI `.env`는 커밋 금지(gitleaks가 `sk-ant-` 형식을 막는다).

## 9. K8s·AWS 리소스 이름

| 대상 | 규칙 | 예 |
| --- | --- | --- |
| 공통 접두사 / 환경 | `cinema` / `staging` (환경은 staging 하나, ADR-34) | — |
| 앱 namespace | `cinema-staging` | — |
| 플랫폼 namespace | 도구 기본값 | `argocd`, `argo-rollouts`, `monitoring`, `kube-system` |
| 관리자 namespace | `cinema-ops` (팀원 조회 범위 밖, Terraform이 만드는 운영 Job·그 입력 Secret) | `db-bootstrap-*` Job (3.1.12) |
| AWS 리소스 | `cinema-staging-{대상}` | `cinema-staging-eks`, `cinema-staging-valkey`, `cinema-staging-users`(Cognito), ⚠️AI `cinema-staging-ai-valkey`, `cinema-staging-ng-ai`(노드그룹) |
| 계정 단위 리소스 | `cinema-{대상}` | `cinema-admin`, `cinema-developer`, `cinema-gha-ecr-push`, `cinema-tfstate-{계정ID}` |
| S3 버킷 | `cinema-staging-frontend-{계정ID}`, `cinema-tfstate-{계정ID}` | — |
| ECR | `cinema/{service}` (frontend 없음) | `cinema/booking`, ⚠️AI `cinema/ai` |
| 이미지 태그 | git SHA 7자리, 불변 | `cinema/booking:3f9a1c2` |
| Pod Identity 역할 | `cinema-staging-{대상}-pod` | `cinema-staging-booking-pod` |
| Lambda | `cinema-staging-{기능}` | `cinema-staging-notification`, `cinema-staging-cognito-presignup` |
| K8s 리소스 | `{service}`, Rollout 미리보기 Service `{service}-preview`, Flyway Job `{service}-flyway` | `booking-preview` |
| 공통 라벨 | `app.kubernetes.io/name={service}`, `app.kubernetes.io/part-of=cinema` | — |
| Service 포트 이름 | `http` (8080) | — |
| ⚠️AI 노드 격리 | 노드 라벨·taint `cinema/workload=ai`(taint 효과 `NoSchedule`). ai 파드만 `nodeSelector` + `toleration` | `ng-ai` |
| ⚠️AI ArgoCD Application | `cinema-ai`(Terraform `argocd_apps.tf` 목록에 추가, gitops `apps/`에는 두지 않음) | — |
| AWS 태그 | `Project=cinema`, `Env=staging`, `Owner={이름}`, `ManagedBy=terraform` | — |

## 10. Git

`docs/repo-workflow.md` (레포·브랜치·커밋·PR·CI).


<노션 반영 필요> 03a 결정 칸에 "10/22 확정(ADR-49) + ⚠️ 항목 수정"(idem 키, refresh 키·jwt-secret·rl 키 삭제, traceId 32자리, details 배열, `{items}`, seat-event `screeningId`, outbox_message, ai·stats 스키마 삭제), 03a Action Item "docs/conventions.md 커밋" 완료 처리
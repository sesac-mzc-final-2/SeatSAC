# SeatSAC (싯싹) — 팀 공통 컨텍스트

> 기준 2026-10-22 · 회의록1·2 결정(ADR-49~64) 반영 · ⚠️AI 2026-11-05 AI 재도입(ADR-82~87) 반영. `docs/` 6개 문서를 순서대로 합친 파일이다.
> **고칠 때는 `docs/`의 원본을 PR로 고치고 이 파일을 다시 만든다**(`docs/` → `CLAUDE.md`). 노션과 다르면 이 파일(레포)이 우선이다.

## 0. 먼저 읽을 것

**프로젝트**: 실시간 좌석 선점과 무중단 배포를 시연하는 영화 예매 사이트. 4명, 2026-10-14 ~ 11-20(발표). staging 하나가 곧 데모 환경이고 **main 머지 = 배포**다.

| 데모 | 증명할 것 |
| --- | --- |
| 무중단 배포 | 배포(booking·gateway 블루/그린) 중 k6 부하에서 오류율 0%, 실패 버전 자동 롤백 |
| 실시간 좌석 | 같은 좌석 100명 동시 요청 → 성공 정확히 1명, 선점 p95 300ms 이하, 다른 화면 반영 1초 이내 |
| ⚠️AI AI 챗봇 (2차, 11/17) | 사용자 질문("20시에 시작하는 영화")을 캐시·프리셋·NL2SQL로 처리, 반복·유사 질문에서 Claude API 토큰이 0이 되는 것(절감률·오적중률 함께) |

| 담당 | 영역 |
| --- | --- |
| 한채연 | gateway · catalog · user · 화면(메인·상영시간·로그인·마이페이지·결제) · 좌석 배치 시드 |
| 남궁일 | booking · payment · 좌석 화면 · 백업 관리자(`cinema-admin`) |
| 김종식 | Infra · DevOps (Terraform · CI/CD · gitops · 관측 · Lambda) · ⚠️AI ai 인프라(ng-ai·ai Valkey·Secret·CI) |
| ⚠️AI 김서현 | ai 서비스(Python FastAPI) · 스냅샷·NL2SQL·캐시·평가 세트 · AI 데모 |

**절대 규칙**

- 비밀 값(액세스 키·비밀번호·tfstate 내용·민감한 tfvars 값·MFA 정보)을 코드·문서·PR·커밋에 쓰지 않는다. 발표 후 레포가 Public이 된다
- 롯데시네마 원본·fixture·시드는 비공개 `lotte-seed`에만. 롯데 실제 예매 API 호출 금지, 롯데 포스터 이미지 표시 금지
- AWS 콘솔에서 리소스를 만들지 않는다(Terraform만). `terraform apply`는 김종식(또는 백업 관리자)이 로컬에서 `cinema-admin`으로만, CI는 plan만. bootstrap·global은 destroy 금지
- 클러스터를 `kubectl apply/edit/delete`로 직접 고치지 않는다(GitOps만). 사람이 ECR·frontend S3에 직접 올리지 않는다(CI만)
- 계약(`contracts/`)을 먼저 PR로 합의하고 구현한다. 필드 추가만, 깨는 변경은 새 엔드포인트·`.v2`
- 이름·설정·메트릭은 아래 표에 있는 것만 쓰고, 새로 만들면 표에 먼저 추가한다
- ⚠️AI ai는 기존 MSA DB(RDS)·기존 Valkey·기존 NetworkPolicy·다른 Application을 건드리지 않는다. M6(11/9~13)에는 ai 경로만 머지할 수 있고, 부하·전환 테스트 중에는 `ng-ai` 0대·플래그 off, 좌석 데모 중에는 `ng-ai` 0대·AI 질의 금지(플래그는 rc2에서 켜 둔 채 — 데모 주간은 머지 금지라 끌 수 없다)

---

# 1. 네이밍·공통 규약 (`docs/conventions.md`)

> 기준 2026-10-22 (회의록2 결정 반영, ADR-49~64). 03a 명명 규칙 개정을 확정한 최종본. 03a 이후 결정(Cognito ADR-37, API 명세서 v1.1, 2.2.4 v0.3, ERD 6차)을 반영해 03a와 다른 곳은 ⚠️로 표시했다.
> 범위: 서비스 6개(gateway·catalog·booking·user·payment + ⚠️ ai) + Lambda 2개(notification·cognito-presignup) (ADR-51·82).
> ⚠️ 11/5 AI 재도입(ADR-82~87): `ai`는 Python(FastAPI) 사용자 챗봇. 기존 5개 서비스·RDS·기존 Valkey는 건드리지 않는다. 아래 ⚠️AI 표시 행이 새로 생긴 규칙이다.
> 이 문서가 노션 회의록과 다르면 이 문서를 따르고, 바꿀 때는 PR로 고친다.

---

### 1. 서비스·포트·경로

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

### 2. 코드·API 이름

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

### 3. 데이터베이스

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

### 4. Valkey 키·채널

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

##### ⚠️AI ai Valkey (`cinema-staging-ai-valkey`, ai 전용 — 위 표의 기존 Valkey와 **다른 클러스터**)

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

### 5. SQS

| 대상 | 이름 |
| --- | --- |
| 알림 큐 | `cinema-staging-notification-events` |
| DLQ | `cinema-staging-notification-events-dlq` (maxReceiveCount 3) |
| 메시지 속성 | `type`, `traceId` (String) |
| 본문 | envelope `{eventId, type, version, occurredAt, producer, traceId, payload}` — `contracts/events/booking.confirmed.v1.json` |
| 발송 | ⚠️ booking 아웃박스(`booking_db.outbox_message`) + 릴레이(파드마다 1초). 요청 스레드에서 보내지 않음 |

### 6. 오류·헤더·시간·ID·로그

| 대상 | 규칙 |
| --- | --- |
| 오류 응답 | ⚠️ `{ code, message, traceId, details[] }` — `details`는 **항상 배열**(없으면 `[]`), 원소 `{ field, value?, reason? }`. 화면 분기는 `code`로 한다 — 단 **401 `UNAUTHORIZED`는 `details[].reason`으로 나눈다**(재로그인으로 풀리지 않는 user의 `TOKEN_REJECTED`·`IDENTITY_MISMATCH`, 아래 공통 오류 코드 표). `message`는 개발용 (`contracts/schemas/error.v1.json`) |
| 헤더 | `Authorization: Bearer {Cognito Access Token}`, `X-User-Id`(= `sub`), `X-User-Role`(`USER`·`ADMIN`), `X-Trace-Id`, `Idempotency-Key`, `CloudFront-Viewer-Address`. 외부에서 들어온 `X-User-*`·`X-Trace-Id`는 Gateway가 지우거나 덮어쓴다 |
| traceId | ⚠️ 소문자 16진수 **32자리**(UUID 하이픈 제거). Gateway가 요청마다 생성, 응답 헤더에도. 요청 없는 작업(만료 스캔·릴레이)은 새로 생성 |
| 시간 | DB `DATETIME(3)` **UTC**(RDS `time_zone=UTC`, 앱 `Clock.systemUTC()`, 상태 전이 조건에 `NOW()` 대신 앱이 구한 `:now`). API·메시지는 ISO-8601 `+09:00`. `business_date`·`date` 파라미터·화면은 Asia/Seoul |
| ID | PK `BIGINT AUTO_INCREMENT`(Java `Long`, OpenAPI `int64`). ⚠️ 사용자 ID = Cognito `sub`(`CHAR(36)`, `String`). holdId·paymentId·eventId = UUID v4 `CHAR(36)` |
| 금액 | 정수(원). 1석 15,000원, `amount = seatCount × 15000` |
| 로그 | stdout JSON 한 줄: `timestamp`(+09:00), `level`, `service`, `traceId`, `userId`(있으면), `message`, `error`(있으면). 카드 번호·토큰·비밀번호 금지 |

#### 공통 오류 코드

| HTTP | code | 어디서 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED`, `SCREENING_STARTED` | 전체 / booking |
| 401 | `UNAUTHORIZED` | gateway |
| 401 | `UNAUTHORIZED` | user — 프로필을 처음 만들 때 Access Token으로 Cognito `GetUser`를 부른다(ADR-80). 구분은 `code`가 아니라 **`details[].reason`**: `TOKEN_REQUIRED` 토큰 없음(Gateway의 토큰 없음 401과 같은 이름이라 화면은 구분하지 않는다) · `TOKEN_REJECTED` Cognito가 토큰을 거절(`NotAuthorizedException`) · `IDENTITY_MISMATCH` 토큰의 `sub`이 `X-User-Id`와 다름. `TOKEN_REJECTED`는 범위 누락처럼 **설정 문제면 다시 로그인해도 같다**(Gateway 검증 뒤 토큰이 폐기된 드문 경우만 새 토큰으로 풀린다). 화면은 토큰 갱신 1회 뒤에도 `TOKEN_REJECTED`·`IDENTITY_MISMATCH`면 재로그인으로 보내지 않고 안내로 끝낸다(로그인 반복 방지) |
| 403 | `FORBIDDEN` | gateway·서비스 |
| 404 | `NOT_FOUND` | 전체 |
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

### 7. 메트릭

- 이름 `{service}_{대상}_{단위}`, 라벨은 `result`·`route` 등 값 종류가 적은 것만(사용자 ID·좌석 ID 금지)
- 필수 메트릭과 라벨 값은 `docs/service-runtime-contract.md` 5장
- ⚠️AI ai(Python)도 HTTP 지표는 `http_server_requests_seconds_*{application="ai",uri,status,method}` 이름으로 낸다(기록 규칙·신호등 공용). AI 지표는 `ai_*`(5장)

### 8. 환경변수·설정·Secret

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

### 9. K8s·AWS 리소스 이름

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

### 10. Git

`docs/repo-workflow.md` (레포·브랜치·커밋·PR·CI).


---

# 2. 서비스 계약 (`docs/contracts.md`)

> 기준 2026-10-21. 원본은 `contracts/` 파일이고 이 문서는 목록과 확정 결정만 담는다. 파일과 이 문서가 다르면 **파일이 맞다**.
> 근거: API 명세서 v1.1(10/21), 2.2.4 Catalog·User API 명세 v0.3(10/21), 계약 대조표(15번), 10/22 회의록2 결정(ADR-54·55).
> ⚠️AI 11/5 AI 재도입(ADR-82) · 11/6 WBS 2.2.8: `openapi/ai.openapi.yaml` 외부 2개 + 응답 예시 5종 **추가**(이 파일이 기준, 구현은 3.4.x). 상세는 API 명세서 v1.4 15장.

---

### 1. 파일

| 파일 | 내용 | 담당 (CODEOWNERS) |
| --- | --- | --- |
| `openapi/catalog.openapi.yaml` | 외부 9개 (조회 6 · 관리자 3) | 한채연 |
| `openapi/booking.openapi.yaml` | 외부 7개 (좌석 현황·SSE·선점·해제·결제·조회·취소) | 남궁일 |
| `openapi/user.openapi.yaml` | 외부 5개 (프로필 조회·수정, 예매 내역, 선호 조회·저장) | 한채연 |
| ⚠️AI `openapi/ai.openapi.yaml` | 외부 2개 (챗봇 질문 `POST /ai/chat`, 추천 질문 목록 `GET /ai/presets`) | 김서현 · 한채연 |
| ⚠️AI `openapi/examples/ai/*.json` | 챗봇 응답 예시 5종(CACHE_SEMANTIC·PRESET·NL2SQL·MY_BOOKINGS·REJECTED). OpenAPI Example Object `{ summary, value }` — `value`가 fixture, YAML이 `$ref`로 참조해 Redocly가 스키마로 검증 | 김서현 |
| `openapi/internal/catalog.internal.openapi.yaml` | 내부 3개 | 한채연 |
| `openapi/internal/booking.internal.openapi.yaml` | 내부 2개 | 남궁일 |
| `openapi/internal/payment.internal.openapi.yaml` | 내부 3개 | 남궁일 |
| `events/seat-event.v1.json` | Valkey Pub/Sub → SSE | 남궁일 |
| `events/booking.confirmed.v1.json` | SQS 예매 완료 알림 | 남궁일 (소비 Lambda 김종식) |
| `schemas/error.v1.json` | 공통 오류 응답 | 전원 |
| `*/examples/**` | 예시 메시지 = 서비스 테스트 fixture | 각 담당 |

합계: 외부 23(⚠️AI ai 2개 포함) · 내부 8 · 이벤트 2.

### 2. API 목록

#### 외부 (`/api` 접두사, Gateway StripPrefix)

| 인증 | 메서드·경로 | 기능 ID | 비고 |
| --- | --- | --- | --- |
| 없음 | `GET /movies` | CAT-01 | 페이징 |
| 없음 | `GET /movies/{movieId}` | CAT-02 | TMDB 메타 |
| 없음 | `GET /theaters` | CAT-03 | `{items}` |
| 없음 | `GET /screenings?date&theaterId&movieId` | CAT-03 | `{items}`, `remainingSeatsSource` |
| 없음 | `GET /screenings/{screeningId}` | CAT-03 | |
| 없음 | `GET /auditoriums/{auditoriumId}/layout?version` | CAT-04 | 좌석 상태 없음 |
| ADMIN | `POST /admin/movies`, `POST /admin/screenings`, `PATCH /admin/auditoriums/{id}/seats` | CAT-06, LAY-07 | P3 |
| 없음 | `GET /screenings/{screeningId}/seats` | BKG-01 | **booking**, Gateway 1순위 라우트 |
| 없음 | `GET /screenings/{screeningId}/seats/stream` | BKG-05 | SSE, **booking** |
| USER | `POST /seats/hold` | BKG-02 | `Idempotency-Key` 필수 |
| USER | `GET /bookings/{bookingId}` | — | 결제 결과 재확인 |
| USER | `DELETE /bookings/{bookingId}/hold` | BKG-03 | |
| USER | `POST /bookings/{bookingId}/pay` | BKG-10 | `Idempotency-Key` 필수, 거절도 200 |
| USER | `POST /bookings/{bookingId}/cancel` | BKG-07 | |
| USER | `GET /users/me`, `PATCH /users/me` | — | 첫 호출 때 프로필 생성 |
| USER | `GET /users/me/bookings` | USR-04 | 페이징 |
| USER | `GET /users/me/preferences`, `PUT /users/me/preferences` | USR-05 | |
| ⚠️AI USER | `POST /ai/chat` | AI-01 | **ai**, 플래그 `APP_AI_ENABLED` 꺼지면 404(gateway). `{message 1~300자, sessionId?}` → `{sessionId, answer, path, columns, rows, rowCount, truncated, sql, presetId, asOf, llm, latencyMs, rejection}` — 모든 필드가 항상 있고 해당 없으면 null. `path` `CACHE_EXACT`·`CACHE_SEMANTIC`·`PRESET`·`NL2SQL`·`MY_BOOKINGS`·`REJECTED`·`OUT_OF_SCOPE`. `sessionId`는 서버가 발급하며, 이 사용자의 세션이 아니면 새 세션으로 시작한다. SQL 거부·범위 밖도 **200**(`REJECTED`는 `rejection {reason, message, object?}`). 409 `AI_QUERY_IN_PROGRESS` · 429 `AI_QUOTA_EXCEEDED`(`Retry-After`, `details[{field: quota, value: 10/min·100/day, reason: PER_MINUTE·PER_DAY}]`) · 503 `AI_LLM_UNAVAILABLE`(`details[{field: provider, value: anthropic, reason}]`)·`AI_SNAPSHOT_NOT_READY`·`SERVICE_UNAVAILABLE`(내 예매 공개 API 실패 `upstream: user`, 또는 ai 파드 없음). `Idempotency-Key` 없음(조회 전용) |
| ⚠️AI USER | `GET /ai/presets` | AI-04 | **ai**, 추천 질문 목록 `{items:[{presetId, title, example}]}` — 버튼을 누르면 `example`을 그대로 `POST /ai/chat`의 `message`로. 화면은 로그인 뒤 이 API를 먼저 불러 404면 챗봇 버튼 숨김, 503이면 비활성 안내 |

#### 내부 (`http://{service}:8080/internal/**`)

| 호출 | API | 타임아웃 | 실패 시 |
| --- | --- | --- | --- |
| booking → catalog | `GET /internal/screenings/{id}` | 1초 / 3초 | 선점·취소 503 |
| user → catalog | `POST /internal/screenings/summaries` (최대 100) | 1초 / 3초 | `screening: null` |
| user → catalog | `GET /internal/theaters?ids=` (최대 10) | 1초 / 3초 | 극장 검증 생략 |
| user → booking | `GET /internal/bookings?userId&status&page&size` | 1초 / 3초 | 503 `BOOKING_UNAVAILABLE` |
| catalog → booking | `GET /internal/screenings/seat-counts?ids=` (최대 100) | **300ms** 전체 | `remainingSeatsSource: TOTAL` |
| booking → payment | `POST /internal/payments` | 1초 / 3초, 타임아웃·연결 오류만 같은 paymentId로 1회 재시도 → 조회 | 503 `PAYMENT_RESULT_UNKNOWN` |
| booking → payment | `GET /internal/payments/{id}` | 1초 / 3초 | 결과 미확인 |
| booking → payment | `POST /internal/payments/{id}/cancel` `{reason}` | 1초 / 3초 | 503 (화면 재시도) |

공통: 재시도 없음(위 1건 제외), Resilience4j 서킷(실패율 50%·10초), 호출 깊이 1, `X-Trace-Id`·`X-User-Id` 전달.

⚠️AI ai는 내부 API를 부르지 않는다. 공개 API(`GET /movies`·`/movies/{id}`·`/theaters`·`/screenings`, 사용자 토큰으로 `GET /users/me/bookings`)를 CloudFront 주소로 호출한다(ADR-84). 기존 계약 변경 없음.

### 3. 확정 결정 (10/22)

| # | 항목 | 결정 | 근거 |
| --- | --- | --- | --- |
| 1 | OpenAPI 버전 | **3.1.0** (error·이벤트 스키마를 JSON Schema 그대로 `$ref`) | 2.2.7 |
| 2 | userId | Cognito `sub` 문자열(`format: uuid`), DB `CHAR(36)` | ADR-37, ERD 6차 |
| 3 | 프로필 email·name | user가 Access Token으로 Cognito `GetUser`. `NotAuthorizedException`은 **401 `UNAUTHORIZED`**(`details[].reason` `TOKEN_REJECTED`), 그 밖의 실패는 **503 `IDENTITY_UNAVAILABLE`** | 2.2.4 v0.3, ADR-54 |
| 4 | `GET /internal/theaters` | **만든다** (USR-05 극장 검증, Catalog 실패 시 검증 생략) | API 명세서 10장 #6 |
| 5 | 범위 | 위 29개 + ⚠️AI ai 외부 2개 = 31개. gateway 요청 수 제한(429)은 없음 — ai의 429 `AI_QUOTA_EXCEEDED`는 ai 앱 안 비용 보호 | ADR-62·82 |
| 6 | booking.confirmed v1 payload | `screeningId`·`seatCount`·`confirmedAt` **필수** (발행자 booking 하나, 구현 전) | API 명세서 9-2, ADR-55 |
| 7 | traceId 형식 | 스키마에 `pattern: ^[0-9a-f]{32}$` (오류·좌석 이벤트·알림 공통), 오류의 traceId는 null 불가 | GW-03 10/21, ADR-55 |
| 8 | 오류 `details` | **항상 배열**(필수, 없으면 `[]`), 원소 `{field, value?, reason?}` | 2.2.4 v0.3, API 명세서 1-4 |
| 9 | 페이징 없는 목록 | `{items}` (`/theaters`, `/screenings`, 내부 summaries·theaters·seat-counts) | 2.2.4 D8, ADR-54 |
| 10 | 내부 회차 API의 좌석 ID | **삭제**. booking은 CAT-04 배치도를 (상영관, 버전) 키로 파드 캐시 | 2.2.4 v0.3 |
| 11 | 결제 | paymentId = booking 발급 UUID, `PROCESSING` 상태, 거절도 200, 모의 카드 0000·1111·9999 | API 명세서 10장 #3~5, ADR-13 |
| 12 | 예매 완료 알림 | 아웃박스 + 릴레이, `eventId` = 아웃박스 UUID(재전송 같은 값) | 10/21 확정 |
| 13 | SSE 적용 규칙 | `seq == lastSeq + 1`일 때만 적용, 빈틈이면 200ms 뒤 BKG-01 재조회 | API 명세서 v1.1 |
| 14 | 하위 호환 | 필드 추가만. 깨지는 변경은 새 엔드포인트·`.v2` 파일. 구현 전 설계 변경만 `contract-break-ok` 라벨 | ADR-32·55, 2.2.7 |

### 4. 바꾸는 방법

1. `contracts/` 파일을 먼저 고치는 PR (구현보다 먼저)
2. 로컬에서 `contracts/tools/check.sh` → CI `contract-check`(lint · 스키마·예시 · 하위 호환)
3. CODEOWNERS 승인(영향받는 담당 전원). 깨지는 변경이면 PR 본문 "계약 변경"에 이유
4. 구현 후(2단계): `pr-check`가 springdoc `/v3/api-docs` 생성본과 계약을 `oasdiff breaking`으로 비교 → Spring은 `springdoc.api-docs.version: openapi_3_1`

### 5. 이번 확정으로 생기는 하위 호환 ERROR (첫 PR에서 라벨 필요)

2.2.7 골격 대비 의도된 변경이다. 아직 구현 전이라 `contract-break-ok` + CODEOWNERS(한채연·남궁일) 승인으로 한 번에 넣는다.

| 파일 | 변경 |
| --- | --- |
| `payment.internal.openapi.yaml` | 결제·취소 요청 본문 필수 추가, `paymentId` 경로 파라미터 `format: uuid`(형식 오류는 400 `VALIDATION_FAILED`) |
| `error.v1.json` (→ 모든 OpenAPI 오류 응답) | `details` 필수, `traceId` null 불가 + pattern |
| `booking.confirmed.v1.json` | payload 필수 3개, `userId` uuid, `traceId` pattern |
| `seat-event.v1.json` | `traceId` pattern |


---

# 3. 서비스 실행 계약 (앱 ↔ 인프라) (`docs/service-runtime-contract.md`)

> 기준 2026-10-21. 개발(서비스 코드)과 인프라/데브옵스(Terraform·gitops·CI)가 **맞닿는 모든 약속**을 한곳에 둔다. 이름 규칙은 `docs/conventions.md`, API는 `contracts/`.
> 이 표대로 만들면 서로 회의 없이 붙는다. 표를 바꿀 때는 PR에 양쪽(서비스 담당 + 김종식) 승인을 받는다.
> 범위: 노드 t3.large 2대(ng-a 1 · ng-c 1, ADR-7·34) + ⚠️AI ai 전용 `ng-ai` 1대(평소 0대, ADR-86), 환경 staging 하나. 값은 10/22 확정(ADR-57·58·59·63), ⚠️AI 11/5 AI 재도입(ADR-82~87)은 각 장의 「ai」 항목과 9장.

---

### 1. 모든 서비스 공통 (Java 5개 — ⚠️AI Python `ai`는 9장)

| 항목 | 앱이 할 일 | 인프라가 할 일 |
| --- | --- | --- |
| 포트 | 8080 하나 (앱·actuator 같은 포트) | Service `{svc}:8080`, 포트 이름 `http` |
| 헬스 | `management.endpoint.health.probes.enabled=true`. readiness 그룹 = `readinessState,db` (gateway는 `readinessState`만). liveness에 외부 의존 넣지 않음 | 프로브(아래 2장) |
| 메트릭 | `micrometer-registry-prometheus`, `/actuator/prometheus` 노출, 공통 태그 `application={svc}`. `http.server.requests` 히스토그램 켜기(`management.metrics.distribution.percentiles-histogram.http.server.requests=true`) | PodMonitor `{svc}`(라벨 `app.kubernetes.io/name`, 포트 `http`, 15초, 파드 라벨 `rollouts-pod-template-hash`를 메트릭에 붙임). ServiceMonitor는 active·preview Service가 같은 파드를 두 번 수집해서 쓰지 않는다 |
| 종료 | `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=20s`. booking은 SIGTERM 때 SSE 연결을 정상 종료 | `preStop: sleep 5`, `terminationGracePeriodSeconds: 35` |
| 로그 | stdout JSON 한 줄(`libs/common-web` logback). 파일 로그 금지 | `kubectl logs`로 traceId 검색 (Loki 없음, OPS-09 P3) |
| 공통 코드 | `libs/common-core`·`common-web` 의존 → 오류 형식·traceId·`@CurrentUser`·내부 호출 클라이언트 자동 적용 | `libs/**` 변경 시 `pr-check`가 5개 전부 테스트, 배포는 안 함 |
| 시간 | JDBC URL에 `connectionTimeZone=UTC`, `Clock.systemUTC()` 주입 | RDS 파라미터 그룹 `time_zone=UTC` |
| 설정 주입 | 아래 3장 이름만 읽는다. 기본값은 `application-local.yml`에만. `${DB_HOST}` 같은 자리 표시는 Spring이 기동 때 치환한다(앱 코드 변경 없음) | `envFrom`: ConfigMap `cinema-infra-endpoints`(Terraform, 엔드포인트) + `{svc}-config`(gitops) + Secret `{svc}-secrets` |
| 이미지 | `bootJar` 결과 `build/libs/app.jar`, Dockerfile은 4장 템플릿 | `build-push`가 x86 빌드 → ECR `cinema/{svc}:{sha7}` → gitops에는 태그만, 레지스트리는 Terraform ArgoCD Application `kustomize.images` |
| 비루트 | 쓰기 경로 `/tmp`만 | `runAsNonRoot: true`, `readOnlyRootFilesystem: true` + `/tmp` emptyDir |

### 2. 서비스별 배포 방식·자원

| 서비스 | 배포 | replicas (HPA) | requests cpu / mem | limits mem | Valkey | RDS 스키마 | Pod Identity |
| --- | --- | --- | --- | --- | --- | --- | --- |
| gateway | **블루/그린** (Rollout) | 1 (HPA 끔, 블루/그린 중 2) | 250m / 512Mi | 768Mi | — | — | 없음 |
| booking | **블루/그린** (Rollout) | 1 (1~4, CPU 70%) | 250m / 512Mi | 768Mi | ✅ | `booking_db` | `cinema-staging-booking-pod`: `sqs:SendMessage`·`GetQueueUrl` on 알림 큐 |
| catalog | 롤링 (maxUnavailable 0, maxSurge 1) | 1 (1~4) | 200m / 448Mi | 640Mi | ✅ | `catalog_db` | 없음 |
| user | 롤링 | 1 (1~2) | 150m / 384Mi | 576Mi | — | `user_db` | 없음 (`GetUser`는 Access Token으로 호출, IAM 불필요) |
| payment | 롤링 | 1 (고정) | 150m / 384Mi | 576Mi | — | `payment_db` | 없음 |
| ⚠️AI ai | 롤링 (maxUnavailable 0, maxSurge 1) | 1 (고정, HPA 없음) | 250m / 1Gi (임베딩 모델 포함) | 1.5Gi | **ai Valkey**(별도) | **없음** (SQLite 메모리 스냅샷) | 없음 (Claude API는 키, AWS 권한 불필요) |

- JVM: `-XX:MaxRAMPercentage=75` (Dockerfile). limits cpu는 두지 않는다(스로틀로 p95 악화)
- ⚠️AI ai는 `ng-ai` 노드에만 뜬다(`nodeSelector cinema/workload=ai` + toleration). 기존 5개 서비스는 taint 때문에 `ng-ai`에 올라가지 않는다. `ng-ai` desired 0이면 ai 파드는 Pending(정상) — 나머지 서비스·데모에 영향 없음
- 두 서비스 동시 블루/그린 금지(노드 2대). 공통 모듈 급한 반영도 한 번에 한 서비스씩: catalog·user·payment → booking → gateway
- 프로브: `startupProbe` readiness 경로 5초 × 30회, `readinessProbe` 5초 / 실패 3, `livenessProbe` 10초 / 실패 3
- 블루/그린: active Service `{svc}`, preview Service `{svc}-preview`, `prePromotionAnalysis` 통과 후 전환, `scaleDownDelaySeconds: 60`. gateway는 ALB 대상 그룹 확인(ADR-35)
- 블루/그린 파드 수: 분석 중 preview는 **1개**(`previewReplicaCount: 1`, active는 HPA 그대로). 전환 직전 preview를 active 수까지 늘리므로 **전환 순간~60초만 2배**(booking HPA 4 → 8개, requests 2 vCPU) → 노드 2대에서 preview가 Pending이면 전환이 멈추고 10분 뒤 abort. 데모 주간 booking `maxReplicas` 2 여부는 ⏳ 남궁일 확인
- booking 만료 스캔·아웃박스 릴레이는 **preview 파드에서도 돈다** → 만료·릴레이 로직 변경은 이전 버전과 호환되게(조건부 UPDATE로 한 번만 처리)

### 3. 설정·Secret (서비스가 읽는 이름)

값은 예시. **SeatSAC-gitops가 Public이라(ADR-74) 엔드포인트·계정 ID는 gitops에 쓰지 않는다**: `{svc}-config`(gitops)에는 `${DB_HOST}` 같은 자리만 두고, 실제 값은 Terraform ConfigMap `cinema-infra-endpoints`(`envs/staging-k8s/infra_endpoints.tf`)에서, 비밀번호는 Secret에서 온다.

##### Terraform ConfigMap `cinema-infra-endpoints` (`cinema-staging`, 모든 서비스·Flyway Job이 받는다)

| 키 | 값 | 쓰는 곳 |
| --- | --- | --- |
| `DB_HOST` | RDS 주소 (`aws_db_instance.address`) | `SPRING_DATASOURCE_URL`, Flyway Job `FLYWAY_URL` |
| `VALKEY_HOST` | Valkey primary 엔드포인트 | `SPRING_DATA_REDIS_HOST` |
| `SQS_NOTIFICATION_URL` | `https://sqs.ap-northeast-2.amazonaws.com/{계정ID}/cinema-staging-notification-events` | booking `APP_SQS_NOTIFICATION_QUEUE_URL` |
| `COGNITO_ISSUER_URI` | `https://cognito-idp.ap-northeast-2.amazonaws.com/{userPoolId}` (Cognito 전에는 빈 값) | gateway `APP_COGNITO_ISSUER_URI` |
| `COGNITO_CLIENT_ID` | 앱 클라이언트 ID (Cognito 전에는 빈 값) | gateway `APP_COGNITO_CLIENT_ID` |

| ⚠️AI `AI_VALKEY_HOST` | ai Valkey primary 엔드포인트(`cinema-staging-ai-valkey`) | ai (이름 그대로 읽음) |
| ⚠️AI `PUBLIC_API_BASE_URL` | `https://{배포ID}.cloudfront.net/api` (CloudFront 기본 도메인, Terraform 출력) | ai — 스냅샷 적재·"내 예매" 호출 |

새 엔드포인트가 생기면 이 표와 `infra_endpoints.tf`에 먼저 추가하고, gitops에는 `${키}`만 쓴다. ⚠️AI Python은 `${키}` 치환을 하지 않으므로 ai는 이 ConfigMap 키를 **이름 그대로** 읽는다.

#### 공통 (ConfigMap `{svc}-config`)

| 키 | 값 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `staging` |
| `AWS_REGION` | `ap-northeast-2` |
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://${DB_HOST}:3306/{svc}_db?connectionTimeZone=UTC&sslMode=REQUIRED` |
| `SPRING_DATASOURCE_USERNAME` | `{svc}_app` |
| (`FLYWAY_URL`) | ⚠️ ConfigMap이 아니라 **Flyway Job의 env**(gitops `base/{svc}/flyway-job.yaml`): `DB_HOST`를 `cinema-infra-endpoints`에서 받아 `jdbc:mysql://$(DB_HOST):3306/{svc}_db?useSsl=true&serverSslCert=/etc/rds-ca/rds-ca.pem&trustServerCertificate=false&disableSslHostnameVerification=false&sslMode=verify-full`로 조립(쿠버네티스 env 치환, 10/25). **Flyway Job 전용**(DB 쓰는 서비스만, 앱은 읽지 않는다). flyway 11.7 이미지에는 MySQL Connector/J가 없어 MariaDB Connector/J **2.7.x**가 `jdbc:mysql://`를 처리한다. 2.x의 TLS 옵션은 `useSsl`이고(`sslMode`는 3.x 옵션이라 2.x는 무시 → TLS가 켜지지 않아 `RSA public key is not available`로 실패), `serverSslCert`로 준 RDS CA로 **서버 인증서·호스트 이름을 검증**한다. `trustServerCertificate=false`·`disableSslHostnameVerification=false`는 2.x 기본값이지만 검증을 끄지 않는다는 뜻을 URL에 명시한다. CA는 ConfigMap `rds-ca-bundle`(Terraform `envs/staging-k8s/rds_ca.tf`)을 `/etc/rds-ca`에 마운트. `sslMode=verify-full`은 3.x로 바뀔 때를 위한 것 |
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | `5` |
| `SPRING_FLYWAY_ENABLED` | `false` (마이그레이션은 Flyway 훅 Job) |
| `SPRING_DATA_REDIS_HOST` / `_PORT` / `_SSL_ENABLED` | `${VALKEY_HOST}` / `6379` / `true` (Valkey 쓰는 서비스만) |

#### 서비스별

| 서비스 | ConfigMap 키 | Secret `{svc}-secrets` 키 |
| --- | --- | --- |
| gateway | ⚠️AI `APP_AI_ENABLED`(기본 `false` — 꺼져 있으면 `/api/ai/**` 라우트 없음 → 404, rc2 M6 테스트 후 `true`), `APP_SERVICES_AI_URL=http://ai:8080` — ai 라우트는 **metadata `response-timeout: 28000`**(ms, 전역 `httpclient.response-timeout: 15s`의 예외, SSE 라우트의 `-1`과 같은 방식. 없으면 15초에 `UPSTREAM_TIMEOUT`). gateway는 `APP_SERVICES_*_READ_TIMEOUT`을 읽지 않으므로 `APP_SERVICES_AI_READ_TIMEOUT`은 만들지 않는다, `APP_COGNITO_ISSUER_URI=${COGNITO_ISSUER_URI}`, `APP_COGNITO_CLIENT_ID=${COGNITO_CLIENT_ID}`, `APP_SERVICES_{CATALOG,BOOKING,USER}_URL` (issuer·client-id는 기본값이 없어 빠지면 기동 실패. `app.cognito.issuer-uri`·`app.cognito.client-id`로 바인딩 → `JwtDecoderConfig`가 issuer·`client_id` 검증) | 없음 |
| catalog | `APP_SERVICES_BOOKING_URL`, `APP_SERVICES_BOOKING_READ_TIMEOUT=300ms`, `APP_CACHE_TTL=10m` | `SPRING_DATASOURCE_PASSWORD` |
| booking | `APP_SERVICES_{CATALOG,PAYMENT}_URL`, `APP_SQS_NOTIFICATION_QUEUE_URL=${SQS_NOTIFICATION_URL}`, `APP_SEAT_HOLD_TTL_SECONDS=300`(30~600, 벗어나면 기동 실패. 멱등 처리 중 키도 같은 값. 바꾸면 이전 TTL 동안 고아 판정이 어긋나므로 데모 준비 때 한 번만), `APP_SEAT_HOLD_STRATEGY=redis`(ADR-45 인터페이스, db-lock은 나중), `APP_OUTBOX_RELAY_INTERVAL=1s`, ⚠️ `APP_OUTBOX_RELAY_ENABLED` — 알림 큐(3.1.9)가 생기기 전에는 **`false`**(기본값). 큐가 없는 동안 켜 두면 확정마다 10회 재시도 끝에 아웃박스 행이 `FAILED`가 되어 사람이 손으로 되살려야 한다. 꺼 두면 `PENDING`으로 쌓여 있다가 켜는 순간 흘러간다(쌓인 양은 지표 `booking_outbox_pending`). 3.1.9 큐와 3.1.8 알림 Lambda 연결 뒤 `true`(런북 6-B-3) — 큐만 있고 받는 쪽이 없으면 메시지가 DLQ로도 못 가고 7일 뒤 사라진다` | `SPRING_DATASOURCE_PASSWORD` |
| user | `APP_SERVICES_{BOOKING,CATALOG}_URL`, `APP_COGNITO_ENDPOINT`(선택, 기본 `https://cognito-idp.{AWS_REGION}.amazonaws.com`, `AWS_REGION`이 없으면 `ap-northeast-2` — 프로필 첫 생성 때 `GetUser`) | `SPRING_DATASOURCE_PASSWORD` |
| payment | `APP_PAYMENT_MOCK_DELAY_CARD=9999`, `APP_PAYMENT_MOCK_DELAY=5s` | `SPRING_DATASOURCE_PASSWORD` |
| ⚠️AI ai | 9장 표 (`APP_AI_*`) | `ANTHROPIC_API_KEY` (Secret 이름 `ai-secrets`) |

- 서비스 간 타임아웃 기본 `APP_SERVICES_{NAME}_CONNECT_TIMEOUT=1s`, `_READ_TIMEOUT=3s` (`libs/common-web` 기본값, 필요할 때만 덮어씀)
- Flyway Job 전용 Secret `{svc}-migrator-secrets`: `FLYWAY_USER={svc}_migrator`, `FLYWAY_PASSWORD`. 앱 파드에는 마운트하지 않는다
- 로컬(`local` 프로필): Compose MySQL·Valkey·ElasticMQ, 계정 비밀번호 `local`, SQS 엔드포인트 `http://localhost:9324`. Cognito는 staging 사용자 풀을 같이 쓴다(콜백 `http://localhost:5173/callback`)
- ⚠️AI 로컬 ai: Compose `valkey-ai`(6380) + `uvicorn` 직접 실행, `services/ai/.env`에 **개발 전용** `ANTHROPIC_API_KEY`(커밋 금지), `PUBLIC_API_BASE_URL=http://localhost:8080/api`(로컬 gateway) 또는 staging CloudFront

#### frontend (빌드 시 `VITE_`, 공개 값만)

| 키 | 값 |
| --- | --- |
| `VITE_API_BASE_URL` | `/api` |
| `VITE_COGNITO_AUTHORITY` | `https://cognito-idp.ap-northeast-2.amazonaws.com/{userPoolId}` |
| `VITE_COGNITO_CLIENT_ID` | 공개 클라이언트 ID (시크릿 없음) |
| `VITE_COGNITO_DOMAIN` | `https://cinema-staging.auth.ap-northeast-2.amazoncognito.com` |
| `VITE_COGNITO_REDIRECT_URI` | `https://{배포ID}.cloudfront.net/callback` (로컬 `http://localhost:5173/callback`) |

`frontend-deploy` 워크플로가 Terraform 출력에서 값을 넣는다(GitHub Variables). OIDC `scope`는 `openid email profile aws.cognito.signin.user.admin`으로 설정한다. 프로필 행이 없을 때 user 서비스가 같은 Access Token으로 Cognito `GetUser`를 호출하고, 기존 행이 있으면 호출하지 않는다. `NotAuthorizedException`은 401 `UNAUTHORIZED`(`details[].reason` `TOKEN_REJECTED`)로, 그 밖의 Cognito 실패는 503 `IDENTITY_UNAVAILABLE`로 반환한다. 토큰은 `react-oidc-context`가 관리하고 `Authorization: Bearer`로 보낸다. 저장소는 **메모리**(`InMemoryWebStorage`, localStorage 금지). 새로고침하면 `signinRedirect`로 Cognito 세션을 이용해 다시 받는다(비밀번호 재입력 없음, ADR-63).

### 4. 이미지·DB 마이그레이션

```dockerfile
# services/{svc}/Dockerfile — 모든 Java 서비스 공통
FROM eclipse-temurin:21-jre
RUN useradd -r -u 10001 -d /app -s /usr/sbin/nologin app
WORKDIR /app
COPY build/libs/app.jar app.jar
# Flyway Job이 복사해 감 (gateway는 이 줄 없음). Dockerfile은 줄 끝 주석이 없다 — 주석은 따로 한 줄
COPY src/main/resources/db/migration /app/db/migration
USER 10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.security.egd=file:/dev/./urandom"
ENTRYPOINT ["java", "-jar", "app.jar"]
```

| 항목 | 규칙 |
| --- | --- |
| 마이그레이션 실행 | ArgoCD 훅 Job `{svc}-flyway`(**Sync 단계 wave -1**: ConfigMap `{svc}-config` wave -2 → Job → Deployment/Rollout wave 0, 실패하면 sync 중단): initContainer(같은 앱 이미지)가 `/app/db/migration`을 emptyDir로 복사 → `flyway/flyway:11.7.2`(Spring Boot 3.5 Flyway 11.7과 같은 메이저) 컨테이너가 `{svc}_migrator`로 `migrate`. URL은 Job env `FLYWAY_URL`(호스트는 `cinema-infra-endpoints`의 `DB_HOST`, 3장, 앱 URL과 별도), 계정은 `{svc}-migrator-secrets`, RDS CA는 ConfigMap `rds-ca-bundle` → `/etc/rds-ca` |
| 파일 위치 | `services/{svc}/src/main/resources/db/migration/V{yyyyMMddHHmm}__{설명}.sql` |
| 호환 | 블루/그린·롤링 중 두 버전이 같은 DB를 쓴다 → 확장 → 전환 → 정리. 컬럼 삭제·이름 변경은 다음 배포 |
| 실패 | Job 실패 = sync 실패 → 새 버전이 뜨지 않는다. 고친 마이그레이션은 새 V 번호로(이미 적용된 파일 수정 금지) |
| 시드 | 롯데 배치·편성은 `lotte-seed`의 SQL을 `catalog_migrator`로 1회 적재(김종식·한채연, 런북). Flyway에 넣지 않음 |

### 5. 필수 메트릭 (대시보드·자동 롤백이 이 이름에 의존)

| 메트릭 | 타입 | 라벨 값 | 서비스 | 쓰는 곳 |
| --- | --- | --- | --- | --- |
| `http_server_requests_seconds_*` (Spring 기본) | histogram | `status`, `uri`, `method` | 전체 (⚠️AI ai는 같은 이름으로 직접 낸다) | 5xx 비율·p95, 블루/그린 분석 |
| `booking_seat_hold_total` | counter | `result`=`success`·`conflict`·`active_hold`·`error` | booking | 신호등·동시성 데모. `success`는 PENDING 커밋(발행 전), `conflict`=409 `SEAT_UNAVAILABLE`, `active_hold`=409 `ACTIVE_HOLD_EXISTS`, `error`=5xx(Catalog 503·Valkey·RDS). **4xx 거절(검증·시작된 회차·없는 회차)은 세지 않는다** → 신호등 분모 = 네 라벨 합 |
| `booking_sse_connections` | gauge | — | booking | SSE 재연결 확인 (블루/그린 전환·노드 재기동 때 0으로 떨어졌다 다시 오르는 게 정상) |
| `booking_payment_call_total` | counter | `result`=`approved`·`declined`·`timeout`·`error` | booking | **자동 롤백**(`declined`는 실패율에서 제외) |
| `booking_hold_expiry_lag_seconds` | histogram | — | booking | 만료 반영 2초 목표. 노드 0대 구간(ADR-34) 뒤 첫 스윕은 밀린 만큼(수 시간) 큰 값이 한 번 찍히는 게 정상 → 경보는 파드 기동 후 10분 제외(3.1.7) |
| `booking_notification_publish_total` | counter | `result`=`sent`·`retry`·`failed` | booking (릴레이) | 알림 경보 |
| `booking_outbox_pending` | gauge | — | booking | 릴레이 밀림 |
| `catalog_cache_requests_total` | counter | `result`=`hit`·`miss` | catalog | 캐시 적중률 |
| `catalog_seat_counts_fallback_total` | counter | — | catalog | 300ms 초과 대체 횟수 |
| `payment_request_total` | counter | `result`=`approved`·`declined`·`processing`·`canceled` | payment | 모의 결제 결과 분포(OPS-05). 자동 롤백은 booking 쪽 `booking_payment_call_total`을 쓴다 |
| `payment_cancel_total` | counter | `reason`=취소 요청 `reason`(계약 `payment.internal.openapi.yaml` enum 값만) | payment | 환불 사유별 건수(OPS-05) |
| ⚠️AI `ai_chat_total` | counter | `path`=`cache_exact`·`cache_semantic`·`preset`·`nl2sql`·`my_bookings`·`rejected`·`out_of_scope`, `result`=`ok`·`error` | ai | 경로별 처리 수 — **토큰 절감 데모의 분모** |
| ⚠️AI `ai_tokens_total` | counter | `kind`=`input`·`output`·`cache_read`·`cache_write`, `path` | ai | 토큰 사용량. 절감 그래프는 `input+output`(우리 캐시 효과), 프롬프트 캐싱은 `cache_read`로 **따로** 표시 |
| ⚠️AI `ai_cache_requests_total` | counter | `layer`=`exact`·`semantic`, `result`=`hit`·`miss` | ai | 캐시 적중률 |
| ⚠️AI `ai_llm_call_seconds` | histogram | `result`=`ok`·`timeout`·`overloaded`·`rate_limited`·`error` | ai | Claude API 지연·장애 |
| ⚠️AI `ai_sql_exec_seconds` · `ai_rejected_total` | histogram · counter | — · `reason`(SQL 검증 거부 사유) | ai | SQLite 실행 시간 · 검증기 동작 |
| ⚠️AI `ai_snapshot_age_seconds` · `ai_quota_rejected_total` | gauge · counter | `scope`=`today`·`other` · `window`=`minute`·`day` | ai | 스냅샷 신선도(오늘 1분·나머지 10분) · 한도 초과 |
| `cinema:http_requests:rate5m` | recording rule | `application` | 인프라 (gitops `observability/prometheusrule.yaml`, 3.1.7) | 신호등 계산용 서비스별 요청률(actuator 제외). **앱이 만드는 지표가 아니다** |
| `cinema:http_5xx_ratio:rate5m` · `cinema:http_p95_seconds:rate5m` | recording rule | `application` | 인프라 (3.1.7) | 최근 5분 5xx 비율 · p95(SSE `…/stream` 제외). 트래픽이 없으면 값이 없다 |
| `cinema:http_5xx_state` · `cinema:http_latency_state` · `cinema:service_state` | recording rule | `application` | 인프라 (3.1.7) | 신호등 상태 0 정상 · 1 주의 · 2 장애. 5xx 주의 ≥ 1%·장애 ≥ 5%, p95 주의 ≥ 300ms·장애 ≥ 1s, `service_state`는 둘 중 큰 값. 값이 없으면 대시보드가 회색 "트래픽 없음" |

블루/그린 분석(`SeatSAC-gitops/analysis/`):

| 대상 | 조건 (preview 파드, 4분 지연 뒤 1분 × 3회 ≈ 6분, 하나라도 실패하면 abort = 전환 없음) |
| --- | --- |
| 공통 | 분석 트래픽 Job(`rollout-smoke`, k6)이 `{svc}-preview:8080`으로 조회 요청을 preview Ready를 기다린 뒤(최대 150초) 6분(요청 제한 5초) 보내고 **응답 200이 95% 미만이면 Job 실패**(404처럼 5xx가 아닌 고장도 잡음) · `/actuator/**` 제외 요청 **≥ 20건/분** (세 측정 모두, 트래픽 0이면 통과가 아니라 실패. 분석은 preview 확장과 동시에(Ready 전) 시작하므로 preview 기동·Job 지연은 4분 지연으로 흡수) |
| booking | 5xx 비율 ≤ 1% **그리고** `booking_payment_call_total{result=~"timeout\|error"}` / (`approved`+`declined`+`timeout`+`error`) ≤ 5% (결제 호출 0건이면 통과 — 분석 트래픽에 결제 경로를 넣는 것은 BKG-10 이후) |
| gateway | 5xx 비율 ≤ 1% — ⚠️AI 쿼리에 `uri!~"/ai.*"` 추가(Claude API 지연·장애가 gateway 자동 롤백을 일으키지 않게, ADR-86·78 보완). rc1 전에 머지 |

- ⚠️AI ai는 블루/그린 분석 대상이 아니다. AI 지표는 데모 신호등과 **별도 대시보드**(`SeatSAC AI`)에만
- 실제 사용자 트래픽은 active로만 간다 → preview에는 분석 Job 요청만 들어온다. 앱이 할 일은 없다(조회 API가 데이터를 바꾸지 않으면 됨)
- booking 분석 회차(`SMOKE_SCREENING_ID`)는 **시드에 있는 실제 회차**(좌석 있는 상영관)여야 한다 — 없는 회차는 catalog 404에서 끝나 Valkey·RDS를 타지 않고, 200 검사로 **모든 booking 배포가 abort**된다. 고르는 법은 런북 6-D-0. 좌석 현황은 지난 회차도 200이라 고정값으로 둔다(결제 경로를 넣을 때는 선점이 `SCREENING_STARTED`로 막히므로 **미래 회차**가 필요). 시드를 다시 적재해 ID가 바뀌면 gitops도 고친다
- 분석 중(약 6분) preview 파드에서도 만료 스캔·아웃박스 릴레이가 돈다(2장) — 새 버전이 전환 전에 실제 예매 데이터를 건드린다는 뜻. 조건부 UPDATE·점유라 안전
- 5xx 비율 쿼리는 `uri!~"/actuator.*"`(probe 제외)
- preview가 `progressDeadlineSeconds: 600` 안에 Ready가 안 되면 abort(`progressDeadlineAbort`)

preview·active 구분은 파드 라벨 `rollouts-pod-template-hash`로 한다(앱은 아무것도 안 해도 됨).

### 6. 네트워크

| 규칙 | 내용 |
| --- | --- |
| NetworkPolicy | 앱 namespace **ingress 기본 거부**(`default-deny-ingress`) + 허용: ALB(**public 서브넷 CIDR 2개**, VPC CIDR 전체가 아님 — 같은 VPC의 파드가 gateway에 붙지 못하게) → gateway, gateway → catalog·booking·user, catalog → booking, booking → catalog·payment, user → booking·catalog, 분석 트래픽 Job(`app.kubernetes.io/name: rollout-smoke`) → booking·gateway(3.1.6), 부하 테스트 Job(`app.kubernetes.io/name: k6-loadtest`) → gateway(4.2.1, 실행 중에만 — 런북 6-H), `monitoring` → 전체(8080). egress는 막지 않음. L4(포트)까지라 허용된 호출자는 `/internal/**`도 부를 수 있다(외부 `/api/internal/**`는 gateway가 404). kubelet 프로브·port-forward·exec는 대상 아님. 확인 `infra/scripts/np-check.sh`(런북 6-E) |
| 전제 | EKS VPC CNI `enableNetworkPolicy=true` (3.1.2) |
| 보안그룹 | RDS 3306·Valkey 6379는 노드 보안그룹에서만. ALB는 CloudFront 관리형 접두사 목록만 |
| 외부 호출 | booking → SQS, user → Cognito `GetUser`, gateway → Cognito JWKS: NAT 경유 HTTPS. ⚠️AI ai → Claude API(`api.anthropic.com`), ai → **CloudFront `PUBLIC_API_BASE_URL`**(공개 API — 스냅샷·내 예매): NAT 경유 HTTPS. ai는 클러스터 안에서 gateway를 직접 부르지 않는다(gateway NetworkPolicy는 public 서브넷만 허용, 기존 정책은 수정하지 않음) |
| ⚠️AI ai NetworkPolicy | **`base/ai/networkpolicy.yaml`만 신규**: gateway → ai(8080), `monitoring` → ai(8080). 기존 `base/{svc}/networkpolicy.yaml`은 수정하지 않는다. `np-check.sh` `SERVICES`에 `ai` 추가(gateway → ai ALLOWED, catalog → ai BLOCKED 확인) |
| ⚠️AI ai Valkey 보안그룹 | 6379는 노드 보안그룹에서만(기존 Valkey와 같은 방식), TLS |
| SSE | 아래 4가지가 **한 세트**(하나라도 빠지면 연결이 끊기거나 이벤트가 몰려서 온다): ① Gateway 라우트 `/screenings/*/seats/stream` 응답 타임아웃 없음(한채연) ② ALB 유휴 120초 — gateway Ingress 주석 `load-balancer-attributes: idle_timeout.timeout_seconds=120`, 대상 그룹 `deregistration_delay.timeout_seconds=30`(내려가는 파드의 SSE를 35초 종료 유예 안에 정리, 3.1.5) ③ CloudFront 경로 `/api/screenings/*/seats/stream` 캐시 정책 CachingDisabled·**압축 끔**, 원본 응답 대기 30초 > heartbeat 15초(3.1.11) ④ booking 응답 헤더 `Content-Type: text/event-stream`, `Cache-Control: no-cache`(남궁일) |

### 7. 개발 ↔ 인프라 인계 지점

"넘겨주는 쪽"이 끝나야 "받는 쪽"이 시작할 수 있는 것만 적었다. 필요 시점이 밀리면 스크럼에서 바로 말한다.

| # | 넘겨주는 것 | 주는 쪽 | 받는 쪽 (작업) | 필요 시점 | 확인 방법 |
| --- | --- | --- | --- | --- | --- |
| 1 | 로컬 Compose(MySQL 8.4·Valkey 8.2·ElasticMQ) + 스키마·계정 SQL | 김종식 | 개발 전원 (서비스 골격) | 즉시 | `docker compose -f infra/compose/docker-compose.yml up` 후 각 `{svc}_app` 접속 |
| 2 | `libs/common-core`·`common-web` 최소판 (오류·traceId·`@CurrentUser`·내부 클라이언트) | 한채연·남궁일 | 전 서비스, 김종식(메트릭 공통 태그) | 서비스 골격 커밋 전 | `error.v1.json` 스키마 테스트 통과 |
| 3 | Flyway V1 (catalog·user 완료, booking·payment ERD 6차) | 각 서비스 담당 | 김종식 3.1.12 (DB 계정·GRANT·Secret) | 3.1.12 시작 전 | Compose에서 `migrate` 성공 |
| 4 | RDS·Valkey 엔드포인트, `{svc}-secrets`, `{svc}-migrator-secrets`, `time_zone=UTC` | 김종식 (3.1.3·3.1.12) | 각 서비스 staging 배포, 시드 적재 | 첫 staging 배포 전 | 관리자 `kubectl get secret -n cinema-staging` (developer는 Secret 목록도 Forbidden — 이름은 이 문서 3장으로 확인) |
| 5 | Cognito 사용자 풀·앱 클라이언트·`ADMIN` 그룹·Pre sign-up Lambda | 김종식 (Terraform) | 한채연 GW-02·3.3.3·로그인 화면 | 3.3.1 인증 필터 시작 전 | Hosted UI 로그인 → Access Token 발급 |
| 6 | EKS + Access Entry(4명) + namespace `cinema-staging`·`monitoring` + port-forward RBAC (`envs/staging-k8s`) | 김종식 (3.1.2·3.1.10) | 개발 전원 (`kubectl logs`·port-forward) | 첫 staging 배포 전 | 4명 `kubectl get pods -n cinema-staging` |
| 7 | 서비스 Dockerfile·`bootJar` 이름·actuator 설정 | 각 서비스 담당 (Dockerfile 5개는 3.1.4에서 4장 템플릿으로 맞춤) | 김종식 3.1.4 `build-push` | 서비스 첫 머지 | PR의 `pr-check` `image` 통과(빌드·비루트·Trivy) → 머지 후 `build-push` ECR push |
| 8 | gitops `base/{svc}` (Deployment/Rollout·Service·HPA·NetworkPolicy·PodMonitor·Flyway Job·gateway Ingress) + ArgoCD·서비스 Application(`envs/staging-k8s`) | 김종식 (3.1.5) | 각 서비스 (main 머지 = 배포) | 첫 staging 배포 | `kubectl -n argocd get applications` Synced·Healthy (팀원도 조회 가능) |
| 9 | 필수 메트릭 구현 (5장) | 남궁일·한채연 | 김종식 3.1.6 AnalysisTemplate, 3.1.7 대시보드 | 3.1.6 블루/그린 시연 전 | `/actuator/prometheus`에 이름·라벨 존재 |
| 10 | SQS 큐·DLQ, booking Pod Identity, 알림 Lambda | 김종식 (3.1.8·3.1.9) | 남궁일 3.2.6 (확정·아웃박스 릴레이) | 3.2.6 통합 테스트 전 | 확정 → Lambda 로그에 `eventId` |
| 11 | `seat-event`·`booking.confirmed` 발행 테스트 (examples로 검증) | 남궁일 | 김종식 (Lambda 소비), 화면 | 3.2.3·3.2.6 | 발행 테스트가 `contracts/events` 스키마 통과 |
| 12 | CloudFront·S3·`frontend-deploy` + `VITE_` 값 | 김종식 (3.1.11) | 한채연·남궁일 화면 | 화면 첫 배포 | CloudFront 도메인 새로고침·`/api` 동작 |
| 13 | SSE 경로 세트(6장 SSE ①~④): ALB 유휴 120초(3.1.5 Ingress)·CloudFront 캐시·압축 끔(3.1.11)·Gateway 라우트 타임아웃 없음·`/api` StripPrefix | 김종식(ALB·CloudFront) · 한채연(라우트) | 남궁일 BKG-05 | 3.2.3 SSE 확인 (3.1.5·3.1.11 작업 때 같이 확인) | CloudFront 경유 `curl -N`으로 15초 ping이 몰리지 않고 바로바로 옴 |
| 14 | NetworkPolicy 허용 목록 (6장) | 김종식 (3.1.13) | 전 서비스 내부 호출 | 내부 API 통합 후 | `infra/scripts/np-check.sh` 전부 ✅ — Cognito 뒤 `REQUIRE_GATEWAY=1`로 gateway → payment BLOCKED까지. **그리고** ALB → gateway 실경로(스크립트 밖): ALB 대상 그룹 gateway healthy + CloudFront `/api/movies` 200 (런북 6-E) |
| 15 | 부하 테스트 시나리오 대상 API·데이터 | 남궁일 | 김종식 OPS-10 (k6 Job) | 4.2.1 전 | k6 스크립트 dry-run |
| ⚠️AI 16 | ECR `cinema/ai`·ai Valkey·`ai-secrets`·`cinema-infra-endpoints` 키 2개·`ng-ai`·Application `cinema-ai` | 김종식 (3.1.18·3.1.19) | 김서현 staging 배포 | rc1(11/6) | `kubectl -n argocd get application cinema-ai`, `ng-ai` 1대일 때 ai readiness 200 |
| ⚠️AI 17 | CI(`changed-services.sh` ai 분기, `pr-check` `python`·ai 기동 확인, `build-push` ai) — **#79에 먼저 포함**(3.4.8 PR이 검사를 받도록) · gitops `base/ai`·`overlays/staging/ai` | 김종식 (3.1.20) | 김서현 (main 머지 = 배포) | CI는 3.4.8 리베이스 전, gitops는 3.1.18 apply 후 | PR `image`(ai)·`python` 통과 |
| ⚠️AI 18 | gateway `/api/ai/**` 라우트(플래그 off, 라우트 metadata `response-timeout: 28000`) + 분석 쿼리 `/ai` 제외 | 한채연(3.3.8)·김종식(3.1.21) | 김서현·한채연 화면 | **rc1(11/6)** | 플래그 off에서 `/api/ai/chat` 404, gateway 블루/그린 통과 |
| ⚠️AI 19 | `contracts/openapi/ai.openapi.yaml` + 예시 | 김서현·한채연 (2.2.8) | 한채연 채팅 화면(3.3.7) | 11/6 | contract-check 통과 |

### 8. 인프라 쪽 남은 정리

- [x] Compose·Terraform에서 `stats_db`·`stats_lambda`·`lambda-stats` 보안그룹·`lambdas/daily-stats` 삭제 (10/22, SG는 다음 apply에서 삭제)
- [x] EKS 1.36 · 노드그룹 ng-a·ng-c · 애드온(VPC CNI 네트워크 정책 켬·metrics-server) · Access Entry · LB Controller (3.1.2, 코드 10/22 · apply 후 런북 4장 확인)
- [x] 가동 정책: 평일 08:23 켜기·23:07 끄기(KST), 데모 주간 24시간, Budgets 월·일 경보 (3.1.14, 코드 10/22 · global apply 후 런북 8-1장 확인). 10/27: 실행을 EventBridge Scheduler로(GitHub schedule 지연·누락), 워크플로는 백업
- [x] ElastiCache Valkey 8.2 `cache.t4g.micro`(noeviction·Ex·TLS) + 메모리 80%·Evictions 경보, RDS MySQL 8.4 Single-AZ `db.t4g.small`(UTC·utf8mb4·TLS 강제) (3.1.3, 코드 10/22 · apply 후 런북 5장 확인)
- [ ] SQS 알림 큐·DLQ(`maxReceiveCount 3`)·booking Pod Identity(`cinema-staging-booking-pod`, ServiceAccount `booking`)·DLQ 경보·오래된 메시지(2일) 경보 (3.1.9, `envs/staging/sqs.tf`·gitops `base/booking/serviceaccount.yaml` — 코드 10/28 · **런북 6-B-2 확인 후 체크**). 릴레이 켜기(`APP_OUTBOX_RELAY_ENABLED=true`)는 알림 Lambda(3.1.8) 연결 뒤 6-B-3
- [ ] DB 스키마 4·계정 8(`REQUIRE SSL`)·`{svc}-secrets`·`{svc}-migrator-secrets`·RDS CA ConfigMap `rds-ca-bundle` (3.1.12, `envs/staging-k8s/db_bootstrap.tf`·`rds_ca.tf` — 클러스터 안 Job이 마스터 Secret을 Pod Identity로 읽어 인증서 검증 TLS로 생성. 코드 10/24 · **런북 8-B PASS·두 번째 plan·8-C 시드 적재 확인 후 체크**). Flyway 훅 Job 틀은 gitops `base/{svc}/flyway-job.yaml`(3.1.5에서 연결)
- [ ] ArgoCD(Helm)·root 앱·서비스 Application(Helm `argocd-apps`)·팀원 읽기 전용 화면, 플랫폼 차트 버전 고정(argo-rollouts·kube-prometheus-stack), gateway Ingress(ALB 유휴 120초·대상 정리 30초) (3.1.5, `envs/staging-k8s/argocd.tf`·`argocd_apps.tf`, gitops `apps/staging`·`base/gateway/ingress.yaml` — 코드 10/25 · **런북 6-C-2 확인 후 체크**). gateway·ALB·CloudFront `/api`는 Cognito 후
- [ ] 앱 JDBC도 서버 인증서 검증으로: `SPRING_DATASOURCE_URL`의 `sslMode=REQUIRED`(암호화만) → `VERIFY_IDENTITY` + `rds-ca-bundle` 트러스트스토어 — 김종식. 3.1.5에서 분리: Connector/J는 PEM CA를 바로 못 읽어 initContainer로 트러스트스토어(PKCS12)를 만들어야 하고, 실제 RDS로 기동 검증이 필요해 앱이 처음 뜬 뒤 별도 PR
- [ ] NetworkPolicy 허용 목록·기본 거부 (3.1.13, gitops `base/*/networkpolicy.yaml` — gateway ALB 대역을 public 서브넷으로 좁힘, 10/27 · **런북 6-E `np-check.sh` 확인 후 체크**, gateway는 Cognito 뒤 `REQUIRE_GATEWAY=1`로 다시)
- [ ] 신호등 대시보드 **SeatSAC 신호등**(Grafana)·기록 규칙 `cinema:*`·CloudWatch 데이터소스(Valkey 메모리·Evictions, SQS DLQ)·Grafana Pod Identity(`cinema-staging-grafana-pod`, ServiceAccount `kube-prometheus-stack-grafana`) (3.1.7, `envs/staging/grafana.tf`·gitops `observability/`·`platform/kube-prometheus-stack/values.yaml` — 코드 11/2 · **런북 6-F 확인 후 체크**). 앱 지표(`booking_seat_hold_total` 등 5장)가 구현되기 전에는 해당 패널이 회색·"수집 없음"으로 나오는 게 정상
- [ ] Compose `elasticmq-native:latest` → 버전 고정 — 김종식
- [ ] `infra/terraform/envs/staging`에 Cognito·SQS·Pod Identity 모듈 추가 시 이 문서 3·6장 이름 그대로 — 김종식
- [ ] Cognito 사용자 풀 `cinema-staging-users`·공개 앱 클라이언트·Hosted UI `cinema-staging`·`ADMIN` 그룹·Pre sign-up Lambda `cinema-staging-cognito-presignup` + `cognito_enabled = true`(gateway Application) (3.1.16, `envs/staging/cognito.tf` — 코드 10/28 · **런북 7-6-4 확인 후 체크**)
- [ ] 알림 Lambda `cinema-staging-notification`(python3.12·arm64·30초·VPC 밖)·실행 역할·로그 그룹(7일)·SQS 이벤트 소스 매핑(배치 10·`ReportBatchItemFailures`·동시 2) (3.1.8, `envs/staging/lambda.tf`·`lambdas/notification` — 코드 11/2 · 런북 6-B-5 확인 후 체크). 배포는 Terraform `archive_file`(Pre sign-up Lambda와 같은 방식, `lambda-deploy` 워크플로 없음)`
- [ ] ⚠️AI ai 인프라: ECR `cinema/ai` · ElastiCache `cinema-staging-ai-valkey`(Valkey 8.2 `cache.t4g.micro`, `noeviction`·`reserved-memory-percent 50`·TLS, 메모리 80% 경보) · `ai-secrets`(`ANTHROPIC_API_KEY` sensitive) · `cinema-infra-endpoints` 키 `AI_VALKEY_HOST`·`PUBLIC_API_BASE_URL` · `argocd_apps.tf`에 `ai` (3.1.18, rc1)
- [ ] ⚠️AI 노드그룹 `ng-ai`(t3.large 1대, min 0·max 1·desired 0, 라벨·taint `cinema/workload=ai`) · EventBridge Scheduler 켜기 대상에서 제외 · `cluster-scale.sh` 대상 지정(`up`은 `ng-ai` 제외, `up ng-ai`·`down ng-ai`, `down`은 전부) · Budgets 금액 재확정 (3.1.19, rc1)
- [ ] ⚠️AI gitops `base/ai`(Deployment·Service·NetworkPolicy·PodMonitor, HPA·Flyway Job 없음) · `overlays/staging/ai` · `np-check.sh` · 저장소 변수 `AI_BUILD_PUSH_ENABLED=true` (3.1.20). CI(`changed-services.sh`·`pr-check` `python`·`build-push` ai)는 #79에서 먼저
- [ ] ⚠️AI gateway 분석 쿼리 `uri!~"/ai.*"` (3.1.21, **rc1 전**)
- [ ] ⚠️AI AI 대시보드 `SeatSAC AI` + 런북 6-G(켜기·끄기·질의 금지 구간) (3.1.22)

### 9. ⚠️AI ai 서비스 (Python, ADR-82~87)

| 항목 | 앱(김서현)이 할 일 | 인프라(김종식)가 할 일 |
| --- | --- | --- |
| 런타임 | Python 3.12, FastAPI + uvicorn(워커 1), `pyproject.toml` + `uv.lock`. 테스트 pytest, 린트 ruff | `pr-check` `python` job(ruff·pytest, `services/ai/**` 바뀐 PR만), `changed-services.sh`의 `ALL_SERVICES`에 `ai` + **ai만 Gradle 대신 `docker build services/ai`** 분기 |
| 포트·헬스·메트릭 | 8080. `/actuator/health/liveness`(프로세스만), `/actuator/health/readiness`(스냅샷 첫 적재 완료 + ai Valkey PING), `/actuator/prometheus` | Java 서비스와 같은 프로브·PodMonitor 템플릿(`app.kubernetes.io/name: ai`, 포트 `http`, 15초). startupProbe 5초 × 30회(임베딩 모델·첫 스냅샷 적재) |
| 이미지 | `python:3.12-slim`, UID 10001, 임베딩 모델(`paraphrase-multilingual-MiniLM-L12-v2`, ONNX, fastembed)을 **빌드 때** `/app/models`에 넣는다(실행 중 다운로드 없음). 시드·스냅샷 파일은 이미지에 넣지 않는다 | `build-push`가 ECR `cinema/ai:{sha7}` → gitops `overlays/staging/ai/image-tag`. Trivy 동일 기준 |
| 비루트·파일 | 쓰기 경로 `/tmp`만. SQLite는 **메모리 DB(`:memory:`)** — 디스크에 남기지 않음 | `runAsNonRoot`, `readOnlyRootFilesystem` + `/tmp` emptyDir |
| 로그 | stdout JSON 한 줄(`timestamp`·`level`·`service=ai`·`traceId`·`userId`·`path`·`latencyMs`·`tokens`·`questionLength`·`questionHash`·`sqlTemplate`·`rowCount`). **질문 원문·SQL 리터럴 값·결과 행·Claude 응답 본문은 남기지 않는다** — 질문에 이메일·전화번호 같은 개인정보가 들어올 수 있다. `questionHash` = HMAC-SHA256(질문, 파드 기동 때 만든 임의 키) 앞 16자(같은 파드 안 반복 질문 추적용, 되돌릴 수 없음), `sqlTemplate` = 리터럴을 `?`로 바꾼 SQL(sqlglot). 디버깅에 원문이 필요하면 로컬에서 재현한다 | `kubectl logs` |
| 오류·traceId | `error.v1.json` 형식 그대로, `X-Trace-Id` 받으면 그대로·없으면 32자리 생성. 코드는 `services/ai` 안(conventions 6장 ⚠️AI 행). `libs/` 변경 없음 | — |
| 배포 | 별도 ArgoCD Application `cinema-ai` | `argocd_apps.tf` `argocd_services`에 `ai` 추가. **다른 Application 파일은 수정하지 않는다** |

#### 9-1. 설정 (ConfigMap `ai-config` · `cinema-infra-endpoints` · Secret `ai-secrets`)

| 키 | 값 | 비고 |
| --- | --- | --- |
| `APP_ENV` | `staging` | |
| `AI_VALKEY_HOST` · `PUBLIC_API_BASE_URL` | `cinema-infra-endpoints`(Terraform) | 이름 그대로 읽음 |
| `APP_AI_VALKEY_PORT` / `APP_AI_VALKEY_TLS` | `6379` / `true` | |
| `APP_AI_MODEL` | `claude-sonnet-5-5` | ADR-83. 교체는 이 값만 |
| `APP_AI_REQUEST_DEADLINE_SECONDS` | `25` | 요청 하나의 **전체 기한**(Claude 호출·SQL 실행·재요청 전부). 넘으면 남은 호출을 하지 않고 503 `AI_LLM_UNAVAILABLE`(`reason` `TIMEOUT`). 아래 시간 예산 |
| `APP_AI_LLM_TIMEOUT_SECONDS` | `15` | Claude 호출 1회 상한. 실제로는 `min(15, 전체 기한까지 남은 시간)`. 내부 REST 3초의 예외 |
| `APP_AI_LLM_MAX_CALLS_PER_QUERY` | `3` | 도구 선택 1 + 검증 실패 재요청 1 + SQL 오류 재요청 1. 3회를 다 써도 **전체 기한 25초 안**에서만(15초 × 3을 보장하지 않는다) |
| `APP_AI_PROMPT_CACHE_ENABLED` | `true` | 스키마 설명(system)에 프롬프트 캐싱. 지표 `kind=cache_read`로 따로 |
| `APP_AI_SQL_TIMEOUT_SECONDS` / `APP_AI_MAX_ROWS` | `5` / `500` | progress handler로 5초 중단(전체 기한이 더 짧으면 그쪽), `fetchmany(500)`. 읽기 전용 방식은 9-2 |
| `APP_AI_SNAPSHOT_TODAY_INTERVAL` / `APP_AI_SNAPSHOT_OTHER_INTERVAL` / `APP_AI_SNAPSHOT_DAYS` | `60s` / `600s` / `7` | 오늘 회차 1분, 나머지 10분, 오늘~+6일 |
| `APP_AI_CACHE_TTL` / `APP_AI_SESSION_TTL` | `1h` / `30m` | |
| `APP_AI_SEMANTIC_CACHE_ENABLED` / `APP_AI_SEMANTIC_THRESHOLD` | `true` / `0.92` | rc2까지 의미 캐시가 안 되면 `false`로 정확 일치 캐시만(B1) |
| `APP_AI_LIMIT_PER_MINUTE` / `APP_AI_LIMIT_PER_DAY` | `10` / `100` | 사용자당. 동시 1건 |
| `ANTHROPIC_API_KEY` (Secret `ai-secrets`) | Terraform sensitive 변수 → K8s Secret | staging 키. 로컬은 개발 전용 키를 `.env`에 |

**시간 예산** (`/api/ai/chat` 한 번, 바깥이 항상 안쪽보다 길다)

| 층 | 상한 | 넘으면 |
| --- | --- | --- |
| CloudFront 원본 응답 대기 | 30초 (기존 설정, 3.1.11) | 504 |
| gateway `/api/ai/**` 라우트 `response-timeout` | **28초** (`28000`) | 504 `UPSTREAM_TIMEOUT` |
| ai 요청 전체 기한 `APP_AI_REQUEST_DEADLINE_SECONDS` | **25초** | 503 `AI_LLM_UNAVAILABLE` `TIMEOUT` — 정상 경로에서는 여기서 먼저 끝나 화면이 ai의 오류 형식을 받는다 |
| └ Claude 호출 1회 | min(15초, 남은 시간) × 최대 3회 | 다음 호출 없이 위 503 |
| └ SQLite 실행 1회 | min(5초, 남은 시간) | `REJECTED` 또는 SQL 오류 재요청 |
| `ai:inflight:{userId}` 잠금 | 40초 | 25초 기한보다 길어 처리 중 풀리지 않는다 |

#### 9-2. 실행 규칙

- **ai는 RDS·기존 Valkey에 연결하지 않는다.** 데이터는 공개 API(`PUBLIC_API_BASE_URL`)로 채운 메모리 SQLite, 캐시·세션·한도는 ai Valkey
- SQLite 읽기 전용: `:memory:`를 URI `mode=ro`로 열면 비어 있는 읽기 전용 DB가 되어 적재할 수 없다. 그래서 **적재 → 잠금 → 교체** 순서로 한다. ① 갱신마다 **새** `:memory:` 연결을 쓰기 가능으로 열어 적재 ② 끝나면 그 연결에 `PRAGMA query_only=ON` + `set_authorizer`(SELECT·READ·FUNCTION만 허용, 나머지 DENY — `PRAGMA query_only=OFF`·`ATTACH`·쓰기 SQL도 여기서 막힌다. `query_only`만으로는 SQL로 다시 끌 수 있다) ③ 질의용 참조를 새 연결로 바꾸고 이전 연결은 진행 중 질의가 끝나면 닫는다(이중 버퍼). 질의 쪽은 쓰기 가능한 연결을 받을 수 없다. 적재 실패면 이전 스냅샷을 그대로 쓰고 `ai_snapshot_age_seconds`가 늘어난다
- 스냅샷 적재 호출(오늘 1분마다 4회, 10분마다 약 50회)은 CloudFront → gateway → catalog로 들어간다. **부하 테스트(4.2.1)·블루/그린 전환 테스트(4.2.2) 중에는 `ng-ai` desired 0 · 플래그 off, 좌석 데모 중에는 `ng-ai` desired 0 · AI 질의 금지(플래그는 rc2에서 켜 둔 채 — 데모 주간은 머지 금지라 끌 수 없다)** → 테스트 환경 = 데모 환경 (런북 6-G)
- `ng-ai`는 EventBridge Scheduler 평일 켜기 대상에서 **뺀다**(평소 0대). 켜기·끄기는 `cluster-scale.sh up ng-ai`·`down ng-ai`(cinema-admin). `cluster-scale.sh up`·`up 2`와 `cluster-schedule`의 `up`은 `ng-ai`를 켜지 않고 `down`은 `ng-ai`까지 끈다(켜 둔 채 잊어도 백업 끄기·수동 `down`이 끈다)
- 플래그 켜기는 rc2(11/13) M6 테스트가 끝난 뒤 gitops PR 1회(gateway 블루/그린 1회). 데모 주간에는 플래그·ai 코드 모두 머지 금지


---

# 4. 데이터 모델·DB 계약 (`docs/data-model.md`)

> 기준 2026-10-21. 원본은 노션 WBS 2.2.2 「ERD·서비스별 스키마」(ERD 6차, 완료)와 각 서비스 Flyway V1. 이 문서는 개발·인프라가 함께 보는 **요약**이다. 테이블 정의가 바뀌면 Flyway 파일이 먼저, 이 문서가 다음이다.
> 10/22 결정 반영(ADR-59·60·61). 스키마는 4개(catalog·booking·user·payment)뿐이다.
> ⚠️AI 11/5 AI 재도입(ADR-82~87)에도 **RDS는 바뀌지 않는다**(리포트 뷰·`ai_readonly` 없음, ADR-60 유지). ai 데이터는 6장.

---

### 1. 공통 규칙

| 규칙 | 내용 |
| --- | --- |
| 인스턴스 | RDS MySQL 8.4 1대(ADR-3·33), 서비스별 스키마·계정, 다른 스키마 권한 없음 |
| 계정 | `{svc}_app`(DML: SELECT·INSERT·UPDATE·DELETE), `{svc}_migrator`(DDL: 해당 스키마 ALL). 비밀번호는 Terraform 랜덤 → K8s Secret |
| 참조 | 서비스 간은 ID 컬럼만, FK 없음. 같은 스키마 안에서만 FK |
| 이름 | snake_case, 테이블 단수형 |
| ID | `BIGINT`(Java `Long`). 예외: `user_id` = Cognito `sub` `CHAR(36)`, `hold_id`·`payment_id`·`message_key` = 앱 발급 UUID `CHAR(36)` |
| 시각 | `DATETIME(3)` **UTC**. 응답은 `+09:00` 변환. `business_date`만 KST 영업일 `DATE` |
| 상태 | `VARCHAR` + 앱 enum. 철자 `CANCELED`, 컬럼 `canceled_at` |
| 문자셋 | `utf8mb4` / `utf8mb4_0900_ai_ci` |

### 2. 스키마별 테이블

#### catalog_db (한채연) — 시드 DDL 기준

| 테이블 | 키·제약 | 비고 |
| --- | --- | --- |
| `theater` | PK `theater_id`, UK (`source_provider`, `source_theater_id`) | 132곳, `has_layout=1` 4곳 |
| `auditorium` | PK `auditorium_id`, UK (`theater_id`, `source_auditorium_id`), FK theater | 39개 관 |
| `seat` | PK `seat_id`, UK (`auditorium_id`, `layout_version`, `seat_label`), FK auditorium | 6,767석, `seat_id = auditorium_id × 10000 + 순번`, `active` |
| `auditorium_layout` | PK (`auditorium_id`, `layout_version`) | 스크린·입구·통로 JSON, `fetched_at` |
| `layout_import` | PK `import_id` | 수집·검증 이력 |
| `movie` | PK `movie_id`, UK (`source_provider`, `source_movie_code`) | 22편. TMDB 보충 컬럼(NULL 허용): `tmdb_id`, `genres`, `overview`, `release_date`, `runtime_minutes`, `poster_path`, `director`, `cast_names`, `meta_fetched_at` |
| `screening` | PK `screening_id`, UK (`auditorium_id`, `business_date`, `play_sequence`), INDEX (`movie_id`, `business_date`) | `starts_at`·`ends_at` UTC, `layout_version` |

#### user_db (한채연)

| 테이블 | 키·제약 | 비고 |
| --- | --- | --- |
| `user` | PK `user_id` CHAR(36), UK `email` | password·role 없음(Cognito). email·name은 첫 `GET /users/me` 때 `GetUser` |
| `user_preference` | PK `user_id` | `preferred_time_slot`, `preferred_genres` JSON |
| `user_preferred_theater` | PK (`user_id`, `rank_no`), UK (`user_id`, `theater_id`) | `rank_no` 1~3 |

#### booking_db (남궁일)

| 테이블 | 키·제약 | 비고 |
| --- | --- | --- |
| `booking` | PK `booking_id`, UK `hold_id`, UK `current_payment_id`, INDEX (`status`, `expires_at`) | `user_id` CHAR(36), `screening_id`, `status`, `seat_count` 1~4, `amount`, `seat_labels` JSON, `expires_at`, `created_at`·`confirmed_at`·`canceled_at`. hold_id·seat_labels·amount는 선점 후 불변 |
| `booking_seat` | PK (`booking_id`, `seat_id`), **UK (`screening_id`, `seat_id`)** | 이중 확정 최종 방어선. PAYMENT_FAILED·EXPIRED·CANCELED 때 행 삭제 |
| `outbox_message` | PK `outbox_id`, UK `message_key`(= 알림 `eventId`), UK (`event_type`, `aggregate_id`), INDEX (`status`, `next_attempt_at`) | `payload` JSON, `trace_id` CHAR(32), `status` PENDING·SENDING·SENT·FAILED, `attempt_count`, `last_error`, `sent_at`. 확정과 같은 트랜잭션에서 INSERT, 릴레이가 1·2·4…최대 60초 간격 재전송, 10회 초과 FAILED + 경보 |

#### payment_db (남궁일)

| 테이블 | 키·제약 | 비고 |
| --- | --- | --- |
| `payment` | PK `payment_id` CHAR(36)(booking 발급), **UK `booking_id`**(`uk_payment_booking_id`, 예매당 1회, ADR-24), INDEX (`status`, `created_at`)(기동 정리) | `amount`, `method`, `card_last4`(카드 번호 저장 금지), `status` PROCESSING·APPROVED·FAILED·CANCELED, `failure_code`, `cancel_reason`, `pg_provider`(`MOCK`만, ADR-60), `approved_at`·`canceled_at`. user_id 없음. PROCESSING으로 먼저 INSERT·커밋 → 판정은 트랜잭션 밖 → `UPDATE … WHERE status='PROCESSING'`. 30초 넘게 남은 PROCESSING은 payment 기동 시 `FAILED`(`PROCESSING_TIMEOUT`)로 정리(ADR-60) |

### 3. 서비스 간 ID 참조 (FK 없음)

| 참조하는 쪽 | 컬럼 | 원본 |
| --- | --- | --- |
| `booking.booking` | `user_id`, `screening_id` | `user.user`, `catalog.screening` |
| `booking.booking_seat` | `screening_id`, `seat_id` | `catalog.screening`, `catalog.seat` |
| `payment.payment` | `booking_id` | `booking.booking` |
| `user.user_preferred_theater` | `theater_id` | `catalog.theater` |

### 4. 상태 전이 (booking)

| 현재 | 다음 | 계기 | 좌석 | 이벤트 |
| --- | --- | --- | --- | --- |
| — | PENDING | BKG-02 선점 | HELD | HELD |
| PENDING | CONFIRMED | BKG-10 승인 + 선점 유효 (+ 아웃박스 행) | RESERVED | CONFIRMED |
| PENDING | PAYMENT_FAILED | BKG-10 거절 | AVAILABLE | RELEASED |
| PENDING | EXPIRED | 만료(keyspace · `hold-expiry` 1초 · RDS 10초 스캔) | AVAILABLE | EXPIRED |
| PENDING | CANCELED | BKG-03 해제 | AVAILABLE | RELEASED |
| CONFIRMED | CANCELED | BKG-07 취소 + PAY-03 | AVAILABLE | RELEASED |

- 모든 전이는 **현재 상태를 조건으로 건** 조건부 UPDATE로 한 번만 처리한다: `UPDATE booking SET status=:next … WHERE booking_id=:id AND status=:expected`(위 표의 "현재" 열). 예: 만료·해제·결제 결과는 `:expected='PENDING'`, 확정 예매 취소(BKG-07)는 `:expected='CONFIRMED'`. 갱신 행이 0이면 이미 다른 경로가 처리한 것(재시도·중복 이벤트)으로 보고 아무것도 하지 않는다. 처리권은 `ZREM`이 아니라 UPDATE 결과 1행으로 정한다
- 이벤트 발행은 커밋 후, `ZREM hold-expiry`는 마지막

### 5. 인프라가 만드는 것 (3.1.3 · 3.1.12)

| 대상 | 내용 |
| --- | --- |
| RDS 파라미터 그룹 | `time_zone=UTC`, `character_set_server=utf8mb4`, `collation_server=utf8mb4_0900_ai_ci` |
| 스키마 | `catalog_db`, `booking_db`, `user_db`, `payment_db` |
| 계정·권한 | 1장 계정 규칙 + 모든 계정 `REQUIRE SSL`, `@'%'`. 마스터 계정(`cinema_master`)은 앱·Flyway Job에서 쓰지 않음. 마스터 비밀번호는 RDS가 Secrets Manager에서 관리(3.1.3) → 계정·스키마 생성은 3.1.12 클러스터 안 Job `cinema-ops/db-bootstrap-*`(Pod Identity로 마스터 Secret 읽기, RDS는 VPC 밖에서 닿지 않음). SQL은 멱등(`IF NOT EXISTS` + `ALTER USER`) → 비밀번호 교체도 같은 Job |
| Secret | `{svc}-secrets`(`SPRING_DATASOURCE_PASSWORD`), `{svc}-migrator-secrets`(`FLYWAY_USER`, `FLYWAY_PASSWORD`) — namespace `cinema-staging`, 비밀번호는 Terraform `random_password`(영숫자 32자, `envs/staging-k8s` state에만) |
| ElastiCache Valkey 8.2 | `notify-keyspace-events Ex`(만료 감지, ADR-44), 전송 중 암호화, `maxmemory-policy noeviction`(선점 키가 밀려나면 안 됨), 노드 **`cache.t4g.micro`** + 메모리 80%·Evictions 경보(ADR-59) |
| 시드 적재 | 3.1.12 후 `lotte-seed/load/run.sh`(관리자): 일회성 Job이 catalog Flyway를 먼저 적용하고 시드를 `catalog_migrator`로 1회 넣은 뒤 검증(극장 132·상영관 39·좌석 6,767·회차 8,360·영화 22). 재실행 안전, 끝나면 Job·ConfigMap 삭제. 시드 파일은 레포·gitops에 두지 않는다 |
| 가용성·백업 | RDS **Single-AZ**(ADR-61) `db.t4g.small`, 자동 백업 7일(03:00 KST), 최종 스냅샷 없음(재생성 리허설용) · 자동 백업은 삭제 후에도 보존 |
| ⚠️AI ElastiCache ai Valkey 8.2 | `cinema-staging-ai-valkey` `cache.t4g.micro` 1노드, `maxmemory-policy noeviction`(한도 키가 밀려나지 않게, 모든 키 TTL), `reserved-memory-percent 50`(벡터 검색 조건), 전송 중 암호화, 메모리 80% 경보. **기존 Valkey와 별도** — 좌석 선점 키와 메모리를 나누지 않는다(ADR-85). 밤에도 유지(ElastiCache는 정지 불가, 월 약 $12) |
| 만들지 않는 것 | 리포트 뷰(`v_*_report`)·`stats_db`·⚠️AI `ai_readonly` 계정 — catalog Flyway V2 `v_screening_report`는 머지하지 않음(ADR-60). ai는 RDS에 접근하지 않는다(ADR-84) |

### 6. ⚠️AI ai 서비스 데이터 (RDS 밖, ADR-84·85)

#### 6-1. SQLite 스냅샷 (ai 파드 메모리, `:memory:`)

공개 API(`PUBLIC_API_BASE_URL` = CloudFront `/api`)의 응답만으로 채운다. 갱신마다 새 메모리 DB에 쓰고, 다 채운 뒤 읽기 전용으로 잠가(`PRAGMA query_only=ON` + authorizer) 질의용과 바꾼다 — `mode=ro`는 `:memory:`에 쓸 수 없다(`docs/service-runtime-contract.md` 9-2). **사이트에서 누구나 볼 수 있는 값만** 들어가고, 시드 원본·롯데 스냅샷·포스터는 들어오지 않는다. 디스크·이미지·레포·PVC에 남지 않고, 파드가 꺼지면 사라진다.

| 테이블 | 컬럼 | 출처(공개 API) | 갱신 |
| --- | --- | --- | --- |
| `movie` | `movie_id` PK, `title`, `title_en`, `view_grade`, `slot_minutes`, `release_date`, `director`, `genres`(쉼표), `synopsis` | `GET /movies?status=NOW_SHOWING`을 `page=0`부터 `totalPages - 1`까지 모두 조회한 뒤 목록의 각 영화에 `GET /movies/{id}` 호출 | 10분 |
| `theater` | `theater_id` PK, `name`, `region`, `has_layout` | `GET /theaters?hasLayout=true` | 10분 |
| `screening` | `screening_id` PK, `movie_id`, `theater_id`, `auditorium_id`, `auditorium_name`, `screen_type`, `business_date`(`YYYY-MM-DD`), `starts_at`·`ends_at`(KST `YYYY-MM-DD HH:MM`), `start_hour`(0~23), `film_format`, `total_seats`, `remaining_seats`, `remaining_seats_source`, `fetched_at` | `GET /screenings?date&theaterId` (극장 4곳 × 오늘~+6일) | **오늘 1분**, 나머지 10분 |
| `snapshot_meta` | `table_name`, `scope`, `refreshed_at` | — | 적재마다 |

- 넣지 않는 것: `posterUrl`(롯데 포스터 표시 금지), 배치도·좌석 ID, 예매·결제·사용자 데이터
- 시각은 모두 KST 문자열(질문이 KST 기준이라 LLM이 변환하지 않게). 공개 API는 시작한 회차를 제외하지만 적재 후 시간이 흐르므로, SQLite에서 회차를 반환하는 모든 질의는 현재 KST와 `starts_at`을 비교해 `starts_at > 현재 KST`인 회차만 반환한다
- 컬럼 한국어 설명·enum 뜻·few-shot은 `services/ai/src/seatsac_ai/resources/schema.yaml`(AI-10). 이 파일이 NL2SQL 정확도의 대부분을 정한다
- 답에는 "○시 ○분 기준"(`fetched_at`)을 붙인다

#### 6-2. 개인 데이터

"내 예매"는 SQLite에 넣지 않고 요청마다 `GET {PUBLIC_API_BASE_URL}/users/me/bookings`를 **사용자의 토큰 그대로** 호출해 답한다. 캐시하지 않고, Claude API에는 예매 행을 보내지 않는다(템플릿 문장으로 답).

#### 6-3. ai Valkey 키

`docs/conventions.md` 4장 ⚠️AI 표.


---

# 5. 결정 요약 (ADR) (`docs/decisions.md`)

> 기준 2026-10-22 (회의록2 결정 반영). 원본은 노션 「결정 기록 (ADR)」(번호 = DB 번호). 근거·대안은 노션에서 본다. 이 문서는 **지금 유효한 결론**만 한 줄씩 둔다.
> 이후 결정이 앞 결정을 덮은 경우 "→ 대체"로 표시했다. ⏳는 아직 열린 항목이다.
> ⚠️AI 11/5 AI 재도입: ADR-82~87(노션 등록 번호는 등록 후 확인). 대체·보완 관계는 각 행 비고.

---

### 1. 범위·일정·사람

| ADR | 결론 | 비고 |
| --- | --- | --- |
| 1 · 50 | ~~서비스 5개, AI 제외(10/22)~~ → **82로 대체**: 서비스 6개(ai 추가) | 26 → 83으로 대체 |
| ⚠️AI 82 | AI 재도입: **Python(FastAPI) `services/ai`, 사용자 챗봇만**(로그인 필수, `/api/ai/**`). 질문 처리 정확 일치 캐시 → 의미 캐시 → 프리셋 → NL2SQL, "내 예매"는 공개 API. 담당 **김서현**. 데모는 1차 11/16(무중단·좌석) + **2차 11/17(AI)** | 1·50 대체, 52·53 보완. 관리자 NL2SQL·추천(AI-08) 제외 |
| ⚠️AI 83 | LLM은 **Claude API(Anthropic 직접) `claude-sonnet-5-5`**, 도구 호출로 프리셋/SQL 선택, 프롬프트 캐싱. 키: staging `ai-secrets`(Terraform sensitive), 로컬 개발 전용 키 `.env`(커밋 금지). Anthropic 월 지출 한도 $20, 사용자당 동시 1·분당 10·하루 100 | 26 대체. Bedrock 안 씀(Pod Identity 불필요) |
| 52 | (⚠️AI 82 보완: 11/17 AI 2차 데모 추가) 데모는 **무중단 배포 · 실시간 좌석** 2개로 확정. 실시간 좌석 데모에 동시성 테스트(같은 좌석 100명 → 성공 1명) 포함 | 선점 전략 비교는 여유 시 |
| 45 | 선점 전략 비교(db-lock/redis): 지금은 3.2.2에 `SeatHoldStrategy` 인터페이스만, db-lock은 잠글 행·만료 처리 **설계 초안만**(남궁일). 구현·측정은 최후 순위(4.2 때 여유 시) | 새 노드 불필요 |
| 53 | 김서현은 일정에서 제외. 필요하면 개발·인프라/DevOps의 WBS 하나씩 맡긴다 → ⚠️AI **82로 보완: AI는 김서현 고정 담당**(11/2) | 4.1.2 김서현·한채연, OPS-07 코드 김종식 |
| 25 | Payment 백엔드는 남궁일, 결제 화면은 한채연 | |
| 2 | 롯데시네마 좌석 배치·하루 편성만 로컬에서 1회 수집 → 비공개 `lotte-seed` 시드 | 실시간 연동·예매 호출 없음 |
| 15 · 16 | ⏳ 스크린 위치(GetSeats `ScreenSeatInfo` 사용 가정) · 수집 방식(로컬 1회 가정) — 한채연 확인 예정 | |

### 2. 데이터·실시간

| ADR | 결론 | 비고 |
| --- | --- | --- |
| 3 · 33 · 61 | RDS **MySQL 8.4** 1대(**Single-AZ**, `db.t4g.small`, TLS 강제, 마스터 비밀번호는 RDS 관리 Secrets Manager) + 서비스별 스키마·계정 | Multi-AZ·AZ 장애 실험은 시간 남을 때 |
| 33 · 59 | ElastiCache **Valkey 8.2 `cache.t4g.micro`**, `noeviction`, `notify-keyspace-events Ex`, 메모리 80%·Evictions 경보 | 부하 테스트에서 70% 넘으면 t4g.small |
| 57 | DB 마이그레이션은 ArgoCD **Flyway 훅 Job**(`{svc}_migrator`, Sync 단계 wave -1 — ConfigMap 다음·앱 전, 실패 시 sync 중단), 앱은 Flyway 끔. 스키마·계정·Secret은 Terraform(`envs/staging-k8s`)이 클러스터 안 Job으로 생성(3.1.12) | 처음 "PreSync"였으나 첫 배포 때 ConfigMap보다 먼저 돌아 Sync wave -1로 변경(10/23) |
| 60 | 리포트 뷰 만들지 않음(catalog V2 머지 안 함, ⚠️AI 재도입 후에도 유지 — 84) · payment `PROCESSING` 30초 초과는 기동 시 `FAILED(PROCESSING_TIMEOUT)` · `pg_provider`는 `MOCK`만 | |
| 4 | 좌석 이벤트: Valkey Pub/Sub `seat-events:{회차}` → SSE, 놓치면 BKG-01 스냅샷 | 유실 허용 |
| 5 | 선점: Valkey `SET NX EX {선점 TTL}`(`APP_SEAT_HOLD_TTL_SECONDS`, 기본 300초, 30~600)(Lua로 여러 좌석 원자적) + 그 시점 RDS PENDING 예매 | |
| 11 | 한 번에 최대 4석 | |
| 19 | 멱등: `Idempotency-Key` + Valkey `idem:{userId}:{키}` 24시간 + 유일 제약(`booking_seat`, `current_payment_id`) | |
| 22 | 내부 브로커 없음: Pub/Sub + keyspace 알림 + sorted set | 만료 반영 2초 초과 시 재검토 |
| 44 | 만료: keyspace(Ex) + `hold-expiry` 1초 스캔 + RDS 10초 스캔, 조건부 UPDATE로 한 번만 | 노션 본문 "ADR-21" = 이 항목 |
| ⚠️AI 84 | AI 데이터: **ai 파드 메모리 SQLite 스냅샷**, CloudFront 공개 API로만 적재(오늘 1분·나머지 10분). RDS·기존 Valkey·리포트 뷰·`ai_readonly` 없음. 실행은 읽기 전용·5초·500행, SQL 검증 sqlglot(SQLite). 포스터·시드 원본 미포함 | 기존 MSA DB를 건드리지 않음. 60 유지 |
| ⚠️AI 85 | AI 캐시: 새 ElastiCache **`cinema-staging-ai-valkey`** `t4g.micro`(벡터 검색, `noeviction` + 모든 키 TTL — 한도 키 보호, 예약 메모리 50%). 정확 일치 + **의미 캐시**(로컬 임베딩 `paraphrase-multilingual-MiniLM-L12-v2`, 슬롯 치환 뒤 임베딩, 유사도 0.92) + 세션(사용자별 키) + 사용 한도. 개인 질문은 캐시 안 함 | 59는 기존 Valkey에만 해당(그대로) |

### 3. 서비스 간 통신·계약·결제·알림

| ADR | 결론 | 비고 |
| --- | --- | --- |
| 23 · 32 | REST 동기(RestClient + Resilience4j), 연결 1초·응답 3초(catalog→booking 300ms), 서킷 50%·10초, OpenAPI 계약 + CI | p95 초과 시 gRPC |
| 38 · 54 | 계약 기준은 API 명세서 v1.1 + 2.2.4 v0.3 정리: 비페이징 목록 `{items}`, 오류 `details` 항상 배열, Cognito 조회 실패 503 `IDENTITY_UNAVAILABLE`, 내부 회차 API `seatIds` 삭제, `GET /internal/theaters` 신설 | `docs/contracts.md` |
| 55 | 계약 강화(구현 전 일괄): `booking.confirmed` 필드 3개 필수, traceId `^[0-9a-f]{32}$`, 오류 traceId null 불가. 첫 계약 PR만 `contract-break-ok`, 라벨은 구현 시작 엔드포인트·M3 이후 금지 | |
| 62 | Gateway 요청 수 제한 미도입(`rl:` 키·429 없음) | |
| 24 | 결제는 예매당 1회, 실패하면 PAYMENT_FAILED + 좌석 해제 | 20 폐기 |
| 13 | 모의 결제: 거절도 200 + status, 카드 끝 0000 거절·1111 잔액 부족·9999 5초 지연 후 승인, `PROCESSING` | 롤백 지표에서 거절 제외 |
| 30 · 51 | 예매 완료 알림: booking만 SQS 발행(아웃박스 + 릴레이) → Lambda(로그), DLQ 3회. **OPS-08 일별 집계는 제외** | |

### 4. 인프라·배포

| ADR | 결론 | 비고 |
| --- | --- | --- |
| 7 · 34 | (⚠️AI 86 보완: `ng-ai` 추가) EKS On-Demand **t3.large 2대**(ng-a 1 · ng-c 1). 환경은 **staging 하나**. 평일 밤·주말 노드 0대(월~금 08:23 켜기·23:07 끄기 KST, 3.1.14 — 실행은 EventBridge Scheduler, GitHub Actions는 백업·수동), 데모 주간 11/16~20 24시간. 비용 경보 Budgets 월·일 | 노션 ADR-7 제목 "3대"는 이전 값 |
| 8 · 35 · 58 | Kustomize + ArgoCD. booking·gateway만 블루/그린(5xx 1%·결제 timeout/error 5% 롤백, scaleDown 60초), 나머지 롤링. 프로브·자원·HPA·메트릭·Secret·NetworkPolicy는 `docs/service-runtime-contract.md` | 동시 블루/그린 금지 |
| 9 · 31 | CloudFront + S3(OAC), 기본 도메인 HTTPS, `/api/*` → ALB, CORS 없음 | 31의 httpOnly 쿠키는 37·63으로 대체 |
| 78 | (⚠️AI 86 보완: gateway 5xx 쿼리에 `uri!~"/ai.*"`) 블루/그린 분석 트래픽: 분석 중 AnalysisTemplate Job(k6, 조회만)이 `{svc}-preview`로 직접 요청, `/actuator` 제외 요청 ≥ 20건/분을 같이 판정(트래픽 0 = 실패). 실패하면 abort(전환 없음) → gitops 태그 revert PR. 실패 버전 시연은 gitops 설정 PR로 의존 서비스 주소를 깨뜨리는 방식 | ⏳ 남궁일 확인(결제 경로 트래픽·시연 방식) |
| 10 | 관측은 Prometheus·Grafana, 비밀 값은 Terraform이 만드는 K8s Secret | External Secrets·Loki는 확장 |
| ⚠️AI 86 | AI 격리: 전용 노드그룹 **`ng-ai`**(t3.large 1대, 라벨·taint `cinema/workload=ai`, 평소 desired 0, 스케줄 대상 제외) · 별도 Application **`cinema-ai`**(`argocd_apps.tf`, 다른 Application 수정 없음) · gateway 플래그 `APP_AI_ENABLED`(꺼지면 `/api/ai/**` 404) · 분석 쿼리 `/ai` 제외 · `base/ai/networkpolicy.yaml`만 신규(gateway·monitoring → ai) · ai는 클러스터 안 gateway를 부르지 않고 CloudFront 공개 주소 사용 | 7·34·58·69·78 보완 |
| 36 · 39 | AWS 아키텍처 = 위 결정들의 합. 노션 ADR-36(10/18 v3)은 이전 스냅샷 | |
| 46 | GitHub Actions → AWS OIDC. 역할 `cinema-gha-ecr-push`·`cinema-gha-tf-plan`·`cinema-gha-frontend-deploy`·`cinema-gha-cluster-schedule`(3.1.14). CI는 apply 안 함 | `infra/terraform/global` |
| 47 | 팀 접근: IAM 역할 + MFA(Identity Center 미사용). `cinema-admin` = 김종식 + **백업 남궁일**, `cinema-developer` = 전원. 프로필 `seatsac-user`·`cinema-dev`·`cinema-admin` | 회의록3 |
| 74 | ⚠️ `SeatSAC-gitops` **Public**(GitHub 무료 플랜은 Private 레포에 ruleset을 적용하지 않음). 계정 ID·엔드포인트는 gitops에 두지 않는다: 엔드포인트 = Terraform ConfigMap `cinema-infra-endpoints` + `${키}`, ECR 레지스트리 = Terraform이 만드는 서비스 ArgoCD Application의 `kustomize.images`, gitops에는 태그만. ArgoCD는 HTTPS로 읽음(자격 증명 없음) | 10/25. 대안: Private + ruleset 없이 운영(직접 push 위험), 유료 플랜 |

### 5. 인증·화면·코드·프로세스

| ADR | 결론 | 비고 |
| --- | --- | --- |
| 37 | Cognito 사용자 풀(Hosted UI, 코드 + PKCE), Gateway가 Access Token을 JWKS로 검증 → `X-User-Id`(= sub)·`X-User-Role` | |
| 63 | 화면 토큰 저장은 **메모리**(`InMemoryWebStorage`), localStorage 금지. 새로고침은 Cognito 세션으로 재발급 | |
| 56 | 공통 코드: 같은 레포 공유 모듈(`libs/common-*` + `build-logic`), 패키지 `com.seatsac.{service}`·`com.seatsac.common.{core,web,test}`. `libs/` 변경 승인 2명 | 최종 결정 21번 "복사" 대체 |
| 48 · 64 | 레포 `SeatSAC`·`SeatSAC-gitops`, 브랜치 `{type}/{기능ID 또는 WBS ID}-{설명}`, 데모 주간 11/16~20 머지 금지, 태그 rc1 11/6·rc2 11/13·v1.0.0 11/19, Lambda Python, 이슈는 노션 WBS만 (⚠️AI `services/ai` 다시 생김 — 82) | `docs/repo-workflow.md` |
| ⚠️AI 87 | AI 일정·머지: **rc1(11/6)** gateway 라우트(플래그 off)·분석 쿼리 제외 머지, ai 인프라·골격은 플래그 off로 staging 배포 가능 · **M6(11/9~13)** `services/ai/**`·`contracts/openapi/ai*`·gitops `base/ai`·`overlays/staging/ai`만 머지 허용(gateway·booking·`libs/`·Terraform 금지), **테스트 준비 예외(11/10 일정 변경)** `tests/k6/**`·`docs/**`·gitops `base/*/networkpolicy.yaml` 테스트 Job 허용 규칙 추가는 M6에도 머지(조건: PR 본문에 「M6 예외(테스트 준비)」와 이유, 스크럼 공유, 테스트 실행 중이 아닐 때), 테스트는 `ng-ai` 0·플래그 off, ai 검증은 로컬 Compose + 개발 키 · **rc2(11/13)** ai 코드 동결(이후 `fix/`만), M6 테스트 뒤 플래그 켜기 · **데모 주간** ai `fix/`도 금지(64 그대로) | 64 보완 |
| 49 | 공통 문서 6종 채택, 합본 `Claude.md`를 레포 루트에 (노션과 다르면 레포 우선, 수정은 PR) | 65는 49로 통합(폐기) |


---

# 6. 레포·브랜치·PR·CI (`docs/repo-workflow.md`)

> 기준 2026-10-22 (회의록2 결정 반영, ADR-49~64). 03b 레포·브랜치·PR 개정 확정본 + 08 공통 코드(B안, ADR-56) + 브랜치 WBS ID(ADR-48) + 레포·일정(ADR-64) + 2.2.7 계약 CI.
> 전제: staging 하나가 곧 데모 환경이라 **main 머지 = 배포**다(3.1.4부터 `build-push`가 main 머지마다 자동으로 돈다).
> ⚠️AI 11/5 AI 재도입(ADR-82·86·87): `services/ai`(Python) 추가, AI 전용 머지 기간 규칙은 2장 「⚠️AI 일정·머지」.

---

### 1. 레포

| 레포 | 공개 | 내용 |
| --- | --- | --- |
| `sesac-mzc-final-2/SeatSAC` (앱, 03b의 `cinema`) | 개발 중 Private → 발표 후 Public | 서비스, frontend, 계약, Terraform, Lambda, 테스트, 문서 |
| `SeatSAC-gitops` | ⚠️ **Public** (무료 플랜 ruleset, ADR-74) — 계정 ID·엔드포인트·키 금지(`gitops-validate`가 막음) | Kustomize 매니페스트, 플랫폼 ArgoCD Application. CI 앱 봇이 이미지 태그만 커밋. 서비스 Application·엔드포인트는 Terraform `envs/staging-k8s` |
| `lotte-seed` | **항상 Private** | 수집 스크립트, 원본 스냅샷, 좌석 배치·편성 시드 SQL |

```
SeatSAC/
├── settings.gradle.kts · gradle/libs.versions.toml · build-logic/   빌드 설정 공통 (08)
├── libs/common-core · common-web · common-test                     런타임 규약만 (오류·traceId·헤더·로그·내부 클라이언트)
├── services/{gateway,catalog,booking,user,payment}/                각 Dockerfile, db/migration
├── services/ai/                                                    ⚠️AI Python(FastAPI) — pyproject.toml·uv.lock·Dockerfile·src/seatsac_ai·tests, .env(커밋 금지)
├── frontend/                                                       React(Vite) → S3
├── contracts/{openapi,events,schemas,tools}/                       계약 + 계약 CI
├── lambdas/{notification,cognito-presignup}/                       Python
├── infra/terraform/{bootstrap,global,envs/staging,envs/staging-k8s}/ · infra/compose/
├── tests/k6/
├── docs/   conventions.md · contracts.md · service-runtime-contract.md · data-model.md · decisions.md · repo-workflow.md · runbooks/
└── .github/ workflows/ · scripts/(서비스 고르기·Trivy, pr-check·build-push 공용) · CODEOWNERS · pull_request_template.md

SeatSAC-gitops/
├── apps/staging/          App of Apps 대상(project·플랫폼 2·cinema-common). root 앱·서비스 Application 5개는 SeatSAC Terraform(staging-k8s argocd_apps.tf)
├── platform/              Argo Rollouts, kube-prometheus-stack values (LB Controller는 Terraform envs/staging-k8s)
├── base/{service}/        Deployment 또는 Rollout, Service(+preview), HPA, NetworkPolicy, PodMonitor, Flyway 훅 Job(Sync wave -1)
│                          ⚠️AI base/ai: Deployment(nodeSelector·toleration ng-ai)·Service·NetworkPolicy(gateway·monitoring → ai)·PodMonitor (HPA·Flyway 없음)
├── base/common/           앱 namespace 인바운드 기본 거부 + analysis
├── overlays/staging/{service}/  ConfigMap {service}-config(엔드포인트는 ${키}) + image-tag/(newTag만)
├── components/rollout/    kustomize가 Rollout 안 ConfigMap 해시 이름을 바꾸게 하는 설정
├── analysis/              AnalysisTemplate (booking·gateway)
└── .github/               CODEOWNERS · PR 템플릿 · gitops-validate
```

- `lambdas/daily-stats/`는 10/22 삭제(ADR-51). ⚠️AI `services/ai/`는 10/22 삭제(ADR-50) 후 11/5 **Python으로 다시 만든다**(ADR-82). 서비스 Application은 Terraform `argocd_apps.tf`에 `ai` → `cinema-ai`(gitops `apps/`에는 두지 않음)
- Secret 매니페스트는 gitops에 없다(Terraform이 생성, Deployment는 이름으로만 참조)

### 2. 브랜치·커밋

| 항목 | 규칙 |
| --- | --- |
| 기본 브랜치 | `main` 보호(ruleset), 직접 push 금지. **main 머지 = staging 자동 배포**(`services/**`가 바뀐 머지 → `build-push`, 3.1.4) |
| 브랜치 이름 | `{type}/{기능ID}-{설명}`. 기능 ID가 없으면 **WBS ID** (ADR-48) |
| type | `feat` · `fix` · `refactor` · `test` · `docs` · `infra` · `ci` · `chore` |
| 예 | `feat/BKG-02-seat-hold`, `fix/CAT-05-cache-key`, `infra/3.1.3-rds-valkey`, `ci/2.2.7-contract-ci`, `docs/ADR-47` |
| 수명 | 최대 3일. 길어지면 기능 플래그로 나눠 머지 |
| 머지 | Squash merge |
| 커밋 메시지 | Conventional Commits + 기능 ID 또는 WBS ID: `feat(booking): BKG-02 좌석 선점 Lua 스크립트`, `infra(terraform): 3.1.3 RDS·Valkey` |
| 릴리스 태그 | `rc1` 11/6(M5), `rc2` 11/13(M6), `v1.0.0` 11/19(리허설 전) |
| 동결 | 11/13 이후 `fix/`만, 승인 2명 + 김종식 확인. **11/16~20 데모 주간은 승인된 fix 외 머지 금지** |
| ⚠️AI 일정·머지 (ADR-87) | **rc1(11/6)**: gateway `/api/ai/**` 라우트(플래그 off)·gateway 분석 쿼리 `/ai` 제외 머지 완료. ai 인프라·골격은 플래그 off로 staging 배포 가능 · **M6(11/9~13)**: AI 기능은 계속 머지할 수 있다 — 허용 경로는 `services/ai/**`·`contracts/openapi/ai*`·gitops `base/ai/**`·`overlays/staging/ai/**`만. gateway·booking·`libs/`·Terraform 변경은 이 기간 금지(부하·전환 테스트 환경 보호). **테스트 준비 예외(11/10 일정 변경)**: 4.2.1 부하 테스트 준비가 M6로 밀려 테스트 도구·문서는 M6에도 머지할 수 있다 — `tests/k6/**`, `docs/**`(런북·문서, `CLAUDE.md` 재생성 포함), gitops `base/*/networkpolicy.yaml`의 **테스트 Job 라벨 허용 규칙 추가만**(예: `k6-loadtest` → gateway. Rollout·Deployment 템플릿은 그대로라 블루/그린·재시작이 없다). 조건: PR 본문에 「M6 예외(테스트 준비)」와 이유, 스크럼 공유, 4.2.1·4.2.2 테스트가 돌지 않는 시간에 머지. 서비스 코드·Terraform 금지는 그대로. 테스트(4.2.1·4.2.2)는 `ng-ai` desired 0·플래그 off. ai 검증은 로컬 Compose + **개발 전용 Claude API 키**(`.env`), staging 확인은 테스트가 돌지 않는 시간에 `kubectl port-forward svc/ai`(스크럼에서 시간 공유) · **rc2(11/13)**: ai 코드도 동결, 이후 ai는 `fix/`만. M6 테스트가 끝난 뒤 gateway `APP_AI_ENABLED=true` gitops PR 1회 · **데모 주간(11/16~20)**: ai `fix/`도 머지 금지(ADR-64 그대로). 11/17 AI 데모는 `ng-ai` 수동 기동(머지 아님) |

### 3. PR

| 항목 | 규칙 |
| --- | --- |
| 승인 | 1명 (11/13 이후 2명). `libs/`·`build-logic/` 변경은 2명(김종식 + 영향받는 서비스 담당) |
| 필수 검사 (ruleset) | `java`·`gitleaks`·`image`·`frontend`(pr-check), `lint (OpenAPI · JSON Schema · 예시)`·`하위 호환 (base 대비)`(contract-check). 여섯 검사 모두 **모든 PR에서 실행**된다(contract-check는 `paths` 필터 없이 `변경 감지` job이 계약 변경이 없으면 두 job을 건너뜀 = 성공. `image`도 같은 방식: 이미지 대상이 없으면 건너뜀 = 성공. `frontend`도 같은 방식: `frontend 변경 감지` job이 `frontend/**` 변경이 없으면 건너뜀 = 성공). `infra-plan`은 `paths` 필터가 있어 필수로 걸지 않는다 — `infra/terraform/**` PR은 리뷰어가 plan 코멘트를 확인. `java`는 Gradle wrapper·서비스 골격이 들어온 뒤 필수로 추가. ⚠️AI `python`(pr-check)도 같은 「변경 없으면 건너뜀」 방식으로 필수 검사에 추가 → 일곱 검사(워크플로가 main에 들어간 뒤 ruleset에 `python`을 추가한다 — 그 전에 추가하면 job이 없어 모든 PR이 대기) |
| 크기 | 400줄 이하 권장(생성 코드·lock 제외) |
| 리뷰 응답 | 24시간 이내(주말 제외) |
| 머지 후 | 작성자가 ArgoCD Synced·Healthy(또는 frontend 배포 완료)까지 확인 — `kubectl -n argocd get applications` 또는 `kubectl -n argocd port-forward svc/argocd-server 8080:80`(읽기 전용 화면) |
| 템플릿 | 변경 요약 · WBS ID·기능 ID · 테스트 방법 · 스크린샷(화면) · **계약 변경 여부** · **DB 마이그레이션 단계(확장·전환·정리)** · **배포 영향(env·Secret·gitops·CloudFront 캐시)** |
| 계약 깨는 변경 | 구현 전 설계 변경만 라벨 `contract-break-ok` + CODEOWNERS 승인, PR 본문에 이유 |

**리뷰 체크리스트**

- DB: 컬럼 삭제·이름 변경은 다음 배포로(두 버전이 같은 DB를 쓴다)
- 계약: 필드 추가만. 삭제·타입 변경은 새 엔드포인트·`.v2`
- 이벤트: 소비자는 모르는 필드를 무시한다
- booking 만료·릴레이 로직: preview 파드도 돌므로 이전 버전과 호환
- 설정 이름이 `docs/service-runtime-contract.md` 3장과 같은지

**CODEOWNERS** (`.github/CODEOWNERS`, GitHub 아이디는 실제 값으로 확인)

| 경로 | 담당 |
| --- | --- |
| `services/gateway·catalog·user/` | 한채연 |
| `services/booking·payment/` | 남궁일 |
| ⚠️AI `services/ai/`, `contracts/openapi/ai*` | 김서현 (계약은 + 한채연) |
| `frontend/` | 한채연 · 남궁일 |
| `contracts/` | 한채연 · 남궁일 |
| `contracts/tools/`, `contracts/redocly.yaml`, `build-logic/`, `gradle/`, `lambdas/`, `infra/`, `.github/`, `tests/k6/` | 김종식 |
| `libs/` | 김종식 · 한채연 · 남궁일 |

### 4. CI/CD 워크플로

| 워크플로 | 트리거 | 하는 일 |
| --- | --- | --- |
| `pr-check` | PR | ⚠️AI `python`: `services/ai/**`가 바뀐 PR만 Python 3.12 → `uv sync --locked`(uv 버전은 `services/ai/Dockerfile`의 `ARG UV_VERSION`) → `ruff check` → `pytest`(Claude API는 목, 키·AWS 권한 없음, 바뀌지 않으면 건너뜀 = 성공 — 필수 검사로 추가). `image`의 서비스 고르기(`changed-services.sh`)는 Java 5개 + `ai`이고, **`services/{svc}/build.gradle.kts`가 없는 ai는 Gradle `bootJar` 없이 `docker build services/ai`**. ai 이미지는 PR에서 **실제로 띄워 본다**(`--read-only --tmpfs /tmp`, liveness 200, readiness HTTP 상태 200 또는 503만 확인(스냅샷 적재·ai Valkey 연결 성공은 보장하지 않음), `http_server_requests_seconds{application="ai"}`, 헤더 없는 `/ai/presets` → 401 + `error.v1.json` 형식). `services/{svc}/Dockerfile`이 아직 없는 서비스는 고르지 않는다. `java`: 빌드·테스트(Testcontainers MySQL 8.4·Valkey 8.2·ElasticMQ) · `gitleaks` · `image`: 바뀐 서비스만 bootJar → `docker build` → 비루트 확인 → Trivy(push 안 함, AWS 권한 없음. `build-logic/`·`gradle/`·이미지 스크립트·워크플로가 바뀌면 5개 전부) · `lambda`: `lambdas/notification` 단위 테스트(Python 3.12 `unittest`, 의존성·AWS 권한 없음, 필수 검사 아님) · `frontend`: `frontend/**`가 바뀐 PR만 node 24 → `npm ci` → `npm run check`(tsc -b · eslint · steiger FSD · vitest) → `npm run build`(`dist/index.html` 확인). frontend-deploy와 같은 node·npm 설정, AWS 권한 없음. 실패하면 같은 PR 브랜치에 고쳐서 push → 자동 재실행. 2단계: springdoc 생성본 vs 계약 diff |
| `contract-check` | PR (항상, 변경 없으면 건너뜀) | Redocly lint, JSON Schema·예시 검증, oasdiff·스키마 하위 호환 |
| `build-push` | main 머지(`services/**`, 바뀐 서비스만) + 수동(`services`=all 또는 목록, main에서만) | bootJar → x86 이미지 → Trivy(CRITICAL·수정 가능이면 실패) → ECR `cinema/{svc}:{sha7}`(OIDC `cinema-gha-ecr-push`, 같은 태그 있으면 건너뜀) → `SeatSAC-gitops` `overlays/staging/{svc}/image-tag`의 **`newTag`만** yq로 GitHub App `seatsac-build-push`(gitops에만 설치, Contents 쓰기) 토큰으로 main에 커밋(조직 정책상 Deploy key 불가). 레지스트리(계정 ID)는 gitops에 쓰지 않는다(Public, ADR-74) — Terraform ArgoCD Application이 붙인다. booking·gateway가 같이 바뀌면 booking만(블루/그린 동시 금지). ⚠️AI ai는 `docker build services/ai`(Gradle 없음) → ECR `cinema/ai` → `overlays/staging/ai/image-tag`. 저장소 변수 **`AI_BUILD_PUSH_ENABLED`가 `true`가 아니면 ai는 건너뛴다**(경고) — ECR `cinema/ai`(3.1.18)·gitops `overlays/staging/ai`(3.1.20)가 생긴 뒤 김종식이 켠다(런북 6-G-1). 그 전에는 ai 머지가 빌드·배포 없이 끝난다 |
| `frontend-deploy` | main 머지(`frontend/**`) + 수동 | Vite 빌드 → S3(`/assets/*` 1년 immutable, `index.html` no-cache) → 무효화 |
| `lambda-deploy` | **없음(현재)** | Lambda 배포는 Terraform `archive_file`(3.1.16 Pre sign-up, 3.1.8 알림)이 코드를 묶어 올린다. 코드가 바뀌면 `infra-plan`에 함수 update가 보이고 apply는 김종식이 로컬에서 `cinema-admin`으로. 별칭(alias)은 쓰지 않는다. 필요해지면 이 워크플로를 추가(CI에 배포 권한이 필요) |
| `infra-plan` | PR(`infra/terraform/**`·`lambdas/**`, 필수 검사 아님) | `terraform plan`(OIDC `cinema-gha-tf-plan`) → PR 코멘트. **apply는 김종식이 로컬에서 `cinema-admin`으로** |
| `cluster-schedule` | **백업** 월~금 08:53 켜기 · 23:37·00:37 끄기(KST) + 수동(up·down·status — `up`은 `ng-ai`를 켜지 않고 `down`은 `ng-ai`까지 끈다) | 정시 켜기·끄기(08:23·23:07)는 **AWS EventBridge Scheduler**(`envs/staging/cluster_schedule.tf`)가 한다 — GitHub schedule은 몇 시간씩 늦거나 빠져서(10/23·10/26) 이 워크플로는 30분 뒤 백업·수동용. 백업은 늦게 도착하면(끄기 월~금 22~23시·화~토 0~5시, 켜기 월~금 7~10시 밖) 건너뛰고, 데모 주간 11/16~20·EventBridge 끄기 DISABLED(`cluster_keep_on`)면 끄기 건너뜀. 노드그룹 desired 1 ↔ 0 (OIDC `cinema-gha-cluster-schedule`), 스크립트 `infra/scripts/cluster-scale.sh` |
| `gitops-validate` (gitops 레포) | PR | `kustomize build` + kubeconform, Secret 매니페스트·계정 ID·`*.amazonaws.com`·`newName` 금지, gitleaks |

- OIDC 역할 세션 이름 `gha-${{ github.actor }}-${{ github.run_id }}`(CloudTrail에 실행자)
- 이미지는 두 번 빌드된다: PR의 `image`(검사용, 버림)와 main의 `build-push`(진짜, 태그 = main 커밋 SHA). 서비스 고르기·Trivy는 `.github/scripts/`를 같이 쓴다
- 공통 모듈(`libs/`·`build-logic/`·`gradle/`)만 바뀐 머지는 배포하지 않는다. 급하면 `build-push` 수동 실행으로 한 서비스씩
- 롤백 = `SeatSAC-gitops`에서 해당 태그 커밋을 revert하는 PR. ECR 이미지는 저장소당 최근 30개가 남는다

### 5. 지켜야 할 것

| 규칙 | 이유 |
| --- | --- |
| 비밀 값(키·비밀번호·tfstate 내용·tfvars의 민감 값)을 Git·문서·PR에 쓰지 않는다. ⚠️AI Claude API 키 포함 — 로컬은 개발 전용 키를 `services/ai/.env`에만 | 발표 후 Public 전환. gitleaks가 막는다 |
| 롯데 원본·fixture·시드는 `lotte-seed`에만 | 준수 조건(공개 금지) |
| 롯데 실제 예매 API 호출 금지, 롯데 포스터 이미지 표시 금지 | 준수 조건 |
| 사람은 ECR·frontend S3에 직접 올리지 않는다 | ECR 저장소 정책(CI 역할만 push) + 태그 불변 |
| AWS 콘솔에서 리소스를 만들지 않는다. IAM·OIDC는 `infra/terraform/global`, 나머지는 `envs/staging` | 10/21 OIDC 공급자 중복 사례 |
| `terraform destroy`로 bootstrap·global을 지우지 않는다 | tfstate·팀 접근 상실 |
| 클러스터 직접 수정 금지(selfHeal이 되돌림). 긴급 시 해당 앱 auto-sync 끄고 작업 → Git 반영 → 스크럼 공유 | GitOps |
| 팀원 평소 작업은 `cinema-dev` 프로필(MFA). `cinema-admin`은 김종식 + 백업 관리자 남궁일 | ADR-47 |
| 작업·진척은 노션 WBS만. GitHub Issues 안 씀 | 03b |
| 발표 후 Public 전환 전: gitleaks 전체 이력 스캔, 시드·원본·`.env`·tfvars 없음 확인. ⚠️AI 개발용·staging Claude API 키 폐기(Anthropic 콘솔) | — |

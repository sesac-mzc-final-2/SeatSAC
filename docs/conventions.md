# 네이밍·공통 규약 (conventions)

> 기준 2026-10-22 (회의록2 결정 반영, ADR-49~64). 03a 명명 규칙 개정을 확정한 최종본. 03a 이후 결정(Cognito ADR-37, API 명세서 v1.1, 2.2.4 v0.3, ERD 6차)을 반영해 03a와 다른 곳은 ⚠️로 표시했다.
> 범위: 서비스 5개(gateway·catalog·booking·user·payment) + Lambda 2개(notification·cognito-presignup) (ADR-50·51).
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
| frontend | React (Vite) | 없음 (S3 + CloudFront) | 5173 (`/api` → 8080 프록시) | — |
| MySQL 8.4 / Valkey 8.2 / ElasticMQ (로컬) | — | 3306 / 6379 / 9324 | 같음 | RDS / ElastiCache(TLS) / SQS |

| 경로 | 규칙 |
| --- | --- |
| 외부 API | `https://{배포ID}.cloudfront.net/api/{경로}` → ALB → gateway가 `/api` 제거(StripPrefix=1) |
| 내부 API | `/internal/**` — Gateway 라우팅 금지, NetworkPolicy로 호출자 제한 |
| 헬스 | `/actuator/health/liveness`, `/actuator/health/readiness` (ALB는 gateway readiness만) |
| 메트릭 | `/actuator/prometheus` |
| Gateway 404 | `/api/internal/**`, `/api/actuator/**`, `/api/payments/**`, `/api/auth/**` |

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

## 4. Valkey 키·채널

| 키 | 소유 | TTL | 값·비고 |
| --- | --- | --- | --- |
| `catalog:movies:*`, `catalog:movie:{id}`, `catalog:theaters:*`, `catalog:screenings:{date}:{theaterId}:{movieId\|all}`, `catalog:layout:{auditoriumId}:{version}` | catalog | 10분 | 변경 시 삭제. 잔여 좌석은 캐시 금지 |
| `seat:{screeningId}:{seatId}` | booking | 300초 | holdId(UUID) |
| `user-hold:{screeningId}:{userId}` | booking | 300초 | 회차당 진행 중 선점 1건 |
| `seat-seq:{screeningId}` | booking | 없음 | 회차별 이벤트 일련번호 |
| `hold-expiry` (sorted set) | booking | 없음 | 멤버 `{screeningId}:{holdId}`, 점수 = 만료 시각 |
| ⚠️ `idem:{userId}:{Idempotency-Key}` | booking | 24시간 | 요청 해시 + 처리 상태 + 첫 응답 (03a `idem:{키}` 대체) |

| Pub/Sub 채널 | 발행 | 구독 | 메시지 |
| --- | --- | --- | --- |
| `seat-events:{screeningId}` | booking (RDS 커밋 후) | booking 각 파드 → SSE | `contracts/events/seat-event.v1.json` ⚠️ `screeningId` 포함 |

- ⚠️ 03a의 `refresh:{사용자ID}:{토큰ID}`는 **삭제**(Cognito가 Refresh Token 관리). 요청 수 제한 `rl:`은 만들지 않는다(ADR-62)
- 새 키는 `{서비스}:{대상}:{id}` 형식으로 만들고 이 표에 소유를 추가한다

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
| 오류 응답 | ⚠️ `{ code, message, traceId, details[] }` — `details`는 **항상 배열**(없으면 `[]`), 원소 `{ field, value?, reason? }`. 화면 분기는 `code`, `message`는 개발용 (`contracts/schemas/error.v1.json`) |
| 헤더 | `Authorization: Bearer {Cognito Access Token}`, `X-User-Id`(= `sub`), `X-User-Role`(`USER`·`ADMIN`), `X-Trace-Id`, `Idempotency-Key`, `CloudFront-Viewer-Address`. 외부에서 들어온 `X-User-*`·`X-Trace-Id`는 Gateway가 지우거나 덮어쓴다 |
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
| 403 | `FORBIDDEN` | gateway·서비스 |
| 404 | `NOT_FOUND` | 전체 |
| 409 | `SEAT_UNAVAILABLE`, `ACTIVE_HOLD_EXISTS`, `BOOKING_STATE_CONFLICT`, `PAYMENT_ALREADY_REQUESTED`, `IDEMPOTENCY_KEY_CONFLICT`, `IDEMPOTENCY_IN_PROGRESS` | booking |
| 409 | `SCHEDULE_CONFLICT`, `SEAT_SOLD` | catalog |
| 409 | `PAYMENT_CONFLICT`, `PAYMENT_NOT_APPROVED` | payment (내부) |
| 500 | `INTERNAL_ERROR` | 전체 |
| 503 | `SERVICE_UNAVAILABLE` | gateway·서비스 |
| 503 | `PAYMENT_RESULT_UNKNOWN` | booking |
| 503 | `BOOKING_UNAVAILABLE`, ⚠️ `IDENTITY_UNAVAILABLE` | user |

새 코드는 이 표와 해당 OpenAPI 응답 `description`에 함께 추가한다.

## 7. 메트릭

- 이름 `{service}_{대상}_{단위}`, 라벨은 `result`·`route` 등 값 종류가 적은 것만(사용자 ID·좌석 ID 금지)
- 필수 메트릭과 라벨 값은 `docs/service-runtime-contract.md` 5장

## 8. 환경변수·설정·Secret

| 구분 | 규칙 | 예 |
| --- | --- | --- |
| Spring 표준 | 바인딩 이름 그대로 | `SPRING_DATASOURCE_URL`, `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_SSL_ENABLED` |
| 앱 고유 | `APP_` | `APP_SEAT_HOLD_TTL_SECONDS=300`, `APP_SEAT_HOLD_STRATEGY=redis` |
| 다른 서비스 주소·타임아웃 | `APP_SERVICES_{NAME}_URL`, `_CONNECT_TIMEOUT`, `_READ_TIMEOUT` | `APP_SERVICES_PAYMENT_URL=http://payment:8080` |
| 인증 | ⚠️ `APP_COGNITO_ISSUER_URI`, `APP_COGNITO_CLIENT_ID` (gateway 앱 설정 `app.cognito.*`, Spring 표준 `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`는 쓰지 않음. 03a `APP_JWT_SECRET`·`jwt-secret` 삭제) | — |
| AWS | SDK 표준 `AWS_REGION=ap-northeast-2`. 자격 증명은 Pod Identity, 액세스 키 금지 | — |
| frontend | `VITE_` (빌드에 포함 = 공개 값만) | `VITE_API_BASE_URL=/api`, `VITE_COGNITO_AUTHORITY` |
| K8s Secret (Terraform 생성) | `{service}-secrets`(앱), `{service}-migrator-secrets`(Flyway Job만) | `booking-secrets` |
| ConfigMap | `{service}-config`(gitops, 엔드포인트는 `${키}`만), ⚠️ `cinema-infra-endpoints`(Terraform, 엔드포인트 — 키는 `docs/service-runtime-contract.md` 3장) | `${DB_HOST}` |
| 프로필 | `local`, `staging` | `SPRING_PROFILES_ACTIVE=staging` |

> `VITE_`에 비밀 값 금지. Secret 값은 Git·문서에 두지 않는다(Terraform 랜덤 생성 → K8s Secret, tfstate는 암호화 S3).

## 9. K8s·AWS 리소스 이름

| 대상 | 규칙 | 예 |
| --- | --- | --- |
| 공통 접두사 / 환경 | `cinema` / `staging` (환경은 staging 하나, ADR-34) | — |
| 앱 namespace | `cinema-staging` | — |
| 플랫폼 namespace | 도구 기본값 | `argocd`, `argo-rollouts`, `monitoring`, `kube-system` |
| 관리자 namespace | `cinema-ops` (팀원 조회 범위 밖, Terraform이 만드는 운영 Job·그 입력 Secret) | `db-bootstrap-*` Job (3.1.12) |
| AWS 리소스 | `cinema-staging-{대상}` | `cinema-staging-eks`, `cinema-staging-valkey`, `cinema-staging-users`(Cognito) |
| 계정 단위 리소스 | `cinema-{대상}` | `cinema-admin`, `cinema-developer`, `cinema-gha-ecr-push`, `cinema-tfstate-{계정ID}` |
| S3 버킷 | `cinema-staging-frontend-{계정ID}`, `cinema-tfstate-{계정ID}` | — |
| ECR | `cinema/{service}` (frontend 없음) | `cinema/booking` |
| 이미지 태그 | git SHA 7자리, 불변 | `cinema/booking:3f9a1c2` |
| Pod Identity 역할 | `cinema-staging-{대상}-pod` | `cinema-staging-booking-pod` |
| Lambda | `cinema-staging-{기능}` | `cinema-staging-notification`, `cinema-staging-cognito-presignup` |
| K8s 리소스 | `{service}`, Rollout 미리보기 Service `{service}-preview`, Flyway Job `{service}-flyway` | `booking-preview` |
| 공통 라벨 | `app.kubernetes.io/name={service}`, `app.kubernetes.io/part-of=cinema` | — |
| Service 포트 이름 | `http` (8080) | — |
| AWS 태그 | `Project=cinema`, `Env=staging`, `Owner={이름}`, `ManagedBy=terraform` | — |

## 10. Git

`docs/repo-workflow.md` (레포·브랜치·커밋·PR·CI).


<노션 반영 필요> 03a 결정 칸에 "10/22 확정(ADR-49) + ⚠️ 항목 수정"(idem 키, refresh 키·jwt-secret·rl 키 삭제, traceId 32자리, details 배열, `{items}`, seat-event `screeningId`, outbox_message, ai·stats 스키마 삭제), 03a Action Item "docs/conventions.md 커밋" 완료 처리
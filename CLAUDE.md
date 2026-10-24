# SeatSAC (싯싹) — 팀 공통 컨텍스트

> 기준 2026-10-22 · 회의록1·2 결정(ADR-49~64) 반영. `docs/` 6개 문서를 순서대로 합친 파일이다.
> **고칠 때는 `docs/`의 원본을 PR로 고치고 이 파일을 다시 만든다**(`docs/` → `CLAUDE.md`). 노션과 다르면 이 파일(레포)이 우선이다.

## 0. 먼저 읽을 것

**프로젝트**: 실시간 좌석 선점과 무중단 배포를 시연하는 영화 예매 사이트. 4명, 2026-10-14 ~ 11-20(발표). staging 하나가 곧 데모 환경이고 **main 머지 = 배포**다.

| 데모 | 증명할 것 |
| --- | --- |
| 무중단 배포 | 배포(booking·gateway 블루/그린) 중 k6 부하에서 오류율 0%, 실패 버전 자동 롤백 |
| 실시간 좌석 | 같은 좌석 100명 동시 요청 → 성공 정확히 1명, 선점 p95 300ms 이하, 다른 화면 반영 1초 이내 |

| 담당 | 영역 |
| --- | --- |
| 한채연 | gateway · catalog · user · 화면(메인·상영시간·로그인·마이페이지·결제) · 좌석 배치 시드 |
| 남궁일 | booking · payment · 좌석 화면 · 백업 관리자(`cinema-admin`) |
| 김종식 | Infra · DevOps (Terraform · CI/CD · gitops · 관측 · Lambda) |

**절대 규칙**

- 비밀 값(액세스 키·비밀번호·tfstate 내용·민감한 tfvars 값·MFA 정보)을 코드·문서·PR·커밋에 쓰지 않는다. 발표 후 레포가 Public이 된다
- 롯데시네마 원본·fixture·시드는 비공개 `lotte-seed`에만. 롯데 실제 예매 API 호출 금지, 롯데 포스터 이미지 표시 금지
- AWS 콘솔에서 리소스를 만들지 않는다(Terraform만). `terraform apply`는 김종식(또는 백업 관리자)이 로컬에서 `cinema-admin`으로만, CI는 plan만. bootstrap·global은 destroy 금지
- 클러스터를 `kubectl apply/edit/delete`로 직접 고치지 않는다(GitOps만). 사람이 ECR·frontend S3에 직접 올리지 않는다(CI만)
- 계약(`contracts/`)을 먼저 PR로 합의하고 구현한다. 필드 추가만, 깨는 변경은 새 엔드포인트·`.v2`
- 이름·설정·메트릭은 아래 표에 있는 것만 쓰고, 새로 만들면 표에 먼저 추가한다

---

# 1. 네이밍·공통 규약 (`docs/conventions.md`)

> 기준 2026-10-22 (회의록2 결정 반영, ADR-49~64). 03a 명명 규칙 개정을 확정한 최종본. 03a 이후 결정(Cognito ADR-37, API 명세서 v1.1, 2.2.4 v0.3, ERD 6차)을 반영해 03a와 다른 곳은 ⚠️로 표시했다.
> 범위: 서비스 5개(gateway·catalog·booking·user·payment) + Lambda 2개(notification·cognito-presignup) (ADR-50·51).
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
| frontend | React (Vite) | 없음 (S3 + CloudFront) | 5173 (`/api` → 8080 프록시) | — |
| MySQL 8.4 / Valkey 8.2 / ElasticMQ (로컬) | — | 3306 / 6379 / 9324 | 같음 | RDS / ElastiCache(TLS) / SQS |

| 경로 | 규칙 |
| --- | --- |
| 외부 API | `https://{배포ID}.cloudfront.net/api/{경로}` → ALB → gateway가 `/api` 제거(StripPrefix=1) |
| 내부 API | `/internal/**` — Gateway 라우팅 금지, NetworkPolicy로 호출자 제한 |
| 헬스 | `/actuator/health/liveness`, `/actuator/health/readiness` (ALB는 gateway readiness만) |
| 메트릭 | `/actuator/prometheus` |
| Gateway 404 | `/api/internal/**`, `/api/actuator/**`, `/api/payments/**`, `/api/auth/**` |

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

### 4. Valkey 키·채널

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
| 오류 응답 | ⚠️ `{ code, message, traceId, details[] }` — `details`는 **항상 배열**(없으면 `[]`), 원소 `{ field, value?, reason? }`. 화면 분기는 `code`, `message`는 개발용 (`contracts/schemas/error.v1.json`) |
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

### 7. 메트릭

- 이름 `{service}_{대상}_{단위}`, 라벨은 `result`·`route` 등 값 종류가 적은 것만(사용자 ID·좌석 ID 금지)
- 필수 메트릭과 라벨 값은 `docs/service-runtime-contract.md` 5장

### 8. 환경변수·설정·Secret

| 구분 | 규칙 | 예 |
| --- | --- | --- |
| Spring 표준 | 바인딩 이름 그대로 | `SPRING_DATASOURCE_URL`, `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_SSL_ENABLED` |
| 앱 고유 | `APP_` | `APP_SEAT_HOLD_TTL_SECONDS=300`, `APP_SEAT_HOLD_STRATEGY=redis` |
| 다른 서비스 주소·타임아웃 | `APP_SERVICES_{NAME}_URL`, `_CONNECT_TIMEOUT`, `_READ_TIMEOUT` | `APP_SERVICES_PAYMENT_URL=http://payment:8080` |
| 인증 | ⚠️ `APP_COGNITO_ISSUER_URI`, `APP_COGNITO_CLIENT_ID` (gateway 앱 설정 `app.cognito.*`, Spring 표준 `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`는 쓰지 않음. 03a `APP_JWT_SECRET`·`jwt-secret` 삭제) | — |
| AWS | SDK 표준 `AWS_REGION=ap-northeast-2`. 자격 증명은 Pod Identity, 액세스 키 금지 | — |
| frontend | `VITE_` (빌드에 포함 = 공개 값만) | `VITE_API_BASE_URL=/api`, `VITE_COGNITO_AUTHORITY` |
| K8s Secret (Terraform 생성) | `{service}-secrets`(앱), `{service}-migrator-secrets`(Flyway Job만) | `booking-secrets` |
| ConfigMap | `{service}-config` | — |
| 프로필 | `local`, `staging` | `SPRING_PROFILES_ACTIVE=staging` |

> `VITE_`에 비밀 값 금지. Secret 값은 Git·문서에 두지 않는다(Terraform 랜덤 생성 → K8s Secret, tfstate는 암호화 S3).

### 9. K8s·AWS 리소스 이름

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

### 10. Git

`docs/repo-workflow.md` (레포·브랜치·커밋·PR·CI).


---

# 2. 서비스 계약 (`docs/contracts.md`)

> 기준 2026-10-21. 원본은 `contracts/` 파일이고 이 문서는 목록과 확정 결정만 담는다. 파일과 이 문서가 다르면 **파일이 맞다**.
> 근거: API 명세서 v1.1(10/21), 2.2.4 Catalog·User API 명세 v0.3(10/21), 계약 대조표(15번), 10/22 회의록2 결정(ADR-54·55).

---

### 1. 파일

| 파일 | 내용 | 담당 (CODEOWNERS) |
| --- | --- | --- |
| `openapi/catalog.openapi.yaml` | 외부 9개 (조회 6 · 관리자 3) | 한채연 |
| `openapi/booking.openapi.yaml` | 외부 7개 (좌석 현황·SSE·선점·해제·결제·조회·취소) | 남궁일 |
| `openapi/user.openapi.yaml` | 외부 5개 (프로필 조회·수정, 예매 내역, 선호 조회·저장) | 한채연 |
| `openapi/internal/catalog.internal.openapi.yaml` | 내부 3개 | 한채연 |
| `openapi/internal/booking.internal.openapi.yaml` | 내부 2개 | 남궁일 |
| `openapi/internal/payment.internal.openapi.yaml` | 내부 3개 | 남궁일 |
| `events/seat-event.v1.json` | Valkey Pub/Sub → SSE | 남궁일 |
| `events/booking.confirmed.v1.json` | SQS 예매 완료 알림 | 남궁일 (소비 Lambda 김종식) |
| `schemas/error.v1.json` | 공통 오류 응답 | 전원 |
| `*/examples/**` | 예시 메시지 = 서비스 테스트 fixture | 각 담당 |

합계: 외부 21 · 내부 8 · 이벤트 2.

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

### 3. 확정 결정 (10/22)

| # | 항목 | 결정 | 근거 |
| --- | --- | --- | --- |
| 1 | OpenAPI 버전 | **3.1.0** (error·이벤트 스키마를 JSON Schema 그대로 `$ref`) | 2.2.7 |
| 2 | userId | Cognito `sub` 문자열(`format: uuid`), DB `CHAR(36)` | ADR-37, ERD 6차 |
| 3 | 프로필 email·name | user가 Access Token으로 Cognito `GetUser`. 실패 시 **503 `IDENTITY_UNAVAILABLE`** | 2.2.4 v0.3, ADR-54 |
| 4 | `GET /internal/theaters` | **만든다** (USR-05 극장 검증, Catalog 실패 시 검증 생략) | API 명세서 10장 #6 |
| 5 | 범위 | 위 29개가 전부. 요청 수 제한(429) 없음 | ADR-50·62 |
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
> 범위: 노드 t3.large 2대(ng-a 1 · ng-c 1, ADR-7·34), 환경 staging 하나. 값은 10/22 확정(ADR-57·58·59·63).

---

### 1. 모든 서비스 공통 (Java 5개)

| 항목 | 앱이 할 일 | 인프라가 할 일 |
| --- | --- | --- |
| 포트 | 8080 하나 (앱·actuator 같은 포트) | Service `{svc}:8080`, 포트 이름 `http` |
| 헬스 | `management.endpoint.health.probes.enabled=true`. readiness 그룹 = `readinessState,db` (gateway는 `readinessState`만). liveness에 외부 의존 넣지 않음 | 프로브(아래 2장) |
| 메트릭 | `micrometer-registry-prometheus`, `/actuator/prometheus` 노출, 공통 태그 `application={svc}`. `http.server.requests` 히스토그램 켜기(`management.metrics.distribution.percentiles-histogram.http.server.requests=true`) | PodMonitor `{svc}`(라벨 `app.kubernetes.io/name`, 포트 `http`, 15초, 파드 라벨 `rollouts-pod-template-hash`를 메트릭에 붙임). ServiceMonitor는 active·preview Service가 같은 파드를 두 번 수집해서 쓰지 않는다 |
| 종료 | `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=20s`. booking은 SIGTERM 때 SSE 연결을 정상 종료 | `preStop: sleep 5`, `terminationGracePeriodSeconds: 35` |
| 로그 | stdout JSON 한 줄(`libs/common-web` logback). 파일 로그 금지 | `kubectl logs`로 traceId 검색 (Loki 없음, OPS-09 P3) |
| 공통 코드 | `libs/common-core`·`common-web` 의존 → 오류 형식·traceId·`@CurrentUser`·내부 호출 클라이언트 자동 적용 | `libs/**` 변경 시 `pr-check`가 5개 전부 테스트, 배포는 안 함 |
| 시간 | JDBC URL에 `connectionTimeZone=UTC`, `Clock.systemUTC()` 주입 | RDS 파라미터 그룹 `time_zone=UTC` |
| 설정 주입 | 아래 3장 이름만 읽는다. 기본값은 `application-local.yml`에만 | `envFrom`: ConfigMap `{svc}-config` + Secret `{svc}-secrets` |
| 이미지 | `bootJar` 결과 `build/libs/app.jar`, Dockerfile은 4장 템플릿 | `build-push`가 x86 빌드 → ECR `cinema/{svc}:{sha7}` |
| 비루트 | 쓰기 경로 `/tmp`만 | `runAsNonRoot: true`, `readOnlyRootFilesystem: true` + `/tmp` emptyDir |

### 2. 서비스별 배포 방식·자원

| 서비스 | 배포 | replicas (HPA) | requests cpu / mem | limits mem | Valkey | RDS 스키마 | Pod Identity |
| --- | --- | --- | --- | --- | --- | --- | --- |
| gateway | **블루/그린** (Rollout) | 1 (HPA 끔, 블루/그린 중 2) | 250m / 512Mi | 768Mi | — | — | 없음 |
| booking | **블루/그린** (Rollout) | 1 (1~4, CPU 70%) | 250m / 512Mi | 768Mi | ✅ | `booking_db` | `cinema-staging-booking-pod`: `sqs:SendMessage`·`GetQueueUrl` on 알림 큐 |
| catalog | 롤링 (maxUnavailable 0, maxSurge 1) | 1 (1~4) | 200m / 448Mi | 640Mi | ✅ | `catalog_db` | 없음 |
| user | 롤링 | 1 (1~2) | 150m / 384Mi | 576Mi | — | `user_db` | 없음 (`GetUser`는 Access Token으로 호출, IAM 불필요) |
| payment | 롤링 | 1 (고정) | 150m / 384Mi | 576Mi | — | `payment_db` | 없음 |

- JVM: `-XX:MaxRAMPercentage=75` (Dockerfile). limits cpu는 두지 않는다(스로틀로 p95 악화)
- 두 서비스 동시 블루/그린 금지(노드 2대). 공통 모듈 급한 반영도 한 번에 한 서비스씩: catalog·user·payment → booking → gateway
- 프로브: `startupProbe` readiness 경로 5초 × 30회, `readinessProbe` 5초 / 실패 3, `livenessProbe` 10초 / 실패 3
- 블루/그린: active Service `{svc}`, preview Service `{svc}-preview`, `prePromotionAnalysis` 통과 후 전환, `scaleDownDelaySeconds: 60`. gateway는 ALB 대상 그룹 확인(ADR-35)
- booking 만료 스캔·아웃박스 릴레이는 **preview 파드에서도 돈다** → 만료·릴레이 로직 변경은 이전 버전과 호환되게(조건부 UPDATE로 한 번만 처리)

### 3. 설정·Secret (서비스가 읽는 이름)

값은 예시. 실제 값은 Terraform 출력(엔드포인트)과 Secret(비밀번호)에서 온다.

#### 공통 (ConfigMap `{svc}-config`)

| 키 | 값 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `staging` |
| `AWS_REGION` | `ap-northeast-2` |
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://{rds-endpoint}:3306/{svc}_db?connectionTimeZone=UTC&sslMode=REQUIRED` |
| `SPRING_DATASOURCE_USERNAME` | `{svc}_app` |
| `FLYWAY_URL` | `jdbc:mysql://{rds-endpoint}:3306/{svc}_db?useSsl=true&trustServerCertificate=true&sslMode=trust` — **Flyway Job 전용**(DB 쓰는 서비스만). flyway 이미지는 MySQL Connector/J 대신 MariaDB 드라이버(2.x: `useSsl`·`trustServerCertificate`, 3.x: `sslMode=trust`)라 TLS 옵션 이름이 다르다(`REQUIRED`를 주면 TLS 없이 붙어 `RSA public key is not available`로 실패). 앱은 읽지 않는다 |
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | `5` |
| `SPRING_FLYWAY_ENABLED` | `false` (마이그레이션은 Flyway 훅 Job) |
| `SPRING_DATA_REDIS_HOST` / `_PORT` / `_SSL_ENABLED` | `{valkey-primary-endpoint}` / `6379` / `true` (Valkey 쓰는 서비스만) |

#### 서비스별

| 서비스 | ConfigMap 키 | Secret `{svc}-secrets` 키 |
| --- | --- | --- |
| gateway | `APP_COGNITO_ISSUER_URI=https://cognito-idp.ap-northeast-2.amazonaws.com/{userPoolId}`, `APP_COGNITO_CLIENT_ID`, `APP_SERVICES_{CATALOG,BOOKING,USER}_URL` (issuer·client-id는 기본값이 없어 빠지면 기동 실패. `app.cognito.issuer-uri`·`app.cognito.client-id`로 바인딩 → `JwtDecoderConfig`가 issuer·`client_id` 검증) | 없음 |
| catalog | `APP_SERVICES_BOOKING_URL`, `APP_SERVICES_BOOKING_READ_TIMEOUT=300ms`, `APP_CACHE_TTL=10m` | `SPRING_DATASOURCE_PASSWORD` |
| booking | `APP_SERVICES_{CATALOG,PAYMENT}_URL`, `APP_SQS_NOTIFICATION_QUEUE_URL`, `APP_SEAT_HOLD_TTL_SECONDS=300`, `APP_SEAT_HOLD_STRATEGY=redis`(ADR-45 인터페이스, db-lock은 나중), `APP_OUTBOX_RELAY_INTERVAL=1s` | `SPRING_DATASOURCE_PASSWORD` |
| user | `APP_SERVICES_{BOOKING,CATALOG}_URL` | `SPRING_DATASOURCE_PASSWORD` |
| payment | `APP_PAYMENT_MOCK_DELAY_CARD=9999`, `APP_PAYMENT_MOCK_DELAY=5s` | `SPRING_DATASOURCE_PASSWORD` |

- 서비스 간 타임아웃 기본 `APP_SERVICES_{NAME}_CONNECT_TIMEOUT=1s`, `_READ_TIMEOUT=3s` (`libs/common-web` 기본값, 필요할 때만 덮어씀)
- Flyway Job 전용 Secret `{svc}-migrator-secrets`: `FLYWAY_USER={svc}_migrator`, `FLYWAY_PASSWORD`. 앱 파드에는 마운트하지 않는다
- 로컬(`local` 프로필): Compose MySQL·Valkey·ElasticMQ, 계정 비밀번호 `local`, SQS 엔드포인트 `http://localhost:9324`. Cognito는 staging 사용자 풀을 같이 쓴다(콜백 `http://localhost:5173/callback`)

#### frontend (빌드 시 `VITE_`, 공개 값만)

| 키 | 값 |
| --- | --- |
| `VITE_API_BASE_URL` | `/api` |
| `VITE_COGNITO_AUTHORITY` | `https://cognito-idp.ap-northeast-2.amazonaws.com/{userPoolId}` |
| `VITE_COGNITO_CLIENT_ID` | 공개 클라이언트 ID (시크릿 없음) |
| `VITE_COGNITO_DOMAIN` | `https://cinema-staging.auth.ap-northeast-2.amazoncognito.com` |
| `VITE_COGNITO_REDIRECT_URI` | `https://{배포ID}.cloudfront.net/callback` (로컬 `http://localhost:5173/callback`) |

`frontend-deploy` 워크플로가 Terraform 출력에서 값을 넣는다(GitHub Variables). 토큰은 `react-oidc-context`가 관리하고 `Authorization: Bearer`로 보낸다. 저장소는 **메모리**(`InMemoryWebStorage`, localStorage 금지). 새로고침하면 `signinRedirect`로 Cognito 세션을 이용해 다시 받는다(비밀번호 재입력 없음, ADR-63).

### 4. 이미지·DB 마이그레이션

```dockerfile
# services/{svc}/Dockerfile — 모든 Java 서비스 공통
FROM eclipse-temurin:21-jre
RUN useradd -r -u 10001 app
WORKDIR /app
COPY build/libs/app.jar app.jar
COPY src/main/resources/db/migration /app/db/migration   # Flyway Job이 복사해 감 (gateway는 없음)
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
```

| 항목 | 규칙 |
| --- | --- |
| 마이그레이션 실행 | ArgoCD 훅 Job `{svc}-flyway`(**Sync 단계 wave -1**: ConfigMap `{svc}-config` wave -2 → Job → Deployment/Rollout wave 0, 실패하면 sync 중단): initContainer(같은 앱 이미지)가 `/app/db/migration`을 emptyDir로 복사 → `flyway/flyway:11.7.2`(Spring Boot 3.5 Flyway 11.7과 같은 메이저) 컨테이너가 `{svc}_migrator`로 `migrate`. URL은 `{svc}-config`의 `FLYWAY_URL`(3장, 앱 URL과 별도), 계정은 `{svc}-migrator-secrets` |
| 파일 위치 | `services/{svc}/src/main/resources/db/migration/V{yyyyMMddHHmm}__{설명}.sql` |
| 호환 | 블루/그린·롤링 중 두 버전이 같은 DB를 쓴다 → 확장 → 전환 → 정리. 컬럼 삭제·이름 변경은 다음 배포 |
| 실패 | Job 실패 = sync 실패 → 새 버전이 뜨지 않는다. 고친 마이그레이션은 새 V 번호로(이미 적용된 파일 수정 금지) |
| 시드 | 롯데 배치·편성은 `lotte-seed`의 SQL을 `catalog_migrator`로 1회 적재(김종식·한채연, 런북). Flyway에 넣지 않음 |

### 5. 필수 메트릭 (대시보드·자동 롤백이 이 이름에 의존)

| 메트릭 | 타입 | 라벨 값 | 서비스 | 쓰는 곳 |
| --- | --- | --- | --- | --- |
| `http_server_requests_seconds_*` (Spring 기본) | histogram | `status`, `uri`, `method` | 전체 | 5xx 비율·p95, 블루/그린 분석 |
| `booking_seat_hold_total` | counter | `result`=`success`·`conflict`·`active_hold`·`error` | booking | 신호등·동시성 데모 |
| `booking_sse_connections` | gauge | — | booking | SSE 재연결 확인 (블루/그린 전환·노드 재기동 때 0으로 떨어졌다 다시 오르는 게 정상) |
| `booking_payment_call_total` | counter | `result`=`approved`·`declined`·`timeout`·`error` | booking | **자동 롤백**(`declined`는 실패율에서 제외) |
| `booking_hold_expiry_lag_seconds` | histogram | — | booking | 만료 반영 2초 목표. 노드 0대 구간(ADR-34) 뒤 첫 스윕은 밀린 만큼(수 시간) 큰 값이 한 번 찍히는 게 정상 → 경보는 파드 기동 후 10분 제외(3.1.7) |
| `booking_notification_publish_total` | counter | `result`=`sent`·`retry`·`failed` | booking (릴레이) | 알림 경보 |
| `booking_outbox_pending` | gauge | — | booking | 릴레이 밀림 |
| `catalog_cache_requests_total` | counter | `result`=`hit`·`miss` | catalog | 캐시 적중률 |
| `catalog_seat_counts_fallback_total` | counter | — | catalog | 300ms 초과 대체 횟수 |

블루/그린 분석(`SeatSAC-gitops/analysis/`):

| 대상 | 조건 (preview 파드, 1분 × 3회) |
| --- | --- |
| booking | 5xx 비율 ≤ 1% **그리고** `booking_payment_call_total{result=~"timeout\|error"}` / (`approved`+`declined`+`timeout`+`error`) ≤ 5% |
| gateway | 5xx 비율 ≤ 1% |

preview·active 구분은 파드 라벨 `rollouts-pod-template-hash`로 한다(앱은 아무것도 안 해도 됨).

### 6. 네트워크

| 규칙 | 내용 |
| --- | --- |
| NetworkPolicy | 앱 namespace **ingress 기본 거부** + 허용: ALB(VPC CIDR) → gateway, gateway → catalog·booking·user, catalog → booking, booking → catalog·payment, user → booking·catalog, `monitoring` → 전체(8080). egress는 막지 않음 |
| 전제 | EKS VPC CNI `enableNetworkPolicy=true` (3.1.2) |
| 보안그룹 | RDS 3306·Valkey 6379는 노드 보안그룹에서만. ALB는 CloudFront 관리형 접두사 목록만 |
| 외부 호출 | booking → SQS, user → Cognito `GetUser`, gateway → Cognito JWKS: NAT 경유 HTTPS |
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
| 7 | 서비스 Dockerfile·`bootJar` 이름·actuator 설정 | 각 서비스 담당 | 김종식 3.1.4 `build-push` | 서비스 첫 머지 | `pr-check`에서 이미지 빌드 성공 |
| 8 | gitops `base/{svc}` (Deployment/Rollout·Service·HPA·NetworkPolicy·PodMonitor·Flyway Job) | 김종식 (3.1.5) | 각 서비스 (main 머지 = 배포) | 첫 staging 배포 | ArgoCD Synced·Healthy |
| 9 | 필수 메트릭 구현 (5장) | 남궁일·한채연 | 김종식 3.1.6 AnalysisTemplate, 3.1.7 대시보드 | 3.1.6 블루/그린 시연 전 | `/actuator/prometheus`에 이름·라벨 존재 |
| 10 | SQS 큐·DLQ, booking Pod Identity, 알림 Lambda | 김종식 (3.1.8·3.1.9) | 남궁일 3.2.6 (확정·아웃박스 릴레이) | 3.2.6 통합 테스트 전 | 확정 → Lambda 로그에 `eventId` |
| 11 | `seat-event`·`booking.confirmed` 발행 테스트 (examples로 검증) | 남궁일 | 김종식 (Lambda 소비), 화면 | 3.2.3·3.2.6 | 발행 테스트가 `contracts/events` 스키마 통과 |
| 12 | CloudFront·S3·`frontend-deploy` + `VITE_` 값 | 김종식 (3.1.11) | 한채연·남궁일 화면 | 화면 첫 배포 | CloudFront 도메인 새로고침·`/api` 동작 |
| 13 | SSE 경로 세트(6장 SSE ①~④): ALB 유휴 120초(3.1.5 Ingress)·CloudFront 캐시·압축 끔(3.1.11)·Gateway 라우트 타임아웃 없음·`/api` StripPrefix | 김종식(ALB·CloudFront) · 한채연(라우트) | 남궁일 BKG-05 | 3.2.3 SSE 확인 (3.1.5·3.1.11 작업 때 같이 확인) | CloudFront 경유 `curl -N`으로 15초 ping이 몰리지 않고 바로바로 옴 |
| 14 | NetworkPolicy 허용 목록 (6장) | 김종식 (3.1.13) | 전 서비스 내부 호출 | 내부 API 통합 후 | gateway 파드 → payment 실패 확인 |
| 15 | 부하 테스트 시나리오 대상 API·데이터 | 남궁일 | 김종식 OPS-10 (k6 Job) | 4.2.1 전 | k6 스크립트 dry-run |

### 8. 인프라 쪽 남은 정리

- [x] Compose·Terraform에서 `stats_db`·`stats_lambda`·`lambda-stats` 보안그룹·`lambdas/daily-stats` 삭제 (10/22, SG는 다음 apply에서 삭제)
- [x] EKS 1.36 · 노드그룹 ng-a·ng-c · 애드온(VPC CNI 네트워크 정책 켬·metrics-server) · Access Entry · LB Controller (3.1.2, 코드 10/22 · apply 후 런북 4장 확인)
- [x] 가동 정책: 평일 08:23 켜기·23:07 끄기(KST), 데모 주간 24시간, Budgets 월·일 경보 (3.1.14, 코드 10/22 · global apply 후 런북 8-1장 확인)
- [x] ElastiCache Valkey 8.2 `cache.t4g.micro`(noeviction·Ex·TLS) + 메모리 80%·Evictions 경보, RDS MySQL 8.4 Single-AZ `db.t4g.small`(UTC·utf8mb4·TLS 강제) (3.1.3, 코드 10/22 · apply 후 런북 5장 확인)
- [x] DB 스키마 4·계정 8(`REQUIRE SSL`)·`{svc}-secrets`·`{svc}-migrator-secrets` (3.1.12, `envs/staging-k8s/db_bootstrap.tf` — 클러스터 안 Job이 마스터 Secret을 Pod Identity로 읽어 생성, 코드 10/23 · apply 후 런북 8장 확인). Flyway 훅 Job 틀은 gitops `base/{svc}/flyway-job.yaml`(3.1.5에서 연결)
- [ ] Compose `elasticmq-native:latest` → 버전 고정 — 김종식
- [ ] `infra/terraform/envs/staging`에 Cognito·SQS·Pod Identity 모듈 추가 시 이 문서 3·6장 이름 그대로 — 김종식


---

# 4. 데이터 모델·DB 계약 (`docs/data-model.md`)

> 기준 2026-10-21. 원본은 노션 WBS 2.2.2 「ERD·서비스별 스키마」(ERD 6차, 완료)와 각 서비스 Flyway V1. 이 문서는 개발·인프라가 함께 보는 **요약**이다. 테이블 정의가 바뀌면 Flyway 파일이 먼저, 이 문서가 다음이다.
> 10/22 결정 반영(ADR-59·60·61). 스키마는 4개(catalog·booking·user·payment)뿐이다.

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
| `payment` | PK `payment_id` CHAR(36)(booking 발급), **UK `booking_id`**(예매당 1회, ADR-24) | `amount`, `method`, `card_last4`(카드 번호 저장 금지), `status` PROCESSING·APPROVED·FAILED·CANCELED, `failure_code`, `cancel_reason`, `pg_provider`(`MOCK`만, ADR-60), `approved_at`·`canceled_at`. user_id 없음. PROCESSING으로 먼저 INSERT·커밋 → 판정은 트랜잭션 밖 → `UPDATE … WHERE status='PROCESSING'`. 30초 넘게 남은 PROCESSING은 payment 기동 시 `FAILED`(`PROCESSING_TIMEOUT`)로 정리(ADR-60) |

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
| 만들지 않는 것 | 리포트 뷰(`v_*_report`)·`stats_db` — catalog Flyway V2 `v_screening_report`는 머지하지 않음(ADR-60) |


---

# 5. 결정 요약 (ADR) (`docs/decisions.md`)

> 기준 2026-10-22 (회의록2 결정 반영). 원본은 노션 「결정 기록 (ADR)」(번호 = DB 번호). 근거·대안은 노션에서 본다. 이 문서는 **지금 유효한 결론**만 한 줄씩 둔다.
> 이후 결정이 앞 결정을 덮은 경우 "→ 대체"로 표시했다. ⏳는 아직 열린 항목이다.

---

### 1. 범위·일정·사람

| ADR | 결론 | 비고 |
| --- | --- | --- |
| 1 · 50 | 서비스는 gateway·catalog·booking·user·payment 5개. AI 서비스는 범위에서 제외(10/22), 필요하면 그때 다시 추가 | 26(LLM) 폐기 |
| 52 | 데모는 **무중단 배포 · 실시간 좌석** 2개로 확정. 실시간 좌석 데모에 동시성 테스트(같은 좌석 100명 → 성공 1명) 포함 | 선점 전략 비교는 여유 시 |
| 45 | 선점 전략 비교(db-lock/redis): 지금은 3.2.2에 `SeatHoldStrategy` 인터페이스만, db-lock은 잠글 행·만료 처리 **설계 초안만**(남궁일). 구현·측정은 최후 순위(4.2 때 여유 시) | 새 노드 불필요 |
| 53 | 김서현은 일정에서 제외. 필요하면 개발·인프라/DevOps의 WBS 하나씩 맡긴다 | 4.1.2 한채연, OPS-07 코드 김종식 |
| 25 | Payment 백엔드는 남궁일, 결제 화면은 한채연 | |
| 2 | 롯데시네마 좌석 배치·하루 편성만 로컬에서 1회 수집 → 비공개 `lotte-seed` 시드 | 실시간 연동·예매 호출 없음 |
| 15 · 16 | ⏳ 스크린 위치(GetSeats `ScreenSeatInfo` 사용 가정) · 수집 방식(로컬 1회 가정) — 한채연 확인 예정 | |

### 2. 데이터·실시간

| ADR | 결론 | 비고 |
| --- | --- | --- |
| 3 · 33 · 61 | RDS **MySQL 8.4** 1대(**Single-AZ**, `db.t4g.small`, TLS 강제, 마스터 비밀번호는 RDS 관리 Secrets Manager) + 서비스별 스키마·계정 | Multi-AZ·AZ 장애 실험은 시간 남을 때 |
| 33 · 59 | ElastiCache **Valkey 8.2 `cache.t4g.micro`**, `noeviction`, `notify-keyspace-events Ex`, 메모리 80%·Evictions 경보 | 부하 테스트에서 70% 넘으면 t4g.small |
| 57 | DB 마이그레이션은 ArgoCD **Flyway 훅 Job**(`{svc}_migrator`, Sync 단계 wave -1 — ConfigMap 다음·앱 전, 실패 시 sync 중단), 앱은 Flyway 끔. 스키마·계정·Secret은 Terraform(`envs/staging-k8s`)이 클러스터 안 Job으로 생성(3.1.12) | 처음 "PreSync"였으나 첫 배포 때 ConfigMap보다 먼저 돌아 Sync wave -1로 변경(10/23) |
| 60 | 리포트 뷰 만들지 않음(catalog V2 머지 안 함) · payment `PROCESSING` 30초 초과는 기동 시 `FAILED(PROCESSING_TIMEOUT)` · `pg_provider`는 `MOCK`만 | |
| 4 | 좌석 이벤트: Valkey Pub/Sub `seat-events:{회차}` → SSE, 놓치면 BKG-01 스냅샷 | 유실 허용 |
| 5 | 선점: Valkey `SET NX EX 300`(Lua로 여러 좌석 원자적) + 그 시점 RDS PENDING 예매 | |
| 11 | 한 번에 최대 4석 | |
| 19 | 멱등: `Idempotency-Key` + Valkey `idem:{userId}:{키}` 24시간 + 유일 제약(`booking_seat`, `current_payment_id`) | |
| 22 | 내부 브로커 없음: Pub/Sub + keyspace 알림 + sorted set | 만료 반영 2초 초과 시 재검토 |
| 44 | 만료: keyspace(Ex) + `hold-expiry` 1초 스캔 + RDS 10초 스캔, 조건부 UPDATE로 한 번만 | 노션 본문 "ADR-21" = 이 항목 |

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
| 7 · 34 | EKS On-Demand **t3.large 2대**(ng-a 1 · ng-c 1). 환경은 **staging 하나**. 평일 밤·주말 노드 0대(월~금 08:23 켜기·23:07 끄기 KST, 3.1.14), 데모 주간 11/16~20 24시간. 비용 경보 Budgets 월·일 | 노션 ADR-7 제목 "3대"는 이전 값 |
| 8 · 35 · 58 | Kustomize + ArgoCD. booking·gateway만 블루/그린(5xx 1%·결제 timeout/error 5% 롤백, scaleDown 60초), 나머지 롤링. 프로브·자원·HPA·메트릭·Secret·NetworkPolicy는 `docs/service-runtime-contract.md` | 동시 블루/그린 금지 |
| 9 · 31 | CloudFront + S3(OAC), 기본 도메인 HTTPS, `/api/*` → ALB, CORS 없음 | 31의 httpOnly 쿠키는 37·63으로 대체 |
| 10 | 관측은 Prometheus·Grafana, 비밀 값은 Terraform이 만드는 K8s Secret | External Secrets·Loki는 확장 |
| 36 · 39 | AWS 아키텍처 = 위 결정들의 합. 노션 ADR-36(10/18 v3)은 이전 스냅샷 | |
| 46 | GitHub Actions → AWS OIDC. 역할 `cinema-gha-ecr-push`·`cinema-gha-tf-plan`·`cinema-gha-frontend-deploy`·`cinema-gha-cluster-schedule`(3.1.14). CI는 apply 안 함 | `infra/terraform/global` |
| 47 | 팀 접근: IAM 역할 + MFA(Identity Center 미사용). `cinema-admin` = 김종식 + **백업 남궁일(`sesac-b3`)**, `cinema-developer` = 전원. 프로필 `seatsac-user`·`cinema-dev`·`cinema-admin` | 회의록3 |

### 5. 인증·화면·코드·프로세스

| ADR | 결론 | 비고 |
| --- | --- | --- |
| 37 | Cognito 사용자 풀(Hosted UI, 코드 + PKCE), Gateway가 Access Token을 JWKS로 검증 → `X-User-Id`(= sub)·`X-User-Role` | |
| 63 | 화면 토큰 저장은 **메모리**(`InMemoryWebStorage`), localStorage 금지. 새로고침은 Cognito 세션으로 재발급 | |
| 56 | 공통 코드: 같은 레포 공유 모듈(`libs/common-*` + `build-logic`), 패키지 `com.seatsac.{service}`·`com.seatsac.common.{core,web,test}`. `libs/` 변경 승인 2명 | 최종 결정 21번 "복사" 대체 |
| 48 · 64 | 레포 `SeatSAC`·`SeatSAC-gitops`, 브랜치 `{type}/{기능ID 또는 WBS ID}-{설명}`, 데모 주간 11/16~20 머지 금지, 태그 rc1 11/6·rc2 11/13·v1.0.0 11/19, Lambda Python, 이슈는 노션 WBS만 | `docs/repo-workflow.md` |
| 49 | 공통 문서 6종 채택, 합본 `Claude.md`를 레포 루트에 (노션과 다르면 레포 우선, 수정은 PR) | 65는 49로 통합(폐기) |


---

# 6. 레포·브랜치·PR·CI (`docs/repo-workflow.md`)

> 기준 2026-10-22 (회의록2 결정 반영, ADR-49~64). 03b 레포·브랜치·PR 개정 확정본 + 08 공통 코드(B안, ADR-56) + 브랜치 WBS ID(ADR-48) + 레포·일정(ADR-64) + 2.2.7 계약 CI.
> 전제: staging 하나가 곧 데모 환경이라 **main 머지 = 배포**다(`build-push` 자동 트리거는 3.1.4에서 켠다. 그 전에는 수동).

---

### 1. 레포

| 레포 | 공개 | 내용 |
| --- | --- | --- |
| `sesac-mzc-final-2/SeatSAC` (앱, 03b의 `cinema`) | 개발 중 Private → 발표 후 Public | 서비스, frontend, 계약, Terraform, Lambda, 테스트, 문서 |
| `SeatSAC-gitops` | Private | Kustomize 매니페스트, ArgoCD Application. CI 봇이 이미지 태그 커밋 |
| `lotte-seed` | **항상 Private** | 수집 스크립트, 원본 스냅샷, 좌석 배치·편성 시드 SQL |

```
SeatSAC/
├── settings.gradle.kts · gradle/libs.versions.toml · build-logic/   빌드 설정 공통 (08)
├── libs/common-core · common-web · common-test                     런타임 규약만 (오류·traceId·헤더·로그·내부 클라이언트)
├── services/{gateway,catalog,booking,user,payment}/                각 Dockerfile, db/migration
├── frontend/                                                       React(Vite) → S3
├── contracts/{openapi,events,schemas,tools}/                       계약 + 계약 CI
├── lambdas/{notification,cognito-presignup}/                       Python
├── infra/terraform/{bootstrap,global,envs/staging,envs/staging-k8s}/ · infra/compose/
├── tests/k6/
├── docs/   conventions.md · contracts.md · service-runtime-contract.md · data-model.md · decisions.md · repo-workflow.md · runbooks/
└── .github/ workflows/ · CODEOWNERS · pull_request_template.md

SeatSAC-gitops/
├── apps/                  App of Apps (root.yaml + staging/: project·플랫폼 2·cinema-common·서비스 5)
├── platform/              Argo Rollouts, kube-prometheus-stack values (LB Controller는 Terraform envs/staging-k8s)
├── base/{service}/        Deployment 또는 Rollout, Service(+preview), HPA, NetworkPolicy, PodMonitor, Flyway 훅 Job(Sync wave -1)
├── base/common/           앱 namespace 인바운드 기본 거부 + analysis
├── overlays/staging/{service}/  이미지 태그, ConfigMap {service}-config (서비스마다 Application 하나)
├── components/rollout/    kustomize가 Rollout 안 ConfigMap 해시 이름을 바꾸게 하는 설정
├── analysis/              AnalysisTemplate (booking·gateway)
└── .github/               CODEOWNERS · PR 템플릿 · gitops-validate
```

- `services/ai/`·`lambdas/daily-stats/`는 10/22 삭제(ADR-50·51)
- Secret 매니페스트는 gitops에 없다(Terraform이 생성, Deployment는 이름으로만 참조)

### 2. 브랜치·커밋

| 항목 | 규칙 |
| --- | --- |
| 기본 브랜치 | `main` 보호(ruleset), 직접 push 금지. **main 머지 = staging 자동 배포** — 단 지금은 `build-push`가 수동 실행(`workflow_dispatch`)만 켜져 있다. 서비스 골격·ECR이 생기는 3.1.4에서 main push 트리거를 켠다 |
| 브랜치 이름 | `{type}/{기능ID}-{설명}`. 기능 ID가 없으면 **WBS ID** (ADR-48) |
| type | `feat` · `fix` · `refactor` · `test` · `docs` · `infra` · `ci` · `chore` |
| 예 | `feat/BKG-02-seat-hold`, `fix/CAT-05-cache-key`, `infra/3.1.3-rds-valkey`, `ci/2.2.7-contract-ci`, `docs/ADR-47` |
| 수명 | 최대 3일. 길어지면 기능 플래그로 나눠 머지 |
| 머지 | Squash merge |
| 커밋 메시지 | Conventional Commits + 기능 ID 또는 WBS ID: `feat(booking): BKG-02 좌석 선점 Lua 스크립트`, `infra(terraform): 3.1.3 RDS·Valkey` |
| 릴리스 태그 | `rc1` 11/6(M5), `rc2` 11/13(M6), `v1.0.0` 11/19(리허설 전) |
| 동결 | 11/13 이후 `fix/`만, 승인 2명 + 김종식 확인. **11/16~20 데모 주간은 승인된 fix 외 머지 금지** |

### 3. PR

| 항목 | 규칙 |
| --- | --- |
| 승인 | 1명 (11/13 이후 2명). `libs/`·`build-logic/` 변경은 2명(김종식 + 영향받는 서비스 담당) |
| 필수 검사 (ruleset) | `java`·`gitleaks`(pr-check), `lint (OpenAPI · JSON Schema · 예시)`·`하위 호환 (base 대비)`(contract-check). 네 검사 모두 **모든 PR에서 실행**된다(contract-check는 `paths` 필터 없이 `변경 감지` job이 계약 변경이 없으면 두 job을 건너뜀 = 성공). `infra-plan`은 `paths` 필터가 있어 필수로 걸지 않는다 — `infra/terraform/**` PR은 리뷰어가 plan 코멘트를 확인. `java`는 Gradle wrapper·서비스 골격이 들어온 뒤 필수로 추가 |
| 크기 | 400줄 이하 권장(생성 코드·lock 제외) |
| 리뷰 응답 | 24시간 이내(주말 제외) |
| 머지 후 | 작성자가 ArgoCD Synced·Healthy(또는 frontend 배포 완료)까지 확인 |
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
| `frontend/` | 한채연 · 남궁일 |
| `contracts/` | 한채연 · 남궁일 |
| `contracts/tools/`, `contracts/redocly.yaml`, `build-logic/`, `gradle/`, `lambdas/`, `infra/`, `.github/`, `tests/k6/` | 김종식 |
| `libs/` | 김종식 · 한채연 · 남궁일 |

### 4. CI/CD 워크플로

| 워크플로 | 트리거 | 하는 일 |
| --- | --- | --- |
| `pr-check` | PR | 바뀐 서비스 빌드·테스트(Testcontainers MySQL 8.4·Valkey 8.2·ElasticMQ), `libs/**` 변경 시 5개 전부, Trivy, gitleaks. 2단계: springdoc 생성본 vs 계약 diff |
| `contract-check` | PR (항상, 변경 없으면 건너뜀) | Redocly lint, JSON Schema·예시 검증, oasdiff·스키마 하위 호환 |
| `build-push` | **현재 수동**(`workflow_dispatch`). 3.1.4부터 main 머지(바뀐 서비스만) | x86 이미지 → ECR(OIDC `cinema-gha-ecr-push`) → `SeatSAC-gitops`에 `kustomize edit set image` 커밋 |
| `frontend-deploy` | main 머지(`frontend/**`) + 수동 | Vite 빌드 → S3(`/assets/*` 1년 immutable, `index.html` no-cache) → 무효화 |
| `lambda-deploy` | main 머지(`lambdas/**`) | 패키징 → 배포 → 별칭 갱신 |
| `infra-plan` | PR(`infra/terraform/**`, 필수 검사 아님) | `terraform plan`(OIDC `cinema-gha-tf-plan`) → PR 코멘트. **apply는 김종식이 로컬에서 `cinema-admin`으로** |
| `cluster-schedule` | 월~금 08:23 켜기 · 23:07 끄기(KST) + 수동(up·down·status) | 노드그룹 desired 1 ↔ 0 (ng-a·ng-c, OIDC `cinema-gha-cluster-schedule`). 데모 주간 11/16~20과 Variables `CLUSTER_KEEP_ON=true`이면 예약 끄기 건너뜀. 스크립트 `infra/scripts/cluster-scale.sh` |
| `gitops-validate` (gitops 레포) | PR | `kustomize build` + kubeconform |

- OIDC 역할 세션 이름 `gha-${{ github.actor }}-${{ github.run_id }}`(CloudTrail에 실행자)
- 공통 모듈만 바뀐 머지는 배포하지 않는다. 급하면 `build-push` 수동 실행으로 한 서비스씩

### 5. 지켜야 할 것

| 규칙 | 이유 |
| --- | --- |
| 비밀 값(키·비밀번호·tfstate 내용·tfvars의 민감 값)을 Git·문서·PR에 쓰지 않는다 | 발표 후 Public 전환. gitleaks가 막는다 |
| 롯데 원본·fixture·시드는 `lotte-seed`에만 | 준수 조건(공개 금지) |
| 롯데 실제 예매 API 호출 금지, 롯데 포스터 이미지 표시 금지 | 준수 조건 |
| 사람은 ECR·frontend S3에 직접 올리지 않는다 | ECR 저장소 정책(CI 역할만 push) + 태그 불변 |
| AWS 콘솔에서 리소스를 만들지 않는다. IAM·OIDC는 `infra/terraform/global`, 나머지는 `envs/staging` | 10/21 OIDC 공급자 중복 사례 |
| `terraform destroy`로 bootstrap·global을 지우지 않는다 | tfstate·팀 접근 상실 |
| 클러스터 직접 수정 금지(selfHeal이 되돌림). 긴급 시 해당 앱 auto-sync 끄고 작업 → Git 반영 → 스크럼 공유 | GitOps |
| 팀원 평소 작업은 `cinema-dev` 프로필(MFA). `cinema-admin`은 김종식 + 백업 관리자 남궁일(`sesac-b3`) | ADR-47 |
| 작업·진척은 노션 WBS만. GitHub Issues 안 씀 | 03b |
| 발표 후 Public 전환 전: gitleaks 전체 이력 스캔, 시드·원본·`.env`·tfvars 없음 확인 | — |

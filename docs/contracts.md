# 서비스 계약 요약 (contracts)

> 기준 2026-10-21. 원본은 `contracts/` 파일이고 이 문서는 목록과 확정 결정만 담는다. 파일과 이 문서가 다르면 **파일이 맞다**.
> 근거: API 명세서 v1.1(10/21), 2.2.4 Catalog·User API 명세 v0.3(10/21), 계약 대조표(15번), 10/22 회의록2 결정(ADR-54·55).
> ⚠️AI 11/5 AI 재도입(ADR-82) · 11/6 WBS 2.2.8: `openapi/ai.openapi.yaml` 외부 2개 + 응답 예시 5종 **추가**(이 파일이 기준, 구현은 3.4.x). 상세는 API 명세서 v1.4 15장.

---

## 1. 파일

| 파일 | 내용 | 담당 (CODEOWNERS) |
| --- | --- | --- |
| `openapi/catalog.openapi.yaml` | 외부 9개 (조회 6 · 관리자 3) | 한채연 |
| `openapi/booking.openapi.yaml` | 외부 8개 (좌석 현황·SSE·선점·해제·결제 준비·결제·조회·취소) | 남궁일 |
| `openapi/payment.openapi.yaml` | 외부 1개 (포트원 웹훅 — 인증 없음, 서명 검증) | 남궁일 |
| `openapi/user.openapi.yaml` | 외부 5개 (프로필 조회·수정, 예매 내역, 선호 조회·저장) | 한채연 |
| ⚠️AI `openapi/ai.openapi.yaml` | 외부 2개 (챗봇 질문 `POST /ai/chat`, 추천 질문 목록 `GET /ai/presets`) | 김서현 · 한채연 |
| ⚠️AI `openapi/examples/ai/*.json` | 챗봇 응답 예시 5종(CACHE_SEMANTIC·PRESET·NL2SQL·MY_BOOKINGS·REJECTED). OpenAPI Example Object `{ summary, value }` — `value`가 fixture, YAML이 `$ref`로 참조해 Redocly가 스키마로 검증 | 김서현 |
| `openapi/internal/catalog.internal.openapi.yaml` | 내부 3개 | 한채연 |
| `openapi/internal/booking.internal.openapi.yaml` | 내부 2개 | 남궁일 |
| `openapi/internal/payment.internal.openapi.yaml` | 내부 4개 (생성·조회·확인·취소) | 남궁일 |
| `events/seat-event.v1.json` | Valkey Pub/Sub → SSE | 남궁일 |
| `events/booking.confirmed.v1.json` | SQS 예매 완료 알림 | 남궁일 (소비 Lambda 김종식) |
| `schemas/error.v1.json` | 공통 오류 응답 | 전원 |
| `*/examples/**` | 예시 메시지 = 서비스 테스트 fixture | 각 담당 |

합계: 외부 25(⚠️AI ai 2개·포트원 웹훅 1개 포함) · 내부 9 · 이벤트 2.

## 2. API 목록

### 외부 (`/api` 접두사, Gateway StripPrefix)

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
| USER | `POST /bookings/{bookingId}/payments` | BKG-10 | `Idempotency-Key` 필수. TOSSPAY 결제 준비(paymentId + 포트원 결제창 값), ADR-112 |
| USER | `POST /bookings/{bookingId}/pay` | BKG-10 | `Idempotency-Key` 필수, 거절도 200. `method` TOSSPAY(완료 확인)·CARD(모의, `X-Payment-Mock-Key`가 맞을 때만 — k6·분석 Job) |
| 없음(서명) | `POST /webhooks/portone` | PAY-05 | 포트원 웹훅. Standard Webhooks 서명, 본문 불신 → 단건 조회 재검증 |
| USER | `POST /bookings/{bookingId}/cancel` | BKG-07 | |
| USER | `GET /users/me`, `PATCH /users/me` | — | 첫 호출 때 프로필 생성 |
| USER | `GET /users/me/bookings` | USR-04 | 페이징 |
| USER | `GET /users/me/preferences`, `PUT /users/me/preferences` | USR-05 | |
| ⚠️AI USER | `POST /ai/chat` | AI-01 | **ai**, 플래그 `APP_AI_ENABLED` 꺼지면 404(gateway). `{message 1~300자, sessionId?}` → `{sessionId, answer, path, columns, rows, rowCount, truncated, sql, presetId, asOf, llm, latencyMs, rejection}` — 모든 필드가 항상 있고 해당 없으면 null. `path` `CACHE_EXACT`·`CACHE_SEMANTIC`·`PRESET`·`NL2SQL`·`MY_BOOKINGS`·`REJECTED`·`OUT_OF_SCOPE`. `sessionId`는 서버가 발급하며, 이 사용자의 세션이 아니면 새 세션으로 시작한다. SQL 거부·범위 밖도 **200**(`REJECTED`는 `rejection {reason, message, object?}`). 409 `AI_QUERY_IN_PROGRESS` · 429 `AI_QUOTA_EXCEEDED`(`Retry-After`, `details[{field: quota, value: 10/min·100/day, reason: PER_MINUTE·PER_DAY}]`) · 503 `AI_LLM_UNAVAILABLE`(`details[{field: provider, value: anthropic, reason}]`)·`AI_SNAPSHOT_NOT_READY`·`SERVICE_UNAVAILABLE`(내 예매 공개 API 실패 `upstream: user`, 또는 ai 파드 없음). `Idempotency-Key` 없음(조회 전용) |
| ⚠️AI USER | `GET /ai/presets` | AI-04 | **ai**, 추천 질문 목록 `{items:[{presetId, title, example}]}` — 버튼을 누르면 `example`을 그대로 `POST /ai/chat`의 `message`로. 화면은 **챗봇 창을 열 때** 이 API를 불러(로그인·메인 진입 때 자동 호출 없음 — 런북 6-G-5) 404면 「준비 중」 안내 뒤 챗봇 버튼 숨김, 503이면 비활성 안내 |

### 내부 (`http://{service}:8080/internal/**`)

| 호출 | API | 타임아웃 | 실패 시 |
| --- | --- | --- | --- |
| booking → catalog | `GET /internal/screenings/{id}` | 1초 / 3초 | 선점·취소 503 |
| user → catalog | `POST /internal/screenings/summaries` (최대 100) | 1초 / 3초 | `screening: null` |
| user → catalog | `GET /internal/theaters?ids=` (최대 10) | 1초 / 3초 | 극장 검증 생략 |
| user → booking | `GET /internal/bookings?userId&status&page&size` | 1초 / 3초 | 503 `BOOKING_UNAVAILABLE` |
| catalog → booking | `GET /internal/screenings/seat-counts?ids=` (최대 100) | **300ms** 전체 | `remainingSeatsSource: TOTAL` |
| booking → payment | `POST /internal/payments` | 1초 / 3초, 타임아웃·연결 오류만 같은 paymentId로 1회 재시도 → 조회 | 503 `PAYMENT_RESULT_UNKNOWN` |
| booking → payment | `GET /internal/payments/{id}` | 1초 / 3초 | 결과 미확인 |
| booking → payment | `POST /internal/payments/{id}/verify` | 1초 / 3초, 타임아웃·연결 오류만 1회 재시도 → 조회 | 503 `PAYMENT_RESULT_UNKNOWN` (TOSSPAY 완료 확인) |
| payment → 포트원 | `GET /payments/{id}` · `POST /payments/{id}/cancel` (`api.portone.io`) | 1초 / 3초, 서킷 `portone` | verify·cancel 503 `SERVICE_UNAVAILABLE` |
| booking → payment | `POST /internal/payments/{id}/cancel` `{reason}` | 1초 / 3초 | 503 (화면 재시도) |

공통: 재시도 없음(위 1건 제외), Resilience4j 서킷(실패율 50%·10초), 호출 깊이 1, `X-Trace-Id`·`X-User-Id` 전달.

⚠️AI ai는 내부 API를 부르지 않는다. 공개 API(`GET /movies`·`/movies/{id}`·`/theaters`·`/screenings`, 사용자 토큰으로 `GET /users/me/bookings`)를 CloudFront 주소로 호출한다(ADR-84). 기존 계약 변경 없음.

## 3. 확정 결정 (10/22)

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
| 11 | 결제 | paymentId = booking 발급 UUID(포트원 paymentId로도 그대로), `PROCESSING` 상태, 거절도 200. **TOSSPAY**(포트원, 화면) = 준비 → 결제창 → 완료 확인(서버가 단건 조회로 금액 대조) · **CARD**(모의 0000·1111·9999) = 테스트·분석 전용, 호출자 제한 | API 명세서 10장 #3~5, ADR-13 → **ADR-112** |
| 12 | 예매 완료 알림 | 아웃박스 + 릴레이, `eventId` = 아웃박스 UUID(재전송 같은 값) | 10/21 확정 |
| 13 | SSE 적용 규칙 | `seq == lastSeq + 1`일 때만 적용, 빈틈이면 200ms 뒤 BKG-01 재조회 | API 명세서 v1.1 |
| 14 | 하위 호환 | 필드 추가만. 깨지는 변경은 새 엔드포인트·`.v2` 파일. 구현 전 설계 변경만 `contract-break-ok` 라벨 | ADR-32·55, 2.2.7 |

## 4. 바꾸는 방법

1. `contracts/` 파일을 먼저 고치는 PR (구현보다 먼저)
2. 로컬에서 `contracts/tools/check.sh` → CI `contract-check`(lint · 스키마·예시 · 하위 호환)
3. CODEOWNERS 승인(영향받는 담당 전원). 깨지는 변경이면 PR 본문 "계약 변경"에 이유
4. 구현 후(2단계): `pr-check`가 springdoc `/v3/api-docs` 생성본과 계약을 `oasdiff breaking`으로 비교 → Spring은 `springdoc.api-docs.version: openapi_3_1`

## 5. 이번 확정으로 생기는 하위 호환 ERROR (첫 PR에서 라벨 필요)

2.2.7 골격 대비 의도된 변경이다. 아직 구현 전이라 `contract-break-ok` + CODEOWNERS(한채연·남궁일) 승인으로 한 번에 넣는다.

| 파일 | 변경 |
| --- | --- |
| `payment.internal.openapi.yaml` | 결제·취소 요청 본문 필수 추가, `paymentId` 경로 파라미터 `format: uuid`(형식 오류는 400 `VALIDATION_FAILED`) |
| `error.v1.json` (→ 모든 OpenAPI 오류 응답) | `details` 필수, `traceId` null 불가 + pattern |
| `booking.confirmed.v1.json` | payload 필수 3개, `userId` uuid, `traceId` pattern |
| `seat-event.v1.json` | `traceId` pattern |

<노션 반영 필요> API 명세서 v1.1의 1-3 "페이징 없는 목록은 JSON 배열" → `{items}`, 1-4 "details는 선택(배열 또는 객체)" → 항상 배열, 3-2 내부 회차 API `seatIds[]` 삭제, 5-1 프로필 생성 실패 → 503 `IDENTITY_UNAVAILABLE`, 10장 #1·#2·#6·#10 결정 표시, 부록 B에 OpenAPI 3.1·contract-check

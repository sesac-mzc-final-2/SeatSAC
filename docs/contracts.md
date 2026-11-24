# 서비스 계약 요약 (contracts)

> 기준 2026-10-21. 원본은 `contracts/` 파일이고 이 문서는 목록과 확정 결정만 담는다. 파일과 이 문서가 다르면 **파일이 맞다**.
> 근거: API 명세서 v1.1(10/21), 2.2.4 Catalog·User API 명세 v0.3(10/21), 계약 대조표(15번), 10/22 회의록2 결정(ADR-54·55).
> ⚠️AI 11/5 AI 재도입(ADR-82) · 11/6 WBS 2.2.8: `openapi/ai.openapi.yaml` 외부 2개 + 응답 예시 5종 **추가**(이 파일이 기준, 구현은 3.4.x). 상세는 API 명세서 v1.4 15장.
> 🆕 11/23 MSA 고도화(노션 ADR-114~125): 체크아웃 saga·coupon·관리자 `/ops`·AI 통계·메시지 16종 **추가**. 상세는 노션 「API 명세서 v1.5 (MSA 고도화)」 21~26장. 계약 파일은 WBS 6.0.1 PR에서 만든다 — 이 문서의 🆕 행은 그 PR의 목록이다(모두 추가만, `contract-break-ok` 불필요).

---

## 1. 파일

| 파일 | 내용 | 담당 (CODEOWNERS) |
| --- | --- | --- |
| `openapi/catalog.openapi.yaml` | 외부 6개 (조회만 — 관리자 API는 11/14 삭제) | 한채연 |
| `openapi/booking.openapi.yaml` | 외부 8개 (좌석 현황·SSE·선점·해제·결제 준비·결제·조회·취소) + 🆕 3개 (체크아웃 시작·조회·확인) | 남궁일 |
| `openapi/payment.openapi.yaml` | 외부 1개 (포트원 웹훅 — 인증 없음, 서명 검증) | 남궁일 |
| `openapi/user.openapi.yaml` | 외부 5개 (프로필 조회·수정, 예매 내역, 선호 조회·저장). 🆕 `GET /users/me`에 `grade` | ~~한채연~~ 🆕 김종식(11/24) |
| 🆕 `openapi/coupon.openapi.yaml` | 외부 3개 (현재 이벤트·발급·내 쿠폰) | ~~한채연~~ 🆕 김종식(11/24) |
| 🆕 `openapi/ops.openapi.yaml` | 외부 8개 (`/ops` — 서비스 상태·정지·가동, 리포트 날짜·생성·상태, AI 범위 밖 통계, 쿠폰 이벤트 생성) | 김종식 |
| ⚠️AI `openapi/ai.openapi.yaml` | 외부 2개 (챗봇 질문 `POST /ai/chat`, 추천 질문 목록 `GET /ai/presets`). 🆕 6.4.7: 응답 `path`에 `RAG`, 필드 `sources`(근거 문서 `[{id, title}]`, 그 밖의 경로는 null) **추가만** — `CLARIFY`·`HOLD_*`를 넣을 때와 같은 방식, 6.0.1 계약 PR에 함께(⏳ `contract-check`로 확인) | 김서현 · 한채연 |
| ⚠️AI `openapi/examples/ai/*.json` | 챗봇 응답 예시 5종(CACHE_SEMANTIC·PRESET·NL2SQL·MY_BOOKINGS·REJECTED) + CLARIFY(#140) + 좌석 잡기 3종(HOLD_CONFIRM·HOLD_RESULT·HOLD_GUIDE) + 문맥 이어받기 `chat-context-carry.json`(3.4.22) + 🆕 이용 안내 `chat-rag.json`(6.4.7). OpenAPI Example Object `{ summary, value }` — `value`가 fixture, YAML이 `$ref`로 참조해 Redocly가 스키마로 검증 | 김서현 |
| `openapi/internal/catalog.internal.openapi.yaml` | 내부 3개 | 한채연 |
| `openapi/internal/booking.internal.openapi.yaml` | 내부 2개 | 남궁일 |
| `openapi/internal/payment.internal.openapi.yaml` | 내부 4개 (생성·조회·확인·취소) | 남궁일 |
| 🆕 `openapi/internal/coupon.internal.openapi.yaml` | 내부 2개 (이벤트 생성·목록, 호출자 ops-api) | ~~한채연~~ 🆕 김종식(11/24) |
| 🆕 `openapi/internal/ai.internal.openapi.yaml` | 내부 1개 (범위 밖 질문 통계, 호출자 ops-api) | 김서현 |
| `events/seat-event.v1.json` | Valkey Pub/Sub → SSE | 남궁일 |
| `events/booking.confirmed.v1.json` | SQS 예매 완료 알림. 🆕 SNS `booking-events`로도 발행(user 복제본), 선택 필드 `amount`·`discountAmount` 추가 | 남궁일 (소비 Lambda 김종식, user ~~한채연~~ 🆕 김종식) |
| 🆕 `events/booking.canceled.v1.json` | 예매 취소·만료·결제 실패(`reason`) | 남궁일 |
| 🆕 `events/screening.changed.v1.json` · `movie.changed.v1.json` | catalog 변경(`version`, `deleted`) → ai·(booking) 복제본 | 한채연 |
| 🆕 `events/coupon.{reservation-requested,use-requested,release-requested}.v1.json` | saga 명령 → coupon | 남궁일(보냄) · ~~한채연~~ 🆕 김종식(받음) |
| 🆕 `events/payment.{preparation-requested,charge-requested,verification-requested}.v1.json` | saga 명령 → payment | 남궁일 |
| 🆕 `events/coupon.{reserved,reservation-rejected}.v1.json` · `payment.{prepared,preparation-failed,approved,declined,result-unknown}.v1.json` | saga 결과 → booking | 한채연·남궁일 |
| `schemas/error.v1.json` | 공통 오류 응답 | 전원 |
| `*/examples/**` | 예시 메시지 = 서비스 테스트 fixture | 각 담당 |

합계: 외부 22(⚠️AI ai 2개·포트원 웹훅 1개 포함) · 내부 9 · 이벤트 2. 🆕 11/23 이후 외부 **36**(booking 3·coupon 3·ops 8 추가) · 내부 **12**(coupon 2·ai 1) · 메시지 **18**(이벤트 4 + 명령 6 + 결과 7 + 좌석 이벤트 1).

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
| 없음 | `GET /screenings/{screeningId}/seats` | BKG-01 | **booking**, Gateway 1순위 라우트 |
| 없음 | `GET /screenings/{screeningId}/seats/stream` | BKG-05 | SSE, **booking** |
| USER | `POST /seats/hold` | BKG-02 | `Idempotency-Key` 필수 |
| USER | `GET /bookings/{bookingId}` | — | 결제 결과 재확인 |
| USER | `DELETE /bookings/{bookingId}/hold` | BKG-03 | |
| USER | `POST /bookings/{bookingId}/payments` | BKG-10 | `Idempotency-Key` 필수. TOSSPAY 결제 준비(paymentId + 포트원 결제창 값), ADR-112 |
| USER | `POST /bookings/{bookingId}/pay` | BKG-10 | `Idempotency-Key` 필수, 거절도 200. `method` TOSSPAY(완료 확인)·CARD(모의, `X-Payment-Mock-Key`가 맞을 때만 — k6·분석 Job) |
| 없음(서명) | `POST /webhooks/portone` | PAY-05 | 포트원 웹훅. Standard Webhooks 서명, 본문 불신 → 단건 조회 재검증 |
| USER | `POST /bookings/{bookingId}/cancel` | BKG-07 | |
| 🆕 USER | `POST /bookings/{bookingId}/checkout` | BKG-10 | **체크아웃 saga**(ADR-116). `Idempotency-Key` 필수, `{method: TOSSPAY\|CARD, couponIssueId?}` → 202 `{checkoutId, state, pollAfterMs}`. 플래그 `APP_CHECKOUT_SAGA_ENABLED`(기본 `false` → 404). 한 예매에 동기 결제와 섞으면 409 `PAYMENT_PATH_CONFLICT` |
| 🆕 USER | `GET /bookings/{bookingId}/checkout` | BKG-10 | polling(1초, 최대 30초). `state` STARTED → COUPON_RESERVING → PAYMENT_PREPARING → READY_TO_PAY(포트원 결제창 값) → VERIFYING → CONFIRMED / COMPENSATING → FAILED / EXPIRED, 🆕 / COUPON_REJECTED(쿠폰만 거절 — 예매·좌석은 그대로, 최초 선점 5분 안에 다른 쿠폰이나 쿠폰 없이 `POST …/checkout`을 새 `Idempotency-Key`로 다시). CARD는 결제 준비 없이 CHARGING. `amount {original, discount, final}`, `failure {code, reason}`. 결제 결과 불명(`CHARGING`·`VERIFYING`)은 보상하지 않고 재확인, ~~선점 만료까지 모르면 EXPIRED~~ 🆕 선점 만료 뒤에는 예매 상태를 따라 닫힌다(기존 만료 처리가 유예 2분 뒤 payment 결과로 정함 — 결과 불명이면 대략 7~9분). 진행 중이면 `DELETE /bookings/{id}/hold`도 409 `CHECKOUT_IN_PROGRESS`, `GET /bookings/{id}`에 `checkout {checkoutId, state}` 추가 |
| 🆕 USER | `POST /bookings/{bookingId}/checkout/confirm` | BKG-10 | 결제창이 닫힌 뒤 `{paymentId}` → 202. `READY_TO_PAY`가 아니면 409 `CHECKOUT_NOT_READY` |
| 🆕 USER | `GET /coupons/events/current` | CPN-01 | **coupon**, `{eventId, title, discountRate, startsAt, endsAt, totalQuantity, remainingQuantity, eligibleGrade, state, myIssue}` — NORMAL 사용자도 200(화면이 「VIP 전용」 안내) |
| 🆕 USER | `POST /coupons/events/{eventId}/claim` | CPN-02 | VIP만(`X-User-Grade`), 선착순, `Idempotency-Key` 필수 → 201. 403 `COUPON_NOT_ELIGIBLE`, 409 `COUPON_SOLD_OUT`·`COUPON_ALREADY_CLAIMED`·`COUPON_EVENT_NOT_OPEN` |
| 🆕 USER | `GET /coupons/me` | CPN-03 | `{items}` |
| 🆕 ADMIN | `/ops/**` 8개 | OPS-20~24 | **ops-api**, gateway 라우트 아님(CloudFront `/ops/*` → ALB). ops-api가 토큰의 `cognito:groups` ∋ `ADMIN` 확인. 목록은 API 명세서 v1.5 25장 |
| USER | `GET /users/me`, `PATCH /users/me` | — | 첫 호출 때 프로필 생성. 🆕 응답에 `grade`(`X-User-Grade`, 저장 안 함) |
| USER | `GET /users/me/bookings` | USR-04 | 페이징. 🆕 `APP_BOOKING_HISTORY_MODE=local`이면 `booking_history` 복제본(응답 모양 그대로) |
| USER | `GET /users/me/preferences`, `PUT /users/me/preferences` | USR-05 | |
| ⚠️AI USER | `POST /ai/chat` | AI-01 | **ai**, 플래그 `APP_AI_ENABLED` 꺼지면 404(gateway, 유효한 토큰으로 인증됐을 때 — 토큰이 없거나 잘못되면 인증이 라우팅보다 먼저라 401). `{message 1~300자, sessionId?, continueFrom?}` → `{sessionId, answer, path, columns, rows, screeningIds, rowCount, truncated, sql, presetId, context, asOf, llm, latencyMs, rejection, choices, cache, seatHold}` — 모든 필드가 항상 있고 해당 없으면 null. `screeningIds`는 `rows`와 같은 순서의 회차 ID(행 하나가 회차 하나일 때만, 집계 결과는 null) — 화면이 좌석 선택 화면 링크를 만든다(ai는 화면 경로를 만들지 않음). `cache {entryId, stored, similarity}`는 이 답과 이어진 캐시 항목(`stored: true` = Claude가 답하고 저장함, `false` = 그 항목에서 나온 답, `similarity`는 `CACHE_SEMANTIC`일 때만 0~1) — 데모 화면이 같은 대화에서 `entryId`가 같은 두 답을 짝지어 실제로 아낀 토큰·시간을 보여 준다(`entryId`는 ai 파드가 다시 뜨면 바뀐다). 저장하지 않은 답은 null이고 질문 글자는 싣지 않는다. `path` `CACHE_EXACT`·`CACHE_SEMANTIC`·`PRESET`·`NL2SQL`·`MY_BOOKINGS`·`REJECTED`·`OUT_OF_SCOPE`·`CLARIFY`(+ 좌석 잡기 플래그 `APP_AI_SEAT_HOLD_ENABLED`(기본 켜짐)가 켜져 있을 때만 `HOLD_CONFIRM`·`HOLD_RESULT`·`HOLD_GUIDE`, `seatHold {screeningId, bookingId, seatLabels, amount, expiresAt, confirmExpiresAt, reason}` — 그 밖은 null). `sessionId`는 서버가 발급하며, 이 사용자의 세션이 아니면 새 세션으로 시작한다. **문맥 이어받기(3.4.22)**: `context {id, date, theater, movie, time, timeDirection, label, inherited}`는 그 답이 실제로 조회한 조건(실행한 SQL에서 하나로 확정된 값만, 없으면 null. `time` 'HH:MM'·`timeDirection` `after`·`before`는 「○시 이후/이전」 하나일 때만 — 시각 범위 두 개·`start_hour` 표현·심야/조조 같은 고정 범위가 있는 답은 `context`가 null). 화면은 답 아래 영화 버튼을 누를 때 그 답의 `context.id`를 `continueFrom`으로 보내고, ai는 그 날짜·극장·시각에 고른 영화를 더해 Claude 없이 실행한다(`inherited`에 이어받은 칸 — 화면은 「`label` 기준」·「조건 지우기」 표시). 모르는·만료된 `continueFrom`은 오류 없이 무시(`inherited` 빈 배열), `continueFrom` 없는 직접 친 질문은 이어받지 않는다. SQL 거부·범위 밖·되묻기도 **200**(`REJECTED`는 `rejection {reason, message, object?}`, `CLARIFY`는 `choices [{label, message}]` 버튼 0~7개 — 누르면 `message`를 같은 `sessionId`로 보낸다). 409 `AI_QUERY_IN_PROGRESS` · 429 `AI_QUOTA_EXCEEDED`(`Retry-After`, `details[{field: quota, value: 10/min·100/day, reason: PER_MINUTE·PER_DAY}]`) · 503 `AI_LLM_UNAVAILABLE`(`details[{field: provider, value: anthropic, reason}]`)·`AI_SNAPSHOT_NOT_READY`·`SERVICE_UNAVAILABLE`(내 예매 공개 API 실패 `upstream: user`, 또는 ai 파드 없음). `Idempotency-Key` 없음(조회 전용. 좌석 잡기에서도 화면은 보내지 않는다 — ai가 확인 요청 때 만든 키로 `POST /seats/hold`를 부른다) |
| ⚠️AI USER | `GET /ai/presets` | AI-04 | **ai**, 추천 질문 목록 `{items:[{presetId, title, example}]}` — 버튼을 누르면 화면이 빈칸(시각·개수는 입력창에서 고치기, 극장·영화·날짜·등급은 고르는 버튼)을 사용자가 정하게 한 뒤 질문 문장을 `POST /ai/chat`의 `message`로 보낸다. 빈칸이 없는 질문과 화면이 모르는 프리셋은 `example`을 그대로. 화면은 **챗봇 창을 열 때** 이 API를 불러(로그인·메인 진입 때 자동 호출 없음 — 런북 6-G-5) 404면 「준비 중」 안내 뒤 챗봇 버튼 숨김, 503이면 비활성 안내 |

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
| booking → payment | `POST /internal/payments/{id}/verify` | **1초 / 10초**(`APP_SERVICES_PAYMENT_PG_READ_TIMEOUT` — 안에서 포트원을 부른다), 재호출 없음 | 503 `PAYMENT_RESULT_UNKNOWN` (TOSSPAY 완료 확인 — 화면의 `GET /bookings/{id}` 재조회가 verify를 다시 부른다) |
| booking → payment | `POST /internal/payments/{id}/cancel` `{reason}` | **1초 / 10초**(같은 설정 — PORTONE은 포트원 취소 + 대조) | 503 (화면 재시도) |
| 🆕 ops-api → coupon | `POST /internal/coupon-events`, `GET /internal/coupon-events` | 1초 / 3초 | 관리자 화면 오류 표시 |
| 🆕 ops-api → ai | `GET /internal/ai/unmet-demand/stats?from&to&limit` | 1초 / 3초 | 관리자 화면 「통계 없음」 |
| payment → 포트원 | `GET /payments/{id}` · `POST /payments/{id}/cancel` (`api.portone.io`) | 1초 / 3초, GET만 1회 재호출(최대 약 8초 < booking 10초 < gateway 15초), 서킷 `portone` | verify·cancel 503 `SERVICE_UNAVAILABLE`(PORTONE_UNAVAILABLE) |

공통: 재시도 없음(위 1건 제외), Resilience4j 서킷(실패율 50%·10초), 호출 깊이 1(예외: booking → payment → 포트원, ADR-112 — 위 시간 예산), `X-Trace-Id`·`X-User-Id` 전달.

🆕 saga 명령·결과는 HTTP가 아니라 SQS다(위 표에 없음, 26장). 동기 내부 호출은 지우지 않는다 — 비동기 경로가 꺼져 있거나(플래그) 복제본에 없을 때의 대체 경로로 남긴다(ADR-116).

⚠️AI ai는 내부 API를 부르지 않는다. 공개 API(`GET /movies`·`/movies/{id}`·`/theaters`·`/screenings`, 사용자 토큰으로 `GET /users/me/bookings`)를 CloudFront 주소로 호출한다(ADR-84). 기존 계약 변경 없음.

⚠️AI 채팅으로 좌석 잡기(ai 플래그 `APP_AI_SEAT_HOLD_ENABLED` 기본 `true` — `false`로 끈다, 3.4.19 — ADR 번호는 노션 등록 후 확정): 켜져 있을 때 ai가 공개 API를 4개 더 부른다 — `GET /screenings/{screeningId}/seats` · `GET /auditoriums/{auditoriumId}/layout`(인증 없음, 좌석 이름 → `seatId`), 그리고 **사용자가 확인 버튼을 누른 뒤에만** `POST /seats/hold`(요청의 `Authorization` 그대로 + ai가 만든 `Idempotency-Key`, 시간 초과·연결 오류 시 같은 키로 1회 재시도). ai가 쓰기 API를 부르는 유일한 경우다. 네 번째는 선점에 성공한 그 확인이 다시 왔을 때만 부르는 `GET /bookings/{bookingId}`(요청의 `Authorization` 그대로, 읽기 — 단 booking이 응답 전에 결제 결과를 반영할 수 있다, `booking.openapi.yaml`). booking·catalog 계약은 바뀌지 않는다.

## 3. 확정 결정 (10/22)

| # | 항목 | 결정 | 근거 |
| --- | --- | --- | --- |
| 1 | OpenAPI 버전 | **3.1.0** (error·이벤트 스키마를 JSON Schema 그대로 `$ref`) | 2.2.7 |
| 2 | userId | Cognito `sub` 문자열(`format: uuid`), DB `CHAR(36)` | ADR-37, ERD 6차 |
| 3 | 프로필 email·name | user가 Access Token으로 Cognito `GetUser`. `NotAuthorizedException`은 **401 `UNAUTHORIZED`**(`details[].reason` `TOKEN_REJECTED`), 그 밖의 실패는 **503 `IDENTITY_UNAVAILABLE`** | 2.2.4 v0.3, ADR-54 |
| 4 | `GET /internal/theaters` | **만든다** (USR-05 극장 검증, Catalog 실패 시 검증 생략) | API 명세서 10장 #6 |
| 5 | 범위 | 위 29개 + ⚠️AI ai 외부 2개 = 31개(관리자 API 3개는 11/14 삭제 — 구현된 적 없음 · ADR-112: 결제 준비·포트원 웹훅 외부 2개, verify 내부 1개 추가). gateway 요청 수 제한(429)은 없음 — ai의 429 `AI_QUOTA_EXCEEDED`는 ai 앱 안 비용 보호 | ADR-62·82 |
| 6 | booking.confirmed v1 payload | `screeningId`·`seatCount`·`confirmedAt` **필수** (발행자 booking 하나, 구현 전) | API 명세서 9-2, ADR-55 |
| 7 | traceId 형식 | 스키마에 `pattern: ^[0-9a-f]{32}$` (오류·좌석 이벤트·알림 공통), 오류의 traceId는 null 불가 | GW-03 10/21, ADR-55 |
| 8 | 오류 `details` | **항상 배열**(필수, 없으면 `[]`), 원소 `{field, value?, reason?}` | 2.2.4 v0.3, API 명세서 1-4 |
| 9 | 페이징 없는 목록 | `{items}` (`/theaters`, `/screenings`, 내부 summaries·theaters·seat-counts) | 2.2.4 D8, ADR-54 |
| 10 | 내부 회차 API의 좌석 ID | **삭제**. booking은 CAT-04 배치도를 (상영관, 버전) 키로 파드 캐시 | 2.2.4 v0.3 |
| 11 | 결제 | paymentId = booking 발급 UUID(포트원 paymentId로도 그대로), `PROCESSING` 상태, 거절도 200. **TOSSPAY**(포트원, 화면) = 준비 → 결제창 → 완료 확인(서버가 단건 조회로 금액 대조) · **CARD**(모의 0000·1111·9999) = 테스트·분석 전용, 호출자 제한 | API 명세서 10장 #3~5, ADR-13 → **ADR-112** |
| 12 | 예매 완료 알림 | 아웃박스 + 릴레이, `eventId` = 아웃박스 UUID(재전송 같은 값) | 10/21 확정 |
| 13 | SSE 적용 규칙 | `seq == lastSeq + 1`일 때만 적용, 빈틈이면 200ms 뒤 BKG-01 재조회 | API 명세서 v1.1 |
| 14 | 하위 호환 | 필드 추가만. 깨지는 변경은 새 엔드포인트·`.v2` 파일. 구현 전 설계 변경만 `contract-break-ok` 라벨 | ADR-32·55, 2.2.7 |
| 15 | 🆕 비동기 결제 | 기존 `/payments`·`/pay`는 그대로 두고 **새 자원 `checkout`**(202 + polling)으로 추가. 상태·결과는 `GET /bookings/{id}/checkout` 한 곳 | 노션 ADR-116 (11/23) |
| 16 | 🆕 메시지 envelope | 기존 envelope + `correlationId`(saga = `checkoutId`). 소비자는 모르는 필드를 무시, `processed_message`로 한 번만 | ADR-117 |
| 17 | 🆕 VIP·관리자 | VIP = Cognito 그룹 → gateway `X-User-Grade`, 관리자 = Cognito `ADMIN` 그룹 → ops-api 직접 검증(`/api/admin/**`은 계속 없음) | ADR-118·119 |
| 18 | 🆕 금액 | 쿠폰 할인은 booking이 결제 준비 전에 확정(`amount {original, discount, final}`), 포트원 결제창은 `final` | ADR-118 |

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

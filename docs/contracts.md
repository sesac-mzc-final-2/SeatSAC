# 서비스 계약 요약 (contracts)

> 기준 2026-10-21. 원본은 `contracts/` 파일이고 이 문서는 목록과 확정 결정만 담는다. 파일과 이 문서가 다르면 **파일이 맞다**.
> 근거: API 명세서 v1.1(10/21), 2.2.4 Catalog·User API 명세 v0.3(10/21), 계약 대조표(15번), 10/22 회의록2 결정(ADR-54·55).

---

## 1. 파일

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
| USER | `POST /bookings/{bookingId}/pay` | BKG-10 | `Idempotency-Key` 필수, 거절도 200 |
| USER | `POST /bookings/{bookingId}/cancel` | BKG-07 | |
| USER | `GET /users/me`, `PATCH /users/me` | — | 첫 호출 때 프로필 생성 |
| USER | `GET /users/me/bookings` | USR-04 | 페이징 |
| USER | `GET /users/me/preferences`, `PUT /users/me/preferences` | USR-05 | |

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
| booking → payment | `POST /internal/payments/{id}/cancel` `{reason}` | 1초 / 3초 | 503 (화면 재시도) |

공통: 재시도 없음(위 1건 제외), Resilience4j 서킷(실패율 50%·10초), 호출 깊이 1, `X-Trace-Id`·`X-User-Id` 전달.

## 3. 확정 결정 (10/22)

| # | 항목 | 결정 | 근거 |
| --- | --- | --- | --- |
| 1 | OpenAPI 버전 | **3.1.0** (error·이벤트 스키마를 JSON Schema 그대로 `$ref`) | 2.2.7 |
| 2 | userId | Cognito `sub` 문자열(`format: uuid`), DB `CHAR(36)` | ADR-37, ERD 6차 |
| 3 | 프로필 email·name | user가 Access Token으로 Cognito `GetUser`. `NotAuthorizedException`은 **403 `TOKEN_REJECTED`**, 그 밖의 실패는 **503 `IDENTITY_UNAVAILABLE`** | 2.2.4 v0.3, ADR-54 |
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

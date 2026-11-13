# 결제 경로 매트릭스 — 어디서 토스페이(포트원)를 쓰고 어디서 모의(MOCK)를 쓰나

> 기준 2026-11-13, ADR-112. 노션 ADR-112 본문 「5. MOCK 범위」와 같게 유지한다. 새 테스트·시연 경로가 생기면 이 표에 먼저 추가한다.

## 원칙

- **사용자 화면은 토스페이만** 보여 준다. 사람이 끼는 확인(통합 확인·데모)은 전부 실제 결제창으로 한다.
- **모의 결제(`method=CARD`)는 사람이 없는 자동 경로에만** 쓴다. booking은 CARD를 두 호출자에게만 허용한다 — 그 밖은 400 `VALIDATION_FAILED`(reason `MOCK_NOT_ALLOWED`).

| 호출자 | 어떻게 | 비고 |
| --- | --- | --- |
| 기계 (k6 Job · 블루/그린 분석 Job) | 요청 헤더 `X-Payment-Mock-Key` = booking Secret `APP_PAYMENT_MOCK_KEY` | gateway는 `X-User-*`·`X-Trace-Id`만 지우므로 통과. k6 Job은 토큰처럼 Secret 마운트(`run-in-cluster.sh`), 분석 Job은 AnalysisTemplate env(Secret 참조). 키가 설정에 없으면 기계 경로 자체가 꺼진다 |

## 매트릭스

| 경로 | 결제 포함 | 수단 | 이유 · 비고 |
| --- | --- | --- | --- |
| k6 `contention`(같은 좌석 100명 동시 선점) · `user-hold` · `browse` · SSE 지연(`sse_lag.py`) | ✗ | — | 결제 없음. **제한 없음** |
| k6 `deploy`(배포 중 부하: 선점 → 결제 → 취소) | ✓ | **MOCK**(기계 키) | 결제창은 자동화할 수 없다. `booking_payment_call_total` 분모·부하 중 결제 무오류 측정 |
| 블루/그린 분석 `analysis/smoke/booking-smoke.js` 결제 경로(`SMOKE_PAY_SCREENING_ID`) | ✓ | **MOCK**(기계 키) | preview 파드에 사람이 없다. 롤백 조건(timeout·error ≤ 5%) 분모 확보 |
| 자동 롤백 데모(5.1.1) 실패 버전 | ✓ | MOCK(k6 deploy) | 위와 같음. 실패는 gitops 설정으로 의존 서비스 주소를 깨뜨려 만든다 |
| payment·booking 단위/Testcontainers 테스트 | ✓ | 토스페이 경로 + **포트원 Stub**(가짜 REST: PAID·FAILED·READY·금액 불일치·웹훅 서명) · MOCK 경로 테스트도 유지 | CI에 외부 호출 없음. 두 경로 모두 테스트 |
| 4.1.1 통합 확인(staging, 사람) — 승인·사용자 취소·승인 뒤 만료(결제창 5분 대기)·BKG-07 환불·웹훅 수신·브라우저 금액 위변조 → 서버 취소 | ✓ | **토스페이**(테스트 채널) | 사람이 결제창을 누른다. PG 거절만 테스트 채널에서 못 만들면 Stub 테스트로 대신 |
| 「지연 → 503 결과 미확인 → 재조회」 재현 | ✓ | **MOCK**(9999, 기계 키) | 포트원에서 의도적 지연을 만들 수 없다. 데모에서는 시연하지 않고 테스트로만 |
| 실시간 좌석 데모(5.1.2) 장면 5 결제 | ✓ | **토스페이**(발표자 계정·토스 앱) | 거절(0000)은 「결제창에서 사용자 취소」로 대체. 리허설(11/15)에서 확정 |
| 로컬 개발(Compose) | ✓ | 포트원 Stub(`APP_PORTONE_API_BASE_URL`로 가리킴) 또는 MOCK | Secret 없이 돌릴 수 있게. 웹훅은 로컬에 못 온다(단건 조회 폴링으로 대체) |

## 외부 PG라서 생기는 규칙 (다른 팀 포트원 장애 기록에서, `services/docs/pg-md/`)

- 포트원 취소 API에는 **멱등 키가 없다** → 취소는 전액만, `currentCancellableAmount`를 명시, 응답을 못 받으면 재호출 대신 `GET`으로 대조
- 취소 응답 `REQUESTED`는 완료가 아니다 → 503 `CANCEL_PENDING`, 웹훅·스윕이 확정
- **다시 요청할 주체가 없는 환불**(금액 불일치·늦은 결제)은 「환불 미완료」를 DB에 먼저 남기고(FAILED + `pg_transaction_id` + `canceled_at` null) 1분 스윕이 끝까지
- **웹훅은 대조만** — 포트원에 아무것도 보내지 않는다
- `@Retry`는 쓰지 않는다(서킷 폴백과 섞이면 재시도가 안 걸림) — GET만 코드로 1회 재호출
- 화면에 CSP를 넣게 되면 `https://*.portone.io https://*.iamport.co`를 `connect-src`·`frame-src`에

## 토스페이로 바뀌면서 데모에서 빠지는 것

| 항목 | 판단 |
| --- | --- |
| 장면 5 ② 거절 카드 `0000` | 「결제창 사용자 취소 → 즉시 해제」로 대체 |
| 지연 카드 `9999` → 503 → 재조회 시연 | 데모에서 삭제, 테스트로만 유지 |
| 같은 paymentId 재호출 → 결제 1건 시연 | 데모에서 삭제, 테스트로만 유지(포트원은 가맹점 채번 ID라 같은 ID 재시도를 거부) |
| 테스트 카드 버튼(`CardForm`) | 화면에서 삭제(관리자 역할 제거 #119·#120). 모의 결제는 k6·분석 Job이 키 헤더로만 쓴다 |
| 결제 결과의 `cardLast4` 표시 | TOSSPAY는 null → 결제 수단 「토스페이」 표시 |

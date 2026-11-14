# frontend — React (Vite) · FSD

- 빌드 결과는 S3 + CloudFront (OAC). 컨테이너 없음. `frontend-deploy` 워크플로가 업로드·무효화한다.
- API는 같은 도메인의 `/api/*` → `VITE_API_BASE_URL=/api`. 로컬은 Vite 프록시 `/api` → `http://localhost:8080`(gateway).
- 로그인은 Cognito Hosted UI (PKCE), `react-oidc-context`. Access Token 을 `Authorization: Bearer` 로. 토큰은 메모리에만(ADR-63).
  OIDC scope 는 `openid email profile aws.cognito.signin.user.admin` — 프로필 행이 없을 때(첫 로그인) user 서비스가 같은 토큰으로 Cognito `GetUser` 를 부르는데, 마지막 범위가 없으면 거절돼 `GET /users/me` 가 401 `TOKEN_REJECTED` 로 끝난다. 행이 있으면 Cognito 를 부르지 않는다.
  로그아웃은 메모리 토큰 삭제 → 잡아 둔 refresh token 을 Cognito `/oauth2/revoke` 로 폐기(직접 fetch, 5초 제한) → Hosted UI `/logout` 순서다.
- SSE: 연결 → BKG-01 스냅샷 → seq 보다 큰 이벤트만 적용. 재연결 때마다 스냅샷 다시. (좌석 화면, 남궁일)

## 실행

```bash
npm ci
npm run dev                 # http://localhost:5173, /api → localhost:8080 (gateway 가 떠 있어야 한다)
npm run dev:mock            # 백엔드 없이: 목 catalog·user 서버(8089) + Vite(/api → 8089) 한 번에
DEV_SESSION=1 npm run dev:mock   # + 가짜 로그인(개발 서버 전용) → 마이페이지(/me)·예매 흐름까지 Cognito 없이
npm run mock                # 목 서버만 (DEV_API_PROXY=http://localhost:8089 npm run dev 와 함께)
npm run check               # typecheck + eslint + FSD 검사(steiger) + vitest
npm run build               # tsc -b && vite build → dist/
```

`mock/server.mjs` 는 catalog·user 계약 모양으로 응답하는 가짜 데이터(영화 6편·극장 4곳·하루 편성·예매 내역 23건·선호, 롯데시네마 수집 데이터·포스터 없음)다.
`/api/users/**`·`/api/bookings/**` 는 `Authorization: Bearer` 가 있으면 누구든 통과시킨다(JWT 검증 없음). 예매 취소(BKG-07)는 메모리 상태를 바꾼다.
`DEV_SESSION=1` 은 Vite `define`(`__DEV_SESSION__`)으로만 들어가 `vite build` 결과에는 절대 켜지지 않는다 — Cognito 값이 비어 있을 때만 가짜 세션이 된다.
예매 ID 9001 은 회차 정보 없음(`screening: null`) 카드다.
오류 화면 재현: 영화 ID 999 는 404 라 `/movies/999` 로 들어가면 「영화를 찾을 수 없어요」가 보이고,
`MOCK_FAIL_PATH=/api/screenings npm run dev:mock` 처럼 띄우면 그 경로가 항상 503(`MOCK_FAIL_STATUS` 로 500 도 가능)을 돌려줘
상영시간표의 오류 블록·문의 코드·공통 토스트를 볼 수 있다. API 를 직접 부를 때는 `?fail=503` 을 붙여도 된다. 빌드에는 포함되지 않는다.

AI 챗봇(3.3.7, `ai.openapi.yaml`)도 목이 응답한다: 추천 질문 5개, 같은 문장을 다시 물으면 `CACHE_EXACT`(토큰 0), "내 예매" → `MY_BOOKINGS`,
"비율·매진" → `NL2SQL`, "테이블" → `REJECTED`, "날씨" → `OUT_OF_SCOPE`. 질문에 `#409`·`#429`·`#429day`·`#503`·`#timeout`·`#snapshot` 을 넣으면 그 오류로 답한다.
`MOCK_AI=off` 는 `/api/ai/**` 404(gateway 플래그 off → 버튼 숨김), `MOCK_AI=down` 은 503(ai 파드 없음 → 창 안 비활성 안내).

Node 는 **22.22 이상**(`engines`). 테스트 의존성 jsdom 30 이 그 아래를 지원하지 않으며, CI(`pr-check` `frontend` job)·`frontend-deploy` 는 Node 24 를 쓴다.

환경 변수는 `.env.example` 참고 (전부 공개 값, `VITE_` 는 빌드에 포함된다). `VITE_COGNITO_*` 가 비어 있으면 로그인 버튼이
비활성화되고 조회 화면만 동작한다. 값은 `docs/service-runtime-contract.md` 3장 frontend 표.

## 구조 — Feature-Sliced Design

`src/` 는 FSD 계층으로 나뉜다. 위 계층만 아래 계층을 import 하고(app → pages → widgets → features → entities → shared),
슬라이스는 `index.ts` 공개 API로만 쓴다. `npm run lint:fsd`(steiger)가 검사한다.

| 계층 | 슬라이스 | 내용 |
| --- | --- | --- |
| `app` | `providers` · `routes` · `styles` | 진입점, 토스트·쿼리·인증(Cognito) 프로바이더, 라우터, 전역 CSS 토큰 |
| `pages` | `home`(W1) · `movie-detail`(W2) · `showtimes`(W3) · `my-page`(W8) · `credits`(W10) · `auth-callback`(W7) · `not-found` | 화면 한 장 = 슬라이스 하나 |
| `widgets` | `page-layout`(헤더·푸터) · `home-hero` · `movie-catalog` · `showtime-board` · `booking-history`(USR-04) · `preferences-panel`(USR-05) | 여러 엔티티·기능을 합친 블록 |
| `features` | `ai-chat`(로그인 후 우하단 AI 챗봇, AI-01·AI-04) · `login`(헤더 로그인·`LoginGate`) · `showtimes-filter` · `enter-seat-select` · `booking-steps` · `cancel-booking`(BKG-07 확인 대화상자) · `edit-preferences`(극장 순위·시간대·장르 편집·저장) | 사용자 행동 |
| `entities` | `ai-chat`(`ai.openapi.yaml` 타입·API·오류 문구) · `movie` · `theater` · `screening` · `session` · `user`(프로필·예매 내역·선호, `user.openapi.yaml`) | 계약 타입, API 함수, 쿼리 옵션, 표시용 작은 UI(상태 배지·예매 카드·프로필) |
| `shared` | `api` · `config` · `lib` · `ui` | `/api` 클라이언트·오류(`error.v1`), 경로·환경, KST 날짜·금액, 버튼·배지·칩·탭·확인 대화상자·빈 상태·오류 블록(문의 코드)·토스트 |

- 엔티티끼리는 import 하지 않는다. 꼭 필요하면 `entities/{슬라이스}/@x/{쓰는 슬라이스}.ts` 로만 연다 (`movie/@x/screening`).
- 슬라이스 안에서는 상대 경로, 계층을 넘을 때는 `@/계층/슬라이스` 별칭을 쓴다.
- 다른 담당 화면 경로(W4 `/screenings/:id`)는 `shared/config/routes.ts` 에 링크 대상으로만 둔다. 예매 취소 API(BKG-07)는 좌석 화면의 booking 엔티티와 겹치지 않게 `features/cancel-booking/api` 에 둔다.

## 화면 규칙 (2.2.5 와이어프레임 결정)

- 시각은 응답의 `+09:00` 문자열을 그대로 KST로 표시한다. `date` 는 KST 영업일.
- 오류는 `code` 로 문구를 고르고 `message` 는 표시하지 않는다. 오류 블록 아래 「문의 코드」 = traceId 앞 8자리, 누르면 32자리 복사.
- 401 → 토큰 1회 갱신 후 재시도, 또 401이면 로그인(Hosted UI, 현재 경로로 복귀). 503 → 공통 토스트 + 다시 시도.
- 토큰은 메모리에만. 새로고침은 "로그인한 적 있음" 플래그(localStorage, 토큰 아님)가 있을 때만 Cognito 세션으로 자동 재발급하고, 처음 온 방문자는 로그인 화면으로 보내지 않는다.
- 역할(USER·ADMIN)은 토큰을 파싱하지 않고 `GET /users/me`의 `role`로 받는다 (3.3.6). 콜백(W7)은 토큰을 받은 뒤 `GET /users/me` 로 프로필을 만들고 돌아갈 경로로 보낸다. 실패하면 다시 시도 또는 「그냥 계속하기」(프로필은 마이페이지에서 다시 만들어진다).
- 마이페이지(W8) `/me?tab=preferences`: 예매 취소는 예매 완료 + 상영 시작 전 카드에만(`canCancelBooking`), 회차 정보가 없으면(`screening: null`) 한 줄 안내. 선호 극장은 최대 3곳(순서 = rank), 시간대는 하나(`preferredTimeSlot`), 장르는 저장만(D6).
- 러닝타임은 상세의 `runtimeMinutes` 만 표시(null이면 숨김). `slotMinutes` 는 회차 간격이라 쓰지 않는다 (W-2).
- AI 챗봇(3.3.7): 로그인하면 버튼만 보이고 `/api/ai/**` 는 부르지 않는다(런북 6-G-5 — 좌석 데모·부하 테스트 중 ai 트래픽 0). 창을 처음 열 때 `GET /ai/presets` 를 불러 404(플래그 off)·그 밖의 4xx면 「준비 중」 안내 뒤 닫으면 버튼을 숨기고, 503이면 버튼은 두고 창 안에서 비활성 안내. 답은 문장 + 결과 표 + 「HH:MM 기준」, 「데모 정보」를 켜면 처리 경로·Claude 호출 수·토큰·지연·SQL. 409·429(`Retry-After` 동안 입력 잠금)·503 은 `code`·`details[].reason` 으로 문구를 고른다. 대화는 메모리에만(새로고침하면 사라짐), `sessionId` 는 응답 값을 다음 질문에 그대로.
- 상영예정 탭 없음 (W-1). 포스터 `null` → 제목 카드. 잔여석 `remainingSeatsSource=TOTAL` 이면 전체 좌석 수만 회색.

# frontend — React (Vite) · FSD

- 빌드 결과는 S3 + CloudFront (OAC). 컨테이너 없음. `frontend-deploy` 워크플로가 업로드·무효화한다.
- API는 같은 도메인의 `/api/*` → `VITE_API_BASE_URL=/api`. 로컬은 Vite 프록시 `/api` → `http://localhost:8080`(gateway).
- 로그인은 Cognito Hosted UI (PKCE), `react-oidc-context`. Access Token 을 `Authorization: Bearer` 로. 토큰은 메모리에만(ADR-63).
- SSE: 연결 → BKG-01 스냅샷 → seq 보다 큰 이벤트만 적용. 재연결 때마다 스냅샷 다시. (좌석 화면, 남궁일)

## 실행

```bash
npm ci
npm run dev                 # http://localhost:5173, /api → localhost:8080 (gateway 가 떠 있어야 한다)
npm run dev:mock            # 백엔드 없이: 목 catalog 서버(8089) + Vite(/api → 8089) 한 번에
npm run mock                # 목 서버만 (DEV_API_PROXY=http://localhost:8089 npm run dev 와 함께)
npm run check               # typecheck + eslint + FSD 검사(steiger) + vitest
npm run build               # tsc -b && vite build → dist/
```

`mock/server.mjs` 는 catalog 계약 모양으로 응답하는 가짜 데이터(영화 6편·극장 4곳·하루 편성, 롯데시네마 수집 데이터·포스터 없음)다.
`?fail=503` 을 붙이면 503, `/api/movies/999` 는 404 로 오류 화면을 재현할 수 있다. 빌드에는 포함되지 않는다.

환경 변수는 `.env.example` 참고 (전부 공개 값, `VITE_` 는 빌드에 포함된다). `VITE_COGNITO_*` 가 비어 있으면 로그인 버튼이
비활성화되고 조회 화면만 동작한다. 값은 `docs/service-runtime-contract.md` 3장 frontend 표.

## 구조 — Feature-Sliced Design

`src/` 는 FSD 계층으로 나뉜다. 위 계층만 아래 계층을 import 하고(app → pages → widgets → features → entities → shared),
슬라이스는 `index.ts` 공개 API로만 쓴다. `npm run lint:fsd`(steiger)가 검사한다.

| 계층 | 슬라이스 | 내용 |
| --- | --- | --- |
| `app` | `providers` · `routes` · `styles` | 진입점, 토스트·쿼리·인증(Cognito) 프로바이더, 라우터, 전역 CSS 토큰 |
| `pages` | `home`(W1) · `movie-detail`(W2) · `showtimes`(W3) · `credits`(W10) · `auth-callback`(W7 최소) · `not-found` | 화면 한 장 = 슬라이스 하나 |
| `widgets` | `header` · `footer` · `page-layout` · `movie-catalog` · `showtime-board` | 여러 엔티티·기능을 합친 블록 |
| `features` | `login` · `showtimes-filter` · `enter-seat-select` · `booking-steps` | 사용자 행동 (로그인 이동, 극장·날짜·영화 필터, 회차 클릭 → 로그인 확인 → 좌석 선택, 예매 단계 표시) |
| `entities` | `movie` · `theater` · `screening` · `session` | 계약(`contracts/openapi/catalog.openapi.yaml`) 타입, API 함수, 쿼리 옵션, 표시용 작은 UI |
| `shared` | `api` · `config` · `lib` · `ui` | `/api` 클라이언트·오류(`error.v1`), 경로·환경, KST 날짜, 버튼·배지·빈 상태·오류 블록(문의 코드)·토스트 |

- 엔티티끼리는 import 하지 않는다. 꼭 필요하면 `entities/{슬라이스}/@x/{쓰는 슬라이스}.ts` 로만 연다 (`movie/@x/screening`).
- 슬라이스 안에서는 상대 경로, 계층을 넘을 때는 `@/계층/슬라이스` 별칭을 쓴다.
- 다른 담당 화면 경로(W4 `/screenings/:id`, W8 `/me`)는 `shared/config/routes.ts` 에 링크 대상으로만 둔다.

## 화면 규칙 (2.2.5 와이어프레임 결정)

- 시각은 응답의 `+09:00` 문자열을 그대로 KST로 표시한다. `date` 는 KST 영업일.
- 오류는 `code` 로 문구를 고르고 `message` 는 표시하지 않는다. 오류 블록 아래 「문의 코드」 = traceId 앞 8자리, 누르면 32자리 복사.
- 401 → 토큰 1회 갱신 후 재시도, 또 401이면 로그인(Hosted UI, 현재 경로로 복귀). 503 → 공통 토스트 + 다시 시도.
- 토큰은 메모리에만. 새로고침은 "로그인한 적 있음" 플래그(localStorage, 토큰 아님)가 있을 때만 Cognito 세션으로 자동 재발급하고, 처음 온 방문자는 로그인 화면으로 보내지 않는다.
- 역할(USER·ADMIN)은 토큰을 파싱하지 않고 `GET /users/me`의 `role`로 받는다 (3.3.6).
- 러닝타임은 상세의 `runtimeMinutes` 만 표시(null이면 숨김). `slotMinutes` 는 회차 간격이라 쓰지 않는다 (W-2).
- 상영예정 탭 없음 (W-1). 포스터 `null` → 제목 카드. 잔여석 `remainingSeatsSource=TOTAL` 이면 전체 좌석 수만 회색.

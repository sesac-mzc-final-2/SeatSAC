# frontend — React (Vite)

- 빌드 결과는 S3 + CloudFront (OAC). 컨테이너 없음. `frontend-deploy` 워크플로가 업로드·무효화한다.
- API는 같은 도메인의 `/api/*` → `VITE_API_BASE_URL=/api`. 로컬은 Vite 프록시 `/api` → `http://localhost:8080`.
- 로그인은 Cognito Hosted UI (PKCE), `react-oidc-context`. Access Token 을 `Authorization: Bearer` 로.
- SSE: 연결 → BKG-01 스냅샷 → seq 보다 큰 이벤트만 적용. 재연결 때마다 스냅샷 다시.

```bash
npm create vite@latest . -- --template react-ts   # 최초 1회 (이 README 를 덮어쓰지 않게 주의)
npm install && npm run dev
```

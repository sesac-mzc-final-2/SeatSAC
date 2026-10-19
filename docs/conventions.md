# conventions — 03a 명명 규칙 회의 결정으로 채운다

- 포트: 컨테이너·Service 8080, 로컬 gateway 8080 · catalog 8081 · booking 8082 · user 8083 · payment 8084 · ai 8085
- 경로: 외부 `/api/*`(Gateway StripPrefix), 내부 `/internal/**`, 헬스 `/actuator/health/*`
- DB: `{svc}_db` + `{svc}_app`/`{svc}_migrator`, `stats_db`/`stats_lambda`, 시각 UTC(`DATETIME(3)`), API +09:00
- 환경변수: Spring 표준 + `APP_`, frontend `VITE_`
- 키: `seat:{회차}:{좌석}`, `user-hold:{회차}:{사용자}`, `hold-expiry`, `seat-seq:{회차}`, `idem:{키}`, `catalog:*`
- 로그: JSON 한 줄 (timestamp · level · service · traceId · userId · message · error)

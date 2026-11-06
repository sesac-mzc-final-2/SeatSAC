# services/ai — AI 챗봇 (Python · FastAPI)

규칙은 `docs/service-runtime-contract.md` 9장, 이름은 `docs/conventions.md` ⚠️AI 행, API는 `contracts/openapi/ai.openapi.yaml`.

## 로컬 실행

```bash
cd services/ai
uv sync                       # Python 3.12, pyproject.toml + uv.lock
cp .env.example .env          # 개발 전용 키만. .env는 커밋 금지
uv run uvicorn seatsac_ai.main:app --port 8085 --reload
curl -s localhost:8085/actuator/health/readiness
```

gateway를 거치지 않고 직접 부를 때는 gateway가 넣어 주는 헤더를 직접 붙인다:
`-H 'X-User-Id: 11111111-2222-3333-4444-555555555555'`.

## 검사 (pr-check `python`과 같은 명령)

```bash
uv run ruff check .
uv run pytest
```

## 패키지

| 패키지 | 내용 | WBS |
| --- | --- | --- |
| `common` | 오류 형식(`error.v1.json`)·traceId·로그·지표·readiness | 3.4.8 |
| `api` | `/actuator/*`, `POST /ai/chat`, `GET /ai/presets` | 3.4.8 (자리) · 3.4.3 · 3.4.4 |
| `snapshot` | 공개 API → SQLite `:memory:` 스냅샷 | 3.4.9 |
| `llm` | Claude API 도구 호출 | 3.4.2 · 3.4.3 |
| `sql` | sqlglot 검증·읽기 전용 실행 | 3.4.2 |
| `cache` | 정확 일치·의미 캐시(ai Valkey) | 3.4.5 |
| `quota` | 동시 1·분당 10·하루 100 | 3.4.3 |

readiness는 각 작업이 `app.state.readiness.register(이름, 점검)`으로 점검을 붙인다
(3.4.9 `snapshot`, 3.4.5 `valkey`). 점검이 없으면 UP이다.

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

## 지표 읽는 법 (3.4.7 · 대시보드 3.1.22)

지표 정의는 `docs/service-runtime-contract.md` 5장. 절감 데모의 숫자는 아래 식으로 만든다.
**우리 캐시 효과(input+output)와 프롬프트 캐싱(cache_read)은 섞지 않는다.**

| 보여줄 것 | PromQL |
| --- | --- |
| Claude 없이 답한 비율 | `sum(increase(ai_chat_total{result="ok",path=~"cache_exact\|cache_semantic\|my_bookings"}[1h])) / sum(increase(ai_chat_total{result="ok"}[1h]))` |
| 캐시 적중률(층별) | `sum by (layer) (increase(ai_cache_requests_total{result="hit"}[1h])) / sum by (layer) (increase(ai_cache_requests_total[1h]))` |
| Claude 답 1건당 토큰(input+output) | `sum(increase(ai_tokens_total{kind=~"input\|output"}[1h])) / sum(increase(ai_chat_total{path=~"preset\|nl2sql\|rejected\|out_of_scope"}[1h]))` |
| 캐시로 아낀 토큰(추정) | 위 "1건당 토큰" × `sum(increase(ai_chat_total{result="ok",path=~"cache_exact\|cache_semantic"}[1h]))` |
| 프롬프트 캐싱으로 싸게 읽은 토큰 | `sum(increase(ai_tokens_total{kind="cache_read"}[1h]))` |
| Claude 지연 p95 | `histogram_quantile(0.95, sum by (le) (rate(ai_llm_call_seconds_bucket[5m])))` |

> ⚠️ 표 안의 `\|`는 마크다운 표 때문에 쓴 것이다. **원문(.md)에서 복사하면 `\|`를 `|`로 바꿔야** PromQL이 파싱된다(#95 리뷰). 정규식이 있는 식은 아래에 그대로 붙여 둔다.

```promql
# Claude 없이 답한 비율
sum(increase(ai_chat_total{result="ok",path=~"cache_exact|cache_semantic|my_bookings"}[1h])) / sum(increase(ai_chat_total{result="ok"}[1h]))
# Claude 답 1건당 토큰(input+output)
sum(increase(ai_tokens_total{kind=~"input|output"}[1h])) / sum(increase(ai_chat_total{path=~"preset|nl2sql|rejected|out_of_scope"}[1h]))
# 캐시로 답한 건수(아낀 토큰 = 1건당 토큰 × 이 값)
sum(increase(ai_chat_total{result="ok",path=~"cache_exact|cache_semantic"}[1h]))
```

- `ai_llm_call_seconds` 버킷은 0.5~25초(Claude 한도 15초까지 p95가 잘리지 않게).
- 경로 `out_of_scope`·`rejected`도 Claude를 부른 답이라 "1건당 토큰"의 분모에 넣는다.
- 캐시 적중률의 분모는 **캐시를 본 질문**만이다. 후속 질문("그중 …")과 같은 종류 값이 두 번 나온 질문은 캐시를 보지 않아 세지 않는다.
- Claude 호출이 중간에 실패한 질문도 이미 쓴 토큰은 `ai_tokens_total`에 들어간다(`path`는 직전에 고른 도구).

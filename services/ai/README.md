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

### Windows 메모

- `uv sync --locked`가 Python 3.12를 내려받다 `Missing expected target directory …`로 멈추면 `UV_PYTHON_INSTALL_DIR`를 짧은 경로(예 `C:\Users\<이름>\.local\uv-python`)로 두고 `uv python install 3.12`를 먼저 한다
- Windows Python에는 시간대 DB가 없어 `ZoneInfoNotFoundError: Asia/Seoul`이 난다 → `uv run --with tzdata pytest` / `uv run --with tzdata uvicorn …`(lock은 그대로)
- `test_slow_query_is_stopped`는 Windows + Python 3.12에서 실패할 수 있다 — `time.monotonic()` 해상도가 약 15ms라 0.001초 상한을 재지 못한다. 이미지(Linux)·CI에서는 통과한다
- 콘솔 한글이 깨지면 `PYTHONUTF8=1`

## 연동 테스트 (WBS 4.1.2)

실행 중인 서비스에 계약대로 요청을 보내 다섯 경로(PRESET·CACHE_EXACT·CACHE_SEMANTIC·NL2SQL·MY_BOOKINGS) 200, 검증기 차단, 오류 형식을 확인한다 — `eval/e2e.py`(질문 9건, 분당 10건 한도 안). 결과 표(markdown)와 JSON(`eval/out/`, 커밋 안 함)을 남긴다.

| 어디 | 명령 | 전제 |
| --- | --- | --- |
| 로컬 | `uv run python -m eval.e2e --base http://localhost:8085` | Compose `valkey-ai`(6380) + `.env`의 개발 전용 키 + `PUBLIC_API_BASE_URL`(로컬 gateway 또는 staging CloudFront). Valkey가 없으면 5·6·11(캐시·세션)이 FAIL, 키가 없으면 Claude가 필요한 질문이 503 `CIRCUIT_OPEN` |
| staging(port-forward) | `kubectl -n cinema-staging port-forward svc/ai 8085:8080` 뒤 같은 명령 | `ng-ai` 1대(김종식 `cluster-scale.sh up ng-ai`, 런북 6-G-2 A)·readiness UP. 4.2.1·4.2.2가 돌지 않는 시간에만(런북 6-G-5) |
| CloudFront(플래그 off) | `--base https://<배포 도메인>/api --token "$TOKEN" --expect-disabled` | 라우트가 없어 **토큰이 있으면 404, 없으면 401**(gateway 인증이 라우팅보다 먼저). 토큰 없는 401은 플래그가 켜져 있어도 같아서 `--token`이 필수다 |
| CloudFront(플래그 on, E2E) | `--base https://<배포 도메인>/api --token "$TOKEN"` | rc2 뒤 gitops `APP_AI_ENABLED=true` + `ng-ai` 1대. 로그인한 사용자의 Access Token(화면 개발자 도구 → 네트워크 → `Authorization` 헤더, 또는 `tests/k6/tools/loadtest_users.py tokens`) |

- 직접 호출(로컬·port-forward)은 gateway가 넣는 `X-User-Id`를 스크립트가 임의 UUID로 붙인다. "내 예매"(MY_BOOKINGS)는 요청의 `Authorization`을 그대로 공개 API에 넘기므로 **토큰 없이는 401이 정상**이고, 200을 보려면 `--token`을 준다(`X-User-Id`는 토큰의 `sub`로 맞춘다)
- 같은 ai Valkey에 두 번째로 돌리면 4번이 PRESET 대신 CACHE_SEMANTIC/CACHE_EXACT로 나올 수 있다(정상)
- 검증기 차단(10-a·10-b)은 Claude가 거부 사유를 받고 고쳐 쓸 수 있어 REJECTED·OUT_OF_SCOPE 또는 `ai_rejected_total` 증가 중 하나로 본다. CloudFront 경유는 지표를 볼 수 없어 path가 차단이 아니면 SKIP(판정 불가)이다. 결정적 확인은 `tests/test_sql_validator.py`·`test_sql_executor.py`(pytest)
- 토큰·질문 원문은 출력하지 않는다

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

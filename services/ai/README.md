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

## 채팅으로 좌석 잡기 (3.4.19)

"오늘 20시 ○○ 건대입구 J7 J8 잡아줘" → 확인 요청(버튼) → **확인 버튼을 누른 뒤에만** 공개 API `POST /seats/hold`.
결제는 채팅에서 하지 않는다(`seat_hold.py`, 계약 `ai.openapi.yaml`의 `HOLD_*`·`seatHold`).

- **기본 켜짐.** 끄려면 `APP_AI_SEAT_HOLD_ENABLED=false`(로컬 `.env`, staging은 gitops `overlays/staging/ai`의 `ai-config`).
  **staging은 gitops에서 `false`로 두고(끄는 gitops PR을 이 코드보다 먼저 머지), 로컬 확인 뒤 켠다**
  끄면 Claude에 가는 system·도구가 좌석 잡기가 없던 때와 글자 하나 다르지 않다(`tests/test_seat_hold.py`)
- 조건이 모자라도 되묻는다(3.4.24): 예매할 수 있는 회차가 1개면 바로 확인 요청, 2~7개면 회차 버튼, 더 많으면 빠진 조건을
  영화 → 날짜 → 극장 순으로 하나씩 버튼으로 묻는다(스냅샷에 실제 회차가 있는 값만, 7개까지). 버튼의 뜻은 대기 행동
  `ai:pending`(2분)에 두고, 누르면 Claude 없이 조건을 하나 더해 다시 좁힌다. 좌석 이름이 없으면 회차까지만 정하고
  좌석 화면 링크로 보낸다(`SEATS_REQUIRED`). 선점은 여전히 「잡기」 확인 버튼 뒤에만
- 켜져 있어도 ai Valkey(`AI_VALKEY_HOST`)나 `PUBLIC_API_BASE_URL`이 없으면 꺼진 것으로 동작한다
- 확인은 닿았는데 응답만 실패해(예: gateway 시간 초과) 같은 확인 버튼 문장이 다시 오면, 성공한 선점의 결과를
  `ai:held:{userId}:{sessionId}`(선점이 남은 동안, 최대 5분)에서 꺼내 같은 답을 준다 — booking을 다시 부르지 않는다
- 로컬에서 켠 채 `PUBLIC_API_BASE_URL`을 staging 주소로 두면 확인 버튼이 **staging 좌석을 실제로 잡는다** — 로컬 gateway 주소로 두고 쓴다
- `eval.run`은 좌석 잡기 클라이언트를 붙이지 않아 도구 4개(꺼진 상태)의 정확도를 잰다

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

## 문맥 이어받기 (3.4.22)

답 아래 「영화 고르기」 버튼이 앞 답의 날짜·극장·시각(○시 이후/이전)을 잃지 않게 한다 — `seatsac_ai/context.py`, 내부 SQL 틀 `resources/context_templates.yaml`.

- 답마다 **실행한 SQL의 맨 바깥 SELECT 조건**에서 하나로 확정된 날짜·극장·영화·시각을 세션 `ctx`(최근 5개)에 남기고 응답 `context`로 돌려준다(`sql/conditions.py` `analyze_only_fixed`)
- 「이 답은 이 조건만 조회했다」고 말할 수 없으면 남기지 않는다: SQL에 서브쿼리·UNION·CTE가 있거나(`NOT IN (… 건대입구 …)`는 건대입구를 뺀 답이다), 날짜·극장·영화·「지금 이후」·「○시 이후/이전 하나」 말고 다른 걸러 내는 조건(지역·상영관 종류·등급·시각 범위 두 개·`start_hour`·잔여석·OR)이 있을 때. 그 답의 버튼은 예전처럼 독립 질문이다
- 시각은 `starts_at >= '<날짜> HH:MM'`(`>`·`<`·`<=`도) **한 줄**만 읽는다 — 프리셋 `today_after_time`의 `:today || ' ' || :time` 모양과 Claude가 쓴 리터럴 모양 둘 다. 그 날짜 부분이 답의 하나뿐인 날짜(`business_date`)와 같아야 하고, 시각이 질문에서 뽑은 시각 슬롯(또는 프리셋 빈칸 `time`)과 같고, 연산자의 방향이 질문의 말뜻(이후/이전)과 같아야 한다(`context.confirmed_time`). 하나라도 어긋나면 시각만 빼지 않고 **문맥 전체를 남기지 않는다**. 「지금 이후」(`starts_at > :now`·지금 시각 글자)는 시각 조건으로 세지 않는다
- 시각은 날짜에 붙은 조건이다 — 날짜를 이어받을 때만 같이 이어받는다. 날짜가 지났거나 새 메시지가 날짜를 말하면 시각도 버린다. 오늘의 이미 지난 시각은 그대로 둔다(틀에 「지금 이후」가 함께 걸려 있다)
- 버튼은 그 답의 `context.id`를 `continueFrom`으로 보낸다 → 그 조건 + 고른 영화로 내부 틀을 Claude 없이 실행한다. `continueFrom`이 없는(직접 친) 질문은 예전과 같다
- 내부 틀 7개(날짜+극장+영화 · 날짜+영화 · 극장+영화, 그리고 날짜가 있는 두 틀의 「○시 이후」·「○시 이전」 판 4개)는 `presets.yaml`과 따로 둔다. 시각 틀은 (빈칸 종류, 방향)으로 고른다 — 연산자는 틀에 미리 써 둔 것(이후 `>=`, 이전 `<=`)이고 값만 바인딩한다(SQL에 글자를 끼워 넣지 않는다) — 화면 버튼·Claude 프롬프트·도구 목록에 나가지 않는다
- **직접 친 질문은 내부 틀로 답하지 않는다.** 예열 항목의 키가 질문 말투가 아니라 `ctx {presetId}`이고(질문의 키는 빈칸이 없어 겹칠 수 없다) 의미 색인에도 넣지 않는다. 평소 캐시 조회는 `presetId`가 `ctx_`로 시작하는 항목을 만나도 쓰지 않는다. 이 키는 이어받기 경로만 조회한다 — 있으면 `CACHE_EXACT`, 없으면 틀을 직접 실행해 `PRESET`
- 지표: 이어받은 답은 `ai_chat_total`의 `cache_exact`(예열 적중) 또는 `preset`(캐시가 비었을 때, 토큰 0)로 잡힌다. `ai_cache_requests_total`에는 세지 않는다(캐시 적중률과 무관). 건수는 로그 `context carried`(`presetId`·`inherited`)로 본다
- 한계: 이어받는 것은 날짜·극장·시각(○시 이후/이전 하나)이다. ○시 이후/이전은 이어받는다. 시각 범위 두 개(20시~22시)·`start_hour` 표현·심야/조조 같은 고정 범위, 지역·상영관 종류·등급 조건이 있는 답은 문맥을 남기지 않는다(이어받지 않음). 이어받은 시각의 경계는 포함이다(이후 `>=`, 이전 `<=`) — 앞 답의 SQL이 `>`·`<`였다면 정각에 시작하는 회차 하나가 더 나올 수 있다. 개수(LIMIT)·정렬은 조건으로 보지 않아 "자리 많은 회차 5개" 답에서 영화를 고르면 오늘 그 영화 전체다. 날짜나 극장이 둘 이상인 답은 그 칸만 빼고 나머지 한 칸을 이어받는다

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

응답의 `cache.entryId`(3.4.20 데모 화면의 캐시 짝)는 캐시 키 해시를 파드 기동 때 만든 임의 키로 HMAC 한 값이다.
파드가 다시 뜨면 값이 바뀌어 그 전 답과는 짝이 끊긴다(같은 대화 안 짝만 필요). 캐시 항목 자체는 그대로 쓴다.

## 지표 읽는 법 (3.4.7 · 대시보드 3.1.22)

지표 정의는 `docs/service-runtime-contract.md` 5장. 절감 데모의 숫자는 아래 식으로 만든다.
**우리 캐시 효과(input+output)와 프롬프트 캐싱(cache_read)은 섞지 않는다.**

| 보여줄 것 | PromQL |
| --- | --- |
| Claude 없이 답한 비율 | `sum(increase(ai_chat_total{result="ok",path=~"cache_exact\|cache_semantic\|my_bookings"}[1h])) / sum(increase(ai_chat_total{result="ok",path!~"hold_.*"}[1h]))` |
| 캐시 적중률(층별) | `sum by (layer) (increase(ai_cache_requests_total{result="hit"}[1h])) / sum by (layer) (increase(ai_cache_requests_total[1h]))` |
| 새로 배운 말투(24시간) | `increase(ai_cache_learned_total{result="stored"}[24h])` |
| Claude 답 1건당 토큰(input+output) | `sum(increase(ai_tokens_total{kind=~"input\|output",path!~"hold_.*"}[1h])) / sum(increase(ai_chat_total{path=~"preset\|nl2sql\|rejected\|out_of_scope\|clarify"}[1h]))` |
| 캐시로 아낀 토큰(추정) | 위 "1건당 토큰" × `sum(increase(ai_chat_total{result="ok",path=~"cache_exact\|cache_semantic"}[1h]))` |
| 프롬프트 캐싱으로 싸게 읽은 토큰 | `sum(increase(ai_tokens_total{kind="cache_read"}[1h]))` |
| 못 답한 요청(분류별, 24시간) | `sum by (category) (increase(ai_unmet_demand_total[24h]))` |
| Claude 지연 p95 | `histogram_quantile(0.95, sum by (le) (rate(ai_llm_call_seconds_bucket[5m])))` |

> ⚠️ 표 안의 `\|`는 마크다운 표 때문에 쓴 것이다. **원문(.md)에서 복사하면 `\|`를 `|`로 바꿔야** PromQL이 파싱된다(#95 리뷰). 정규식이 있는 식은 아래에 그대로 붙여 둔다.

```promql
# Claude 없이 답한 비율
sum(increase(ai_chat_total{result="ok",path=~"cache_exact|cache_semantic|my_bookings"}[1h])) / sum(increase(ai_chat_total{result="ok",path!~"hold_.*"}[1h]))
# Claude 답 1건당 토큰(input+output)
sum(increase(ai_tokens_total{kind=~"input|output",path!~"hold_.*"}[1h])) / sum(increase(ai_chat_total{path=~"preset|nl2sql|rejected|out_of_scope|clarify"}[1h]))
# 캐시로 답한 건수(아낀 토큰 = 1건당 토큰 × 이 값)
sum(increase(ai_chat_total{result="ok",path=~"cache_exact|cache_semantic"}[1h]))
```

- `ai_llm_call_seconds` 버킷은 0.5~25초(Claude 한도 15초까지 p95가 잘리지 않게).
- 경로 `out_of_scope`·`rejected`도 Claude를 부른 답이라 "1건당 토큰"의 분모에 넣는다.
- 경로 `hold_*`(채팅으로 좌석 잡기, 플래그 `APP_AI_SEAT_HOLD_ENABLED`가 켜져 있을 때만 생긴다 — 기본 켜짐)는 조회 답이 아니라 위 식에서 뺀다(`path!~"hold_.*"`). `hold_confirm`·`hold_guide`는 Claude를 1회 쓰지만 조회 답이 아니고, `hold_result`는 토큰 0이라 넣으면 비율이 부풀려진다. 좌석 잡기가 켜져 있으면 좌석을 잡아 달라는 요청은 `hold_seats`로 가므로 `ai_unmet_demand_total{category="action_request"}`에는 결제·예매 취소 같은 나머지 행동 요청만 잡힌다(끄면 좌석 잡기 요청도 다시 여기에 잡힌다).
- 캐시 적중률의 분모는 **캐시를 본 질문**만이다. 후속 질문("그중 …")과 같은 종류 값이 두 번 나온 질문은 캐시를 보지 않아 세지 않는다.
- `ai_cache_learned_total`은 스스로 배우기(`cache/learned.py`) 결과다. 배운 말투가 틀린 답을 내면 ai Valkey에서 먼저 `APP_AI_CACHE_LEARN_ENABLED=false`로 끄고 ai Valkey에서 `DEL ai:learned:v2`로 장부를 비운다(다음 예열부터 다시 넣지 않는다, 캐시에 남은 항목은 1시간 안에 사라진다). 끄지 않고 지우기만 하면 Claude가 같은 답을 낼 때 다시 배운다.
- `ai_unmet_demand_total`은 Claude가 `out_of_scope` 도구로 끝낸 질문을 `category`(`action_request`·`site_feature`·`unrelated`·`unknown`)별로 센다. `unknown`은 도구를 불렀는데 분류가 정해진 값이 아닌 경우다. 욕설·인사·잡담(Claude가 `chitchat`으로 분류)과 Claude가 도구 없이 두 번 끝난 폴백은 수요가 아니라 **지표도 로그도 남기지 않는다** — 그래서 합계는 `ai_chat_total{path="out_of_scope"}`보다 그만큼 작다. `unrelated`는 영화관과 무관해도 구체적인 정보·기능을 원한 요청("오늘 날씨 어때?")이고, "오늘 날씨 좋다" 같은 말은 chitchat이다.
- 범위 밖(`OUT_OF_SCOPE`) 답의 `answer`는 같은 `category`에 따라 고정 문장이 다르다(3.4.21): `action_request`는 "그 기능은 아직 준비 중이에요. 지금은 화면에서 해 주세요.", `site_feature`는 "그 정보는 아직 제공하지 않아요. 영화·극장·상영 시간은 물어보실 수 있어요.", 그 밖(`unrelated`·`chitchat`·`unknown`·도구 없이 끝난 폴백)은 "영화·극장·상영 시간에 대한 질문에만 답할 수 있어요.". Claude가 쓴 `reason`은 답에 넣지 않는다. 응답 계약·`path`·지표·로그는 그대로다.
- 무엇을 원했는지는 로그 `unmet demand`의 `reason`(Claude가 쓴 요약, 120자)으로 본다 — 질문 원문은 남기지 않는다. 숫자 연속(3개 이상)과 이메일은 `[숫자]`·`[이메일]`로 가리고, `reason`이 질문을 베낀 것으로 보이면(공백·문장부호를 뺀 뒤 겹치는 연속 글자가 `max(8, min(질문의 60%, 20))`자 이상) `reason`을 비우고 `reasonDropped: true`를 남긴다.
- 수요 기록의 한계: ① "내 예매 취소해줘"처럼 '내 예매' 낱말이 든 요청은 MY_BOOKINGS 경로로 가서 세지 않는다. ② chitchat으로 잘못 분류된 요청은 흔적이 없고 `ai_chat_total{path="out_of_scope"}`와의 차이로만 건수를 본다(이 차이에는 도구 없이 끝난 폴백도 들어 있다). ③ 24시간 합계는 ai 파드가 떠 있던 구간만 반영한다(ng-ai는 평소 0대).
- Claude 호출이 중간에 실패한 질문도 이미 쓴 토큰은 `ai_tokens_total`에 들어간다(`path`는 직전에 고른 도구).

# 실시간 좌석 데모 대본 (WBS 5.1.2)

> 초안 2026-10-30 · 갱신 2026-11-10 · 남궁일. 1차 데모 11/16(무중단 배포 · 실시간 좌석, ADR-82), 데모 주간 11/16~20.
> 무중단 배포 데모는 5.1.1(김종식)이고 이 문서는 **실시간 좌석**만 다룬다. 부하 테스트(4.2.1) 실행 절차는 `docs/runbooks/terraform-infra.md` 6-H, 대시보드는 6-F.

## 0. 증명할 것

| # | 보여줄 것 | 목표 수치 | 어디서 확인 |
| --- | --- | --- | --- |
| 1 | 다른 화면에 반영 | **1초 이내** | 두 브라우저 나란히 (눈) |
| 2 | 같은 좌석 100명 동시 선점 | **성공 정확히 1명**, 선점 p95 **300ms 이하** | k6 Job 로그 「성공 1 · 사용자 제한 0 · p95 …ms」 + 신호등 대시보드 ⑤ 「선점 성공·충돌 건수」 |
| 3 | 같은 멱등 키 2회 요청 | 예매 **1건** | `curl` 2회 응답의 `bookingId` 동일 |
| 4 | 선점 만료 | **2초 이내** 좌석 해제 | 화면 + `booking_hold_expiry_lag_seconds` |

전체 **7분**. 결제까지 보여주면 9분.

---

## 1. 준비 (D-1까지)

### 1-1. 사람·장비

| 역할 | 누구 | 하는 일 |
| --- | --- | --- |
| 진행·설명 | 남궁일 | 브라우저 A 조작, 대본 진행 |
| 보조 | 김종식 | 브라우저 B(관찰자 화면)·Grafana·k6 Job 실행(`cinema-admin`) |

- 화면은 **셋**을 띄운다: 브라우저 A(예매하는 사람) · 브라우저 B(다른 사람) · Grafana
- A와 B는 **서로 다른 Cognito 계정**이어야 한다 — 회차당 선점 1건 규칙 때문에 같은 계정이면 두 번째가 `ACTIVE_HOLD_EXISTS`로 막힌다
- B는 **새로고침 없이** 좌석판이 바뀌는 것을 보여주는 창이다. 절대 새로고침하지 않는다(SSE로 바뀌는 게 핵심)

### 1-2. 체크리스트

- [ ] 클러스터 가동 (데모 주간 11/16~20은 24시간, `cluster_keep_on`) · **`ng-ai` 0대 · AI 질의 금지**(런북 6-G-5 — 좌석 데모 p95 측정 보호, 챗봇 창을 열지 않는다)
- [ ] 데모용 회차 **2개**: `DEMO_SCREENING`(장면 1·3·4·5), `CONTENTION_SCREENING`(장면 2). 100명이 몰린 회차를 다시 쓰면 좌석이 지저분해진다
  - **시작까지 60분 이상 남은 회차**를 고른다. 선점·결제는 **상영 시작 5분 전에 마감**된다(#77, 400 `SCREENING_STARTED` · `reason: BOOKING_CLOSED`). 데모 도중 마감에 걸리지 않게 여유를 둔다
  - 고르는 법: `eval "$(python3 tests/k6/tools/pick_targets.py --base-url "$API")"`(런북 6-H-1 3) — `CONTENTION_*`를 장면 2에, `USERHOLD_*` 회차를 `DEMO_SCREENING`으로 써도 된다
- [ ] Cognito 계정: 데모용 2개(A·B) + **경쟁용 100개**(4.2.1과 공용, `tests/k6/tools/loadtest_users.py`)
- [ ] 토큰 — Access Token **60분**(Terraform `access_token_validity = 60`). **데모 직전에 다시 받는다**: `python3 tests/k6/tools/loadtest_users.py refresh && … tokens --min-valid-minutes 40 --out ~/.seatsac/k6-tokens.txt`(k6는 테스트 중 로그인을 부르지 않는다). 토큰 파일은 레포 밖, 커밋·노션 금지
- [ ] 신호등 대시보드(3.1.7, 런북 6-F) 열어 두기 — 시간 범위를 **데모 직전부터**로 잡는다(⑤ 행 건수 카드가 범위 안 합계라서)
- [ ] CloudFront 경유 SSE 확인: `python3 tests/k6/tools/sse_lag.py --duration 60 --url "$API/screenings/$DEMO_SCREENING/seats/stream"` → 하트비트가 **15초 간격으로 몰리지 않고** 온다(압축 끔·캐시 비활성이 안 돼 있으면 뭉쳐서 온다)
- [ ] 데이터 초기화 (5.1.1 런북): 예매 정리 · Valkey 키 삭제 · 회차 시드 재적재 — 재적재로 회차 ID가 바뀌면 위 회차를 다시 고른다
- [ ] 리허설 1회 — **특히 장면 4의 타이밍**

### 1-3. 환경변수 (진행자 터미널)

```bash
export API="https://$(cd infra/terraform/envs/staging && terraform output -raw cloudfront_domain_name)/api"   # 화면과 같은 경로
export DEMO_SCREENING=…        # 시작 60분 이상 남은 회차 (장면 1·3·4·5)
export CONTENTION_SCREENING=…  # 시작 60분 이상 남은 회차 (장면 2)
export TOKEN_A=…  TOKEN_B=…    # 데모 계정 2개 (셸 기록에 남기지 않게 파일에서 읽는다)
export K6_TOKENS_FILE=~/.seatsac/k6-tokens.txt   # 경쟁용 100개
```

---

## 2. 타임라인

| 시각 | 할 일 | 왜 |
| --- | --- | --- |
| **T-10분** | 토큰 재발급, 브라우저 A·B 로그인, 대시보드 열기 | Access Token 60분 |
| T-3분 | A·B를 `DEMO_SCREENING` 좌석 화면에 띄워 둔다 | B의 「실시간 반영 중」 표시 확인 |
| **T-1분** | **장면 4용 선점을 미리 잡는다** (아래 명령) | 선점 TTL 300초 — 장면 1~3이 약 4분이라 장면 4 시작 무렵 만료된다 |
| **T=0** | 장면 1 시작 | |
| T+4분경 | 장면 4 — 미리 잡은 선점이 만료 | T-1분 + 5분 |

> **장면 4 타이밍.** 선점 TTL은 이제 설정(`APP_SEAT_HOLD_TTL_SECONDS`, 30~600초, #76)이지만 **데모에서는 기본 300초 그대로 둔다**.
> - 줄이려면 gitops booking overlay 값 변경 = booking 배포다. M6(11/9~13)엔 booking 변경 금지, 데모 주간엔 머지 금지라 바꿀 창이 사실상 없다
> - 화면 문구 「5분 동안 좌석을 지켜 드려요」 등이 300초에 고정돼 있어 값을 바꾸면 안내와 실제가 어긋난다
> - 값을 바꾸면 이전 TTL 동안 커밋 중/고아 판정이 어긋난다(런타임 계약 3장 booking 행)
>
> 그래서 **장면 4 시작 정확히 5분 전(T-1분)** 에 잡는다. 리허설에서 장면 1~3 시간을 재 보고 조정한다.

```bash
# T-1분: 장면 4용 선점 (만료를 보여줄 좌석 1석). bookingId를 적어 둔다
curl -s -X POST "$API/seats/hold" \
  -H "Authorization: Bearer $TOKEN_B" -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"screeningId\":$DEMO_SCREENING,\"seatIds\":[<만료용 좌석ID>]}" | tee ~/.seatsac/expiring.json
```

---

## 3. 대시보드 (B 화면 옆에 띄워 둔다)

신호등 대시보드(런북 6-F)의 **⑤ 실시간 좌석 행**을 쓴다. 따로 패널을 만들지 않는다.

| 볼 것 | 지표 | 어느 장면에서 |
| --- | --- | --- |
| 선점 성공·충돌 건수 | `booking_seat_hold_total{result}` (#66 — `success`·`conflict`·`active_hold`·`error`, 4xx 거절은 세지 않음) | 장면 2 — `success` 1, `conflict` 99. 「선점 결과 / 초」는 rate라 한순간 봉우리로만 보이니 **건수 카드**를 가리킨다 |
| 선점 p95 | `http_server_requests_seconds{application="booking",uri="/seats/hold"}` | 장면 2 — 300ms 선 |
| SSE 연결 수 | `booking_sse_connections` | 장면 1 — 2(A·B) |
| 만료 반영 지연 | `booking_hold_expiry_lag_seconds` p95 | 장면 4 — 2초 선 |

---

## 4. 장면별 대본

### 장면 1 — 다른 화면에 1초 이내 반영 (90초)

**보여줄 것**: A가 좌석을 잡으면 B 화면이 **새로고침 없이** 바뀐다.

1. A·B 모두 같은 회차 좌석 화면. B의 좌석판 위 **「실시간 반영 중」** 표시를 가리킨다
2. A에서 좌석 2개를 누른다 → **아직 아무 일도 일어나지 않는다**(화면 안 선택일 뿐). B는 그대로다
   > 설명: "좌석을 누르는 건 서버에 가지 않습니다. **잠금은 「결제하기」를 누를 때** 걸립니다."
3. A가 **「결제하기」** → A는 결제 화면으로, **B에서 그 두 좌석이 1초 안에 호박색(선점 중)으로 바뀐다**
4. A가 결제 화면에서 **「선점 취소하고 돌아가기」** → B에서 그 좌석이 **다시 비워진다**

**말할 것**: Valkey Lua 한 번(`hold.lua`)으로 2석을 원자적으로 잡고(좌석 키 `SET EX` + `user-hold` + 만료 장부), RDS 커밋 뒤 `seat-events:{회차}`에 발행 → 각 파드가 구독해 SSE로 내린다. 화면은 `seq`가 `lastSeq+1`일 때만 적용하고 빈틈이면 200ms 뒤 스냅샷을 다시 받는다.

**Plan B**: B가 안 바뀌면 → ① B의 표시가 「연결 다시 시도 중」인지 본다 ② 끊겼으면 B 새로고침(스냅샷으로 복구되는 것도 설계라고 설명) ③ CloudFront 압축/캐시 문제면 `sse_lag.py`나 `curl -N`으로 이벤트가 오는 걸 터미널에서 보여 준다.

### 장면 2 — 같은 좌석 100명, 성공 1명 (90초)

**보여줄 것**: k6 Job 요약 「성공 1 · 사용자 제한 0 · p95 …ms」와 대시보드 건수 카드.

```bash
# 김종식 터미널 (cinema-admin). 런북 6-H-2 ② — 클러스터 안 일회성 Job, gateway Service 직접 호출
tests/k6/run-in-cluster.sh contention -e SCREENING_ID=$CONTENTION_SCREENING -e SEAT_ID=<좌석ID>
# 결과 로그: ~/.seatsac/k6-results/{실행 ID}.log
```

읽는 법 — 스크립트에 임계값이 걸려 있어 **어긋나면 k6가 실패로 끝난다**:

- `hold_success` **count==1** ← 데모의 핵심
- `hold_latency` **p(95)<300**
- `hold_seat_taken` 99 (정상), `hold_active_hold` **0** ← 0이 아니면 토큰이 겹쳐 측정이 무효다
- 동시에 B 화면(같은 회차를 띄웠다면)에서 그 좌석이 선점 중으로 바뀐다

**말할 것**: 100개 요청이 Valkey 단일 스레드의 Lua 한 번에서 직렬화된다. 성공한 1명만 RDS에 `PENDING` 예매를 받고, 나머지는 409 `SEAT_UNAVAILABLE`로 좌석 목록을 돌려받는다. DB 행 잠금을 쓰면 커넥션 5개를 물고 대기하느라 p95가 무너진다. 최후 방어선은 `booking_seat`의 `(screening_id, seat_id)` 유일 제약.

**Plan B**: 토큰 100개가 준비되지 않으면 ① `-e VUS=20`을 붙여 줄여 돌린다(`run-in-cluster.sh`가 이 값으로 토큰 수를 점검한다. 성공 1명은 그대로 보인다) ② 4.2.1 리포트(같은 시나리오의 실측)를 화면에 띄워 설명한다.

### 장면 3 — 같은 멱등 키 2회 → 예매 1건 (60초)

```bash
KEY=$(uuidgen)
for i in 1 2; do
  curl -s -X POST "$API/seats/hold" \
    -H "Authorization: Bearer $TOKEN_A" -H "Content-Type: application/json" \
    -H "Idempotency-Key: $KEY" \
    -d "{\"screeningId\":$DEMO_SCREENING,\"seatIds\":[<좌석ID>]}" \
    | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get("bookingId"), d.get("seatLabels"))'
done
```

**보여줄 것**: 두 응답의 `bookingId`가 **같다**. B 화면에서도 그 좌석 하나만 선점 중으로 바뀐다(두 번째 요청은 서버에서 다시 실행되지 않았다).

> 예매 내역 API(`/users/me/bookings`)는 PENDING을 보여 주지 않는다(계약: 생략 시 PENDING 제외). 선점 상태는 좌석 화면이나 `GET $API/bookings/{bookingId}`로 보여 준다.

**말할 것**: `idem:{userId}:{Idempotency-Key}`에 첫 응답을 24시간 보관하고 그대로 돌려준다. 키에 사용자 ID를 넣는 이유는 다른 사람이 같은 키를 보냈을 때 **남의 예매 응답이 가지 않게** 하기 위함이다. 처리 중 키는 선점 TTL과 같이 짧게 두고, 늦게 끝난 요청이 남의 응답을 덮어쓰지 않게 처리권(owner)을 확인한다.

> 장면 3에서 잡은 선점은 장면 4 전에 해제한다(같은 계정 A가 장면 5에서 다시 선점해야 하므로): `curl -s -X DELETE "$API/bookings/{id}/hold" -H "Authorization: Bearer $TOKEN_A"`

### 장면 4 — 만료 2초 이내 해제 (90초)

**보여줄 것**: T-1분에 잡아 둔 좌석이 **화면에서 저절로 비워진다**.

1. B 화면에서 그 좌석이 아직 선점 중인 것을 보여 준다
2. 남은 시간을 말하고 기다린다
3. 만료되는 순간 **A·B 화면에서 2초 안에 좌석이 비워진다**
4. 대시보드 **만료 반영 지연**에서 값이 2초 선 아래인 것을 가리킨다

**말할 것**: 안전망이 세 겹이다 — Valkey keyspace 알림(즉시), `hold-expiry` 정렬 집합 1초 스캔(알림이 유실돼도), RDS 10초 스캔(최후). 세 경로가 같은 선점을 동시에 집어도 **조건부 UPDATE가 한 번만** 처리한다.

**Plan B**: 타이밍을 놓쳤으면 → A가 **「선점 취소하고 돌아가기」**(BKG-03)로 즉시 해제를 보여 주고 "만료도 같은 해제 경로를 탄다"고 설명한 뒤, 지표로 만료 지연을 보완한다.

### 장면 5 (선택) — 결제까지 (60초)

1. A에서 카드 `4242 4242 4242 4242` → **예매 완료**. B에서 그 좌석이 **진한 색(예매 완료)** 으로 바뀐다
2. 다른 좌석을 잡고 카드 끝 **`0000`** → **결제 실패** 안내. B에서 그 좌석이 **바로 비워진다**

**말할 것**: 거절은 고장이 아니라 결과라서 서버도 200 + `status`로 돌려준다. 그래서 블루/그린 자동 롤백 판정에서 `declined`를 제외한다 — 데모 중 거절 카드를 써도 배포가 롤백되지 않는다. 결제 ID는 booking이 먼저 정하고, 응답을 못 받으면 같은 ID로 1회 다시 부른 뒤 조회한다 — 모르는 결과를 실패로 단정하지 않는다.

> 카드 끝 `9999`(5초 지연 → 결과 미확인 503 → 재조회)는 대시보드에 5xx·`timeout`으로 잡혀 무중단 배포 데모(5.1.1)의 롤백 판정과 섞일 수 있다(런북 6-F-3). **좌석 데모에서는 쓰지 않는다.**

---

## 5. 데모 뒤 정리

```bash
# 남은 선점 해제 (다음 리허설을 위해). 예매 내역 API는 PENDING을 보여 주지 않으므로
# 장면 3 응답·~/.seatsac/expiring.json에 적어 둔 bookingId로 해제한다 (해제하지 않아도 300초 뒤 만료된다)
curl -s -X DELETE "$API/bookings/{bookingId}/hold" -H "Authorization: Bearer $TOKEN_A" -H "Idempotency-Key: $(uuidgen)"
```

- 경쟁 회차는 좌석이 지저분해지므로 **다음 리허설에는 다른 회차**를 쓰거나 5.1.1 초기화 런북을 돌린다
- 확정 예매를 만든 경우: 릴레이가 켜져 있으면 `booking_outbox_pending` 0 확인, 꺼져 있으면 아웃박스에 쌓인 것이 정상(`APP_OUTBOX_RELAY_ENABLED`, 런타임 계약 3장)

---

## 6. 데모 전에 해결해야 하는 것

| 항목 | 상태 (11/10) | 누구 |
| --- | --- | --- |
| 데모 회차 2개(시작 60분 이상 남은 회차) | `pick_targets.py`로 당일 선정 | 남궁일 |
| Cognito 계정 100개 + 토큰 | 도구 있음(#68), 계정 생성·발급은 4.2.1 실행 때 | 남궁일·김종식 (`cinema-admin`) |
| ~~`APP_SEAT_HOLD_TTL_SECONDS` 무효~~ | ✅ #76(11/06 배포) — 데모는 300초 유지(2장) | — |
| ~~`booking_seat_hold_total` 미구현~~ | ✅ #66 | — |
| `booking_seat_hold_total`·`booking_payment_call_total`의 `result` 라벨을 기동 때 0으로 등록하지 않음 — 충돌 99건이 한 수집 주기에 몰리면 처음 생긴 값이 `increase()`에서 빠져 장면 2 건수 카드가 작게 보일 수 있다(런북 6-F-3 전제와 다름, 11/10 staging에서 확인) | 미수정. booking 코드라 M6엔 불가 → rc2 뒤 `fix/`. 고치기 전에는 장면 2를 **k6 요약**으로 보여 준다 | 남궁일 |
| ~~좌석·결제 화면 머지~~ | ✅ #61(11/04) | — |
| 상영 5분 전 마감(#77) 화면 반영 — 좌석판 잠금·결제 마감 문구 | 미반영. M6엔 frontend 머지 불가 → rc2 뒤 `fix/` | 남궁일 |
| CloudFront SSE 4종 세트 확인 | `sse_lag.py`로 확인(체크리스트) | 남궁일 |
| 신호등 대시보드 적용 | 런북 6-F | 김종식 |
| `ng-ai` 0 · AI 질의 금지 공지 | 1차 데모 당일 스크럼 | 전원 |

> **회차**와 **토큰 100개**가 없으면 장면 2·4를 보여줄 수 없다. 4.2.1 부하 테스트(11/13까지) 때 계정·토큰을 만들어 두고, 리허설(11/16 전)에서 한 번 더 확인한다.

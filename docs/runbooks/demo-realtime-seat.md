# 실시간 좌석 데모 대본 (WBS 5.1.2)

> 초안 2026-10-30 · 남궁일. 발표 11/19~20, 리허설 11/16~18.
> 무중단 배포 데모는 5.1.1(김종식)이고 이 문서는 **실시간 좌석**만 다룬다.

## 0. 증명할 것

| # | 보여줄 것 | 목표 수치 | 어디서 확인 |
| --- | --- | --- | --- |
| 1 | 다른 화면에 반영 | **1초 이내** | 두 브라우저 나란히 (눈) |
| 2 | 같은 좌석 100명 동시 선점 | **성공 정확히 1명**, 선점 p95 **300ms 이하** | k6 Job 로그 (`hold_success`·`hold_latency`) |
| 3 | 같은 멱등 키 2회 요청 | 예매 **1건** | `curl` 2회 응답의 `bookingId` 동일 |
| 4 | 선점 만료 | **2초 이내** 좌석 해제 | 화면 + `booking_hold_expiry_lag_seconds` |

전체 **7분**. 결제까지 보여주면 9분.

---

## 1. 준비 (D-1까지)

### 1-1. 사람·장비

| 역할 | 누구 | 하는 일 |
| --- | --- | --- |
| 진행·설명 | 남궁일 | 브라우저 A 조작, 대본 진행 |
| 보조 | 김종식 | 브라우저 B(관찰자 화면)·Grafana·k6 Job 실행 |

- 화면은 **셋**을 띄운다: 브라우저 A(예매하는 사람) · 브라우저 B(다른 사람) · Grafana
- A와 B는 **서로 다른 Cognito 계정**이어야 한다 — 회차당 선점 1건 규칙 때문에 같은 계정이면 두 번째가 `ACTIVE_HOLD_EXISTS`로 막힌다
- B는 **새로고침 없이** 좌석판이 바뀌는 것을 보여주는 창이다. 절대 새로고침하지 않는다(SSE로 바뀌는 게 핵심)

### 1-2. 체크리스트

- [ ] 클러스터 가동 (데모 주간 11/16~20은 24시간, `cluster_keep_on`)
- [ ] 시드 적재 완료, **미래 회차** ID 확보 — 선점은 `startsAt > now`만 된다(지난 회차는 400 `SCREENING_STARTED`)
- [ ] 데모용 회차 **2개**: `DEMO_SCREENING`(장면 1·3·4), `CONTENTION_SCREENING`(장면 2). 100명이 몰린 회차를 다시 쓰면 좌석이 지저분해진다
- [ ] Cognito 계정: 데모용 2개(A·B) + **경쟁용 100개**(4.2.1과 공용, `AdminCreateUser`)
- [ ] 토큰 선발급 — Access Token 60분. **데모 직전에 다시 받는다**(k6는 테스트 중 로그인을 부르지 않는다)
- [ ] Grafana 대시보드 4개 패널 열어 두기 (아래 3장)
- [ ] CloudFront 경유 SSE 확인: `curl -N https://{배포}/api/screenings/{ID}/seats/stream` → 15초 ping이 **몰리지 않고 바로바로** 와야 한다(압축 끔·캐시 비활성이 안 돼 있으면 뭉쳐서 온다)
- [ ] 데이터 초기화 (5.1.1 런북): 예매 정리 · Valkey 키 삭제 · 회차 시드 재적재
- [ ] 리허설 1회 — **특히 장면 4의 타이밍**

### 1-3. 환경변수 (진행자 터미널)

```bash
export BASE=https://{배포ID}.cloudfront.net/api      # 화면과 같은 경로로 보여 준다
export DEMO_SCREENING=…        # 미래 회차 (장면 1·3·4)
export CONTENTION_SCREENING=…  # 미래 회차 (장면 2)
export TOKEN_A=…  TOKEN_B=…    # 데모 계정 2개
export K6_TOKENS="…,…,…"       # 경쟁용 100개 (쉼표 구분)
```

---

## 2. 타임라인

| 시각 | 할 일 | 왜 |
| --- | --- | --- |
| **T-10분** | 토큰 재발급, 브라우저 A·B 로그인, Grafana 열기 | Access Token 60분 |
| **T-6분** | **장면 4용 선점을 미리 잡는다** (아래 명령) | 선점 TTL이 **300초 고정**이라 데모 중에 만료시키려면 미리 잡아야 한다 |
| T-3분 | A·B를 `DEMO_SCREENING` 좌석 화면에 띄워 둔다 | B의 「실시간 반영 중」 표시 확인 |
| **T=0** | 장면 1 시작 | |
| T+5분경 | 장면 4 — 미리 잡은 선점이 만료 | T-6분 + 5분 = **T+(-1)분**… 아래 주의 참고 |

> **장면 4 타이밍 주의.** TTL 300초(5분)는 코드 상수다(`BookingPolicy.HOLD_TTL`). `APP_SEAT_HOLD_TTL_SECONDS`는 **지금 효과가 없다**(열린 결정, PR #63 본문). 그래서 만료 장면을 보여줄 시점 **정확히 5분 전**에 선점을 잡아야 한다. 장면 1~3이 약 4분이므로 **장면 1 시작 1분 전**에 잡는 것이 맞다.
>
> 설정이 실제로 먹게 고쳐지면(결정 ①) 데모 환경만 `APP_SEAT_HOLD_TTL_SECONDS=30`으로 두고 타이밍 부담을 없앨 수 있다 — 그게 더 안전하다.

```bash
# T-1분: 장면 4용 선점 (만료를 보여줄 좌석 1석). bookingId를 적어 둔다
curl -s -X POST "$BASE/seats/hold" \
  -H "Authorization: Bearer $TOKEN_B" -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"screeningId\":$DEMO_SCREENING,\"seatIds\":[<만료용 좌석ID>]}" | tee /tmp/expiring.json
```

---

## 3. Grafana 패널 (B 화면 옆에 띄워 둔다)

| 패널 | 쿼리 | 어느 장면에서 |
| --- | --- | --- |
| 선점 결과 | `sum by (result) (increase(booking_seat_hold_total[1m]))` | 장면 2 — `success` 1, `conflict` 99 · ⚠️ **지표 미구현**(아래 6장) |
| 선점 응답 p95 | `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{application="booking",uri="/seats/hold"}[1m])))` | 장면 2 — 300ms 선 |
| SSE 연결 수 | `booking_sse_connections` | 장면 1 — 2(A·B) |
| 만료 반영 지연 | `histogram_quantile(0.95, sum by (le) (rate(booking_hold_expiry_lag_seconds_bucket[5m])))` | 장면 4 — 2초 선 |

---

## 4. 장면별 대본

### 장면 1 — 다른 화면에 1초 이내 반영 (90초)

**보여줄 것**: A가 좌석을 잡으면 B 화면이 **새로고침 없이** 바뀐다.

1. A·B 모두 같은 회차 좌석 화면. B의 오른쪽 위 **「실시간 반영 중」** 초록 표시를 가리킨다
2. A에서 좌석 2개를 누른다 → **아직 아무 일도 일어나지 않는다**(화면 안 선택일 뿐). B는 그대로다
   > 설명: "좌석을 누르는 건 서버에 가지 않습니다. **잠금은 「좌석 선택 완료」를 누를 때** 걸립니다."
3. A가 **「좌석 선택 완료」** → A는 결제 화면으로, **B에서 그 두 좌석이 1초 안에 호박색(선점 중)으로 바뀐다**
4. A가 결제 화면에서 **「좌석 선택 취소」** → B에서 그 좌석이 **다시 비워진다**

**말할 것**: Valkey `SET NX EX 300` Lua 한 번으로 2석을 원자적으로 잡고, RDS 커밋 뒤 `seat-events:{회차}`에 발행 → 각 파드가 구독해 SSE로 내린다. 화면은 `seq`가 `lastSeq+1`일 때만 적용하고 빈틈이면 200ms 뒤 스냅샷을 다시 받는다.

**Plan B**: B가 안 바뀌면 → ① B의 라이브 표시가 꺼져 있는지 본다(끊기면 회색) ② 끊겼으면 B 새로고침(스냅샷으로 복구되는 것도 설계라고 설명) ③ CloudFront 압축/캐시 문제면 `curl -N`으로 이벤트가 오는 걸 터미널에서 보여 준다.

### 장면 2 — 같은 좌석 100명, 성공 1명 (90초)

**보여줄 것**: k6 Job 로그의 `hold_success: 1`.

```bash
# 클러스터 안 Job (OPS-10). gateway Service 직접 호출이라 /api 접두사가 없다
k6 run -e SCREENING_ID=$CONTENTION_SCREENING -e SEAT_ID=<좌석ID> \
       -e K6_TOKENS="$K6_TOKENS" tests/k6/seat-hold-contention.js
```

읽는 법 — 스크립트에 임계값이 걸려 있어 **어긋나면 k6가 실패로 끝난다**:

- `hold_success` **count==1** ← 데모의 핵심
- `hold_latency` **p(95)<300**
- `hold_seat_taken` 99 (정상), `hold_active_hold` **0** ← 0이 아니면 토큰이 겹쳐 측정이 무효다
- 동시에 B 화면에서 그 좌석이 선점 중으로 바뀐다

**말할 것**: 100개 요청이 Valkey 단일 스레드의 Lua 한 번에서 직렬화된다. 성공한 1명만 RDS에 `PENDING` 예매를 받고, 나머지는 409 `SEAT_UNAVAILABLE`로 좌석 목록을 돌려받는다. DB 행 잠금을 쓰면 커넥션 5개를 물고 대기하느라 p95가 무너진다.

**Plan B**: 토큰 100개가 준비되지 않으면 ① `VUS=20`으로 줄여 돌린다(성공 1명은 그대로 보인다) ② 또는 **클러스터 안에서 booking에 직접** 100개의 서로 다른 `X-User-Id`로 보낸다(gateway 우회, 분석 smoke와 같은 방식). 설명에 "실제 경로는 gateway를 지난다"를 덧붙인다.

### 장면 3 — 같은 멱등 키 2회 → 예매 1건 (60초)

```bash
KEY=$(uuidgen)
for i in 1 2; do
  curl -s -X POST "$BASE/seats/hold" \
    -H "Authorization: Bearer $TOKEN_A" -H "Content-Type: application/json" \
    -H "Idempotency-Key: $KEY" \
    -d "{\"screeningId\":$DEMO_SCREENING,\"seatIds\":[<좌석ID>]}" \
    | python3 -c 'import sys,json;d=json.load(sys.stdin);print(i if False else d.get("bookingId"), d.get("seatLabels"))'
done
curl -s "$BASE/users/me/bookings?page=0&size=5" -H "Authorization: Bearer $TOKEN_A" | head -c 300
```

**보여줄 것**: 두 응답의 `bookingId`가 **같다**. 예매 내역에도 1건.

**말할 것**: `idem:{userId}:{Idempotency-Key}`에 첫 응답을 24시간 보관하고 그대로 돌려준다. 키에 사용자 ID를 넣는 이유는 다른 사람이 같은 키를 보냈을 때 **남의 예매 응답이 가지 않게** 하기 위함이다. 최후 방어선은 `booking_seat`의 유일 제약.

### 장면 4 — 만료 2초 이내 해제 (90초)

**보여줄 것**: T-6분에 잡아 둔 좌석이 **화면에서 저절로 비워진다**.

1. B 화면에서 그 좌석이 아직 선점 중인 것을 보여 준다
2. 남은 시간을 말하고(결제 화면의 카운트다운을 A에서 보여 주면 더 좋다) 기다린다
3. 만료되는 순간 **B 화면에서 2초 안에 좌석이 비워진다**
4. Grafana **만료 반영 지연** 패널에서 값이 2초 선 아래인 것을 가리킨다

**말할 것**: 안전망이 세 겹이다 — Valkey keyspace 알림(즉시), `hold-expiry` 정렬 집합 1초 스캔(알림이 유실돼도), RDS 10초 스캔(최후). 세 경로가 같은 선점을 동시에 집어도 **조건부 UPDATE가 한 번만** 처리한다.

**Plan B**: 타이밍을 놓쳤으면 → A가 **「좌석 선택 취소」**(BKG-03)로 즉시 해제를 보여 주고 "만료도 같은 해제 경로를 탄다"고 설명한 뒤, 지표 패널로 만료 지연을 보완한다.

### 장면 5 (선택) — 결제까지 (60초)

1. A에서 카드 `4242 4242 4242 4242` → **예매 완료**. B에서 그 좌석이 **진한 색(예매 완료)** 으로 바뀐다
2. 다른 좌석을 잡고 카드 끝 **`0000`** → **결제 실패** 안내. B에서 그 좌석이 **바로 비워진다**

**말할 것**: 거절은 고장이 아니라 결과라서 서버도 200 + `status`로 돌려준다. 그래서 블루/그린 자동 롤백 판정에서 `declined`를 제외한다 — 데모 중 거절 카드를 써도 배포가 롤백되지 않는다.

---

## 5. 데모 뒤 정리

```bash
# 남은 선점 해제 (다음 리허설을 위해)
curl -s "$BASE/users/me/bookings?status=PENDING" -H "Authorization: Bearer $TOKEN_A"
# 각 bookingId에 대해
curl -s -X DELETE "$BASE/bookings/{id}/hold" -H "Authorization: Bearer $TOKEN_A"
```

- 경쟁 회차는 좌석이 지저분해지므로 **다음 리허설에는 다른 회차**를 쓰거나 5.1.1 초기화 런북을 돌린다
- 아웃박스에 쌓인 확정 알림이 SQS로 나갔는지 확인(릴레이가 켜져 있으면): `booking_outbox_pending` 0

---

## 6. 데모 전에 해결해야 하는 것

| 항목 | 상태 | 누구 |
| --- | --- | --- |
| 시드 적재 + **미래 회차** 2개 선정 | 대기 (3.1.12) | 김종식 → 내가 ID 확정 |
| Cognito 계정 100개 + 토큰 선발급 | 대기 | 김종식 (4.2.1 공용) |
| **`APP_SEAT_HOLD_TTL_SECONDS` 무효** — 데모용 짧은 TTL 불가 | 열린 결정 (PR #63) | 김종식과 합의 |
| **`booking_seat_hold_total` 지표 미구현** — 런타임 계약 5장 필수 지표인데 코드에 없다. 장면 2의 「성공 1 · 충돌 99」 패널과 3.1.7 신호등 대시보드가 이걸 쓴다(인계 #9) | **내 몫, 미착수** | 남궁일 |
| CloudFront SSE 4종 세트 확인 | 대기 (3.1.11) | 김종식 → 내가 `curl -N`으로 확인 |
| Grafana 패널 4개 | 대기 (3.1.7) | 김종식 |
| 좌석·결제 화면 머지 | PR #61 승인 대기 | 한채연 |

> 이 중 **시드 회차**와 **토큰 100개**가 없으면 장면 2·4를 보여줄 수 없다. 리허설(11/16) 전에 둘은 반드시 확보한다.

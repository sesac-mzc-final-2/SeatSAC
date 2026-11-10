# k6 부하 시나리오

클러스터 안 **Job**으로 돌리고 gateway Service를 직접 부른다(`http://gateway:8080`) — 그래서 **`/api` 접두사가 없다**. Job·스케줄은 김종식(OPS-10), 시나리오·대상 데이터는 남궁일(인계 15번).

| 시나리오 | 증명할 것 | 임계값 |
| --- | --- | --- |
| `seat-hold-contention.js` | 같은 좌석 100명 동시 → **성공 정확히 1명** | `hold_success == 1`, 선점 p95 < 300ms |
| `user-hold-limit.js` | 사용자당 진행 중 선점 **1건** (같은 회차 두 번째 선점 → 409 `ACTIVE_HOLD_EXISTS`, 해제 뒤 다시 201) | 체크 5개 전부 통과 |
| `browse.js` | 조회 캐시 적중 | 영화·상영시간 p95 < 100ms, 좌석 현황 p95 < 200ms, 실패율 0 |
| `deploy-during-load.js` | 블루/그린 전환 중 **오류율 0%** (결제 경로 포함) | 5xx·연결 오류 0, 확정 예매 > 0 |

임계값을 넘기면 k6가 **0이 아닌 코드로 끝난다** → Job 실패로 드러난다.

## 돌리기 전에 준비할 것

### 토큰 (`K6_TOKENS_FILE`)

Cognito 테스트 계정으로 **미리 발급**해 **파일로** 넘긴다(`K6_TOKENS_FILE`, 줄당 1개). 쉼표로 잇는 `K6_TOKENS`는 몇 개만 쓸 때의 대안이다 — 100개면 환경변수·인자 길이 한도에 걸릴 수 있다. k6 안에서 로그인하지 않는다(인증 쪽에 불필요한 부하를 주지 않기 위해).
계정·토큰은 `tools/loadtest_users.py`로 준비한다(Python 표준 라이브러리만, 관리 API만 `aws` CLI).

```bash
T=tests/k6/tools/loadtest_users.py
# 1) 계정 100개 — 관리자 역할(cinema-admin, MFA). 메일은 보내지 않는다(SUPPRESS).
#    상태 파일과 풀에 모두 있는 계정은 건너뛴다. 풀에만 있는 계정이 있으면 멈춘다 — 상태 파일을 잃은 경우에만
#    --adopt를 붙여 다시 실행한다(그 계정들의 비밀번호를 새로 정한다. 같은 주소로 직접 가입한 남의 계정일 수 있다)
python3 $T create --count 100 \
  --authority "$(gh variable get VITE_COGNITO_AUTHORITY)" --client-id "$(gh variable get VITE_COGNITO_CLIENT_ID)"
# 2) 토큰 — SRP 로그인. AWS 자격 증명이 필요 없다(공개 앱 클라이언트). 100명 × 2회 호출
python3 $T login
# 3) 돌리기 직전 — Access Token(60분)만 리프레시 토큰으로 새로 받는다(로그인 호출 없음, 리프레시 7일)
python3 $T refresh
python3 $T tokens --min-valid-minutes 30 --out ~/.seatsac/k6-tokens.txt   # 줄당 1개·권한 600. 30분 안에 만료될 토큰이 있으면 실패
export K6_TOKENS_FILE=~/.seatsac/k6-tokens.txt                            # k6는 절대 경로로 받는다
python3 $T status                                                         # 계정 수·가장 이른 만료
# 끝나면 (선택) 계정 삭제 — 관리자 역할
python3 $T delete
```

- 계정은 `loadtest+001@example.com` ~ `loadtest+100@example.com`(이름 `loadtest-001`…)이고 `ADMIN` 그룹에 넣지 않는다 — 들어 있으면 `create`가 멈추고, 나중에 넣었어도 `login`·`refresh`·`tokens`가 토큰의 `cognito:groups`를 보고 멈춘다
- 계정은 데모 주간(11/20)까지 두고 **Public 전환 전 `delete`**(런북 7-6-3)
- **비밀번호·토큰은 레포 밖** `~/.seatsac/loadtest-users.json`(권한 600)에만 둔다. 커밋·PR·노션에 붙이지 않는다. 다른 위치는 `--state`
- **토큰은 파일로 넘긴다**(`K6_TOKENS_FILE`). 토큰 하나가 1~1.2KB라 100개를 쉼표로 이으면 리눅스 env·인자 한도(128KiB)에 거의 닿는다. 몇 개만 쓸 때는 `K6_TOKENS`(쉼표)도 된다
- 클러스터 안 Job(OPS-10, 김종식): `run-in-cluster.sh`가 실행마다 Secret(토큰 파일)·ConfigMap(이 폴더 스크립트)·Job을 같은 이름으로 만들고 끝나면 지운다(GitOps 예외, 런북 6-H). 파드 라벨 `app.kubernetes.io/name=k6-loadtest`를 gitops `base/gateway/networkpolicy.yaml`이 허용한다 — 빠지면 요청이 전부 타임아웃된다
- 사용자 프로필(`GET /users/me`)은 만들지 않는다 — booking은 `X-User-Id`(= `sub`)만 쓴다
- 테스트: `python3 -m unittest discover -s tests/k6/tools` — 가짜 Cognito 서버가 서버 쪽 SRP로 서명을 검증한다(`pick_targets`·`sse_lag`는 가짜 조회 API로)

> **같은 좌석 경쟁 시나리오는 토큰이 VU 수만큼 필요하다.** booking은 **회차당 선점 1건**만 허용하므로(`user-hold`), 토큰이 겹치면 두 번째 요청이 좌석 때문이 아니라 사용자 제한(`ACTIVE_HOLD_EXISTS`)으로 막힌다 — 재려던 것이 가려진다. 그래서 스크립트가 `setup()`에서 토큰 수를 확인하고 부족하면 **바로 멈춘다**.

### 데이터

| 시나리오 | 환경변수 | 조건 |
| --- | --- | --- |
| contention | `SCREENING_ID`, `SEAT_ID` | 아직 아무도 잡지 않은 회차의 활성 좌석 하나 |
| user-hold | `SCREENING_ID`, `SEAT_IDS`(쉼표 2개) | 첫 번째 토큰 사용자가 선점하지 않은 회차(contention과 다른 회차)의 빈 좌석 2개 |
| browse | `SCREENING_IDS`(쉼표), **`THEATER_ID`**, `THEATER_DATE` | 시드가 적재된 회차 여러 개 + 그 극장 ID (`/screenings`는 `date`·`theaterId` 둘 다 필수) |
| deploy | `SCREENING_ID`, `SEAT_ID_FROM`, `VUS`, `SEATS_PER_VU` | 좌석이 **VU × SEATS_PER_VU**개 이상 남은 회차 |

`tools/pick_targets.py`가 공개 조회 API로 시나리오마다 **다른 회차**를 골라 환경변수로 내준다(시작 60분 이상 남은 회차 — 예매 마감이 시작 5분 전이다):

```bash
eval "$(python3 tests/k6/tools/pick_targets.py --base-url "https://{배포ID}.cloudfront.net/api")"
# → CONTENTION_SCREENING_ID·CONTENTION_SEAT_ID · USERHOLD_SCREENING_ID·USERHOLD_SEAT_IDS
#   DEPLOY_SCREENING_ID·DEPLOY_SEAT_ID_FROM · BROWSE_SCREENING_IDS·THEATER_ID·THEATER_DATE
```

`seat_id = auditorium_id × 10000 + 순번`이다(시드 규칙). 상영관 하나가 100~300석이라 `deploy`는 VU 10 × 20석 = 200석을 쓰므로 **큰 상영관**을 고른다.

### 다시 돌릴 때

선점 TTL이 **300초**다. 같은 좌석으로 다시 돌리려면 그만큼 기다리거나 `DELETE /bookings/{id}/hold`로 풀어 둔다.

`deploy`는 확정한 예매를 **바로 취소해 좌석을 반납**하므로 한 번 돌리는 동안에는 좌석이 계속 돌아간다(그렇게 하지 않으면 `SEATS_PER_VU`번 반복한 뒤 선점이 전부 409가 되고 결제 경로가 돌지 않는다). 다만 중간에 끊으면 확정된 예매가 남을 수 있으니 다시 돌리기 전에 `hold_conflict_rate`를 보고, 높으면 `SEAT_ID_FROM`을 옮긴다.

## 클러스터에서 돌리기 (4.2.1 — 런북 6-H)

관리자(`cinema-admin`)가 레포 루트에서 돌린다. 결과 로그는 `~/.seatsac/k6-results/{실행 ID}.log`(레포 밖).

```bash
R=tests/k6/run-in-cluster.sh
$R user-hold  -e SCREENING_ID=$USERHOLD_SCREENING_ID -e SEAT_IDS=$USERHOLD_SEAT_IDS
$R contention -e SCREENING_ID=$CONTENTION_SCREENING_ID -e SEAT_ID=$CONTENTION_SEAT_ID
$R browse     -e SCREENING_IDS=$BROWSE_SCREENING_IDS -e THEATER_ID=$THEATER_ID -e THEATER_DATE=$THEATER_DATE
$R deploy     -e SCREENING_ID=$DEPLOY_SCREENING_ID -e SEAT_ID_FROM=$DEPLOY_SEAT_ID_FROM -e DURATION=2m
$R cleanup    # 남은 k6 리소스 지우기
```

- 토큰은 `K6_TOKENS_FILE`(권한 600)로만 받는다. `-e K6_TOKENS…`는 막는다(명령 기록·Job 인자에 남는다)
- 사전 점검: 권한 · gateway Ready · 다른 k6 Job 없음 · **ai 파드 없음**(ADR-87 — 무시하려면 `ALLOW_AI=1`) · 노드 4대 미만이면 경고
- 종료 코드: 0 통과 · 99 임계값 실패 · 1 실행 실패 · 2 사전 점검 실패. `DRY_RUN=1`이면 Job 매니페스트만 출력한다

## 실행 예 (로컬·직접 k6)

```bash
# 같은 좌석 100명 (토큰 100개 필요)
k6 run -e BASE_URL=http://gateway:8080 -e SCREENING_ID=5123 -e SEAT_ID=4060121 \
       -e K6_TOKENS_FILE="$K6_TOKENS_FILE" tests/k6/seat-hold-contention.js

# 조회 (토큰 불필요)
k6 run -e SCREENING_IDS=5123,5124,5125 -e THEATER_ID=1004 tests/k6/browse.js

# 배포 중 부하 — 띄워 둔 상태에서 새 버전을 배포한다
k6 run -e SCREENING_ID=5123 -e SEAT_ID_FROM=4060001 -e VUS=10 -e SEATS_PER_VU=20 \
       -e DURATION=5m -e K6_TOKENS_FILE="$K6_TOKENS_FILE" tests/k6/deploy-during-load.js
```

## 읽는 법 — 0이어야 하는 것

- **`server_errors`**(5xx·연결 오류) — 무중단 배포의 합격선이다. `preStop 5초`+`graceful 20초`와 `scaleDownDelaySeconds 60`이 제대로 걸려 있으면 0이다
- **`hold_active_hold`** — contention에서 0이 아니면 **토큰이 부족한 것**이고 측정이 무효다
- **`payment_result_unknown`** — 지연 카드(`9999`)를 쓰지 않았는데 0이 아니면 Payment 응답이 3초를 넘긴 것이다. 그 자체로 오류는 아니지만(예매는 `PENDING`이고 재조회로 확정) 전환 중이라면 들여다볼 신호다

- **`payment_unresolved`** — 503 뒤 단건 조회로 종료 상태(`CONFIRMED`·`PAYMENT_FAILED`·`EXPIRED`·`CANCELED`)를 끝까지 못 본 건이다. 0이어야 한다
- **`bookings_canceled`** — 좌석 반납 수. 확정 수와 크게 다르면 취소가 밀린 것이다
- **`hold_conflict_rate`** — 선점 409 비율. 10%를 넘으면 좌석 구간이 겹쳤거나 반납이 밀렸다

> k6 안에서만 쓰는 메트릭이다(위 `hold_*`·`browse_*`·`server_errors` 포함). Prometheus로 수집하지 않으므로 `docs/conventions.md` 7장의 서비스 메트릭 이름 규칙(`{service}_{대상}_{단위}`)과는 별개다 — k6 Job 로그와 임계값에서만 쓴다.

503 `PAYMENT_RESULT_UNKNOWN`은 **실패로 세지 않는다** — 설계상 「결과 미확인」이고 화면이 단건 조회로 복구한다. 그 밖의 5xx는 전부 실패다 — 배포 중 gateway가 내는 503 `SERVICE_UNAVAILABLE`도 포함이다(면제는 결제 경로의 `PAYMENT_RESULT_UNKNOWN` 하나뿐이다).

## SSE 반영 지연 — k6 밖에서 잰다

**「다른 화면 반영 1초 이내」** 는 k6에 SSE 클라이언트가 없어 `tools/sse_lag.py`로 잰다. 화면과 같은 경로(CloudFront)로 스트림을 열고, 이벤트마다 `받은 시각 − occurredAt`을 찍는다(이 PC 시계가 동기화돼 있어야 한다). 시나리오를 돌리는 동안 다른 터미널에서 연다:

```bash
python3 tests/k6/tools/sse_lag.py --duration 150 \
  --url "https://{배포ID}.cloudfront.net/api/screenings/$DEPLOY_SCREENING_ID/seats/stream"
# → 이벤트 N건 · 지연 p50/p95/최대 · 하트비트 간격(15초 안팎이어야 한다). p95 > 1초면 종료 코드 1
```

눈으로만 볼 때는 `curl -N`도 된다. 하트비트가 15초마다 와야 하고, 선점 이벤트는 1초 안에 와야 한다.

```bash
curl -N -H "Accept: text/event-stream" \
  "https://{배포ID}.cloudfront.net/api/screenings/5123/seats/stream"
```

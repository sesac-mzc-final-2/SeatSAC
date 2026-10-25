# k6 부하 시나리오

클러스터 안 **Job**으로 돌리고 gateway Service를 직접 부른다(`http://gateway:8080`) — 그래서 **`/api` 접두사가 없다**. Job·스케줄은 김종식(OPS-10), 시나리오·대상 데이터는 남궁일(인계 15번).

| 시나리오 | 증명할 것 | 임계값 |
| --- | --- | --- |
| `seat-hold-contention.js` | 같은 좌석 100명 동시 → **성공 정확히 1명** | `hold_success == 1`, 선점 p95 < 300ms |
| `browse.js` | 조회 캐시 적중 | 영화·상영시간 p95 < 100ms, 좌석 현황 p95 < 200ms, 실패율 0 |
| `deploy-during-load.js` | 블루/그린 전환 중 **오류율 0%** (결제 경로 포함) | 5xx·연결 오류 0, 확정 예매 > 0 |

임계값을 넘기면 k6가 **0이 아닌 코드로 끝난다** → Job 실패로 드러난다.

## 돌리기 전에 준비할 것

### 토큰 (`K6_TOKENS`)

Cognito 테스트 계정으로 **미리 발급**해 쉼표로 넘긴다. k6 안에서 로그인하지 않는다(인증 쪽에 불필요한 부하를 주지 않기 위해).

```bash
K6_TOKENS="eyJ...,eyJ...,eyJ..."
```

> **같은 좌석 경쟁 시나리오는 토큰이 VU 수만큼 필요하다.** booking은 **회차당 선점 1건**만 허용하므로(`user-hold`), 토큰이 겹치면 두 번째 요청이 좌석 때문이 아니라 사용자 제한(`ACTIVE_HOLD_EXISTS`)으로 막힌다 — 재려던 것이 가려진다. 그래서 스크립트가 `setup()`에서 토큰 수를 확인하고 부족하면 **바로 멈춘다**.

### 데이터

| 시나리오 | 환경변수 | 조건 |
| --- | --- | --- |
| contention | `SCREENING_ID`, `SEAT_ID` | 아직 아무도 잡지 않은 회차의 활성 좌석 하나 |
| browse | `SCREENING_IDS`(쉼표), `THEATER_DATE` | 시드가 적재된 회차 여러 개 |
| deploy | `SCREENING_ID`, `SEAT_ID_FROM`, `VUS`, `SEATS_PER_VU` | 좌석이 **VU × SEATS_PER_VU**개 이상 남은 회차 |

`seat_id = auditorium_id × 10000 + 순번`이다(시드 규칙). 상영관 하나가 100~300석이라 `deploy`는 VU 10 × 20석 = 200석을 쓰므로 **큰 상영관**을 고른다.

### 다시 돌릴 때

선점 TTL이 **300초**다. 같은 좌석으로 다시 돌리려면 그만큼 기다리거나 `DELETE /bookings/{id}/hold`로 풀어 둔다. `deploy`는 확정(결제 승인)을 만들기 때문에 **같은 좌석을 두 번 쓸 수 없다** — `SEAT_ID_FROM`을 옮기거나 회차를 바꾼다.

## 실행 예

```bash
# 같은 좌석 100명 (토큰 100개 필요)
k6 run -e BASE_URL=http://gateway:8080 -e SCREENING_ID=5123 -e SEAT_ID=4060121 \
       -e K6_TOKENS="$TOKENS" tests/k6/seat-hold-contention.js

# 조회 (토큰 불필요)
k6 run -e SCREENING_IDS=5123,5124,5125 tests/k6/browse.js

# 배포 중 부하 — 띄워 둔 상태에서 새 버전을 배포한다
k6 run -e SCREENING_ID=5123 -e SEAT_ID_FROM=4060001 -e VUS=10 -e SEATS_PER_VU=20 \
       -e DURATION=5m -e K6_TOKENS="$TOKENS" tests/k6/deploy-during-load.js
```

## 읽는 법 — 0이어야 하는 것

- **`server_errors`**(5xx·연결 오류) — 무중단 배포의 합격선이다. `preStop 5초`+`graceful 20초`와 `scaleDownDelaySeconds 60`이 제대로 걸려 있으면 0이다
- **`hold_active_hold`** — contention에서 0이 아니면 **토큰이 부족한 것**이고 측정이 무효다
- **`payment_result_unknown`** — 지연 카드(`9999`)를 쓰지 않았는데 0이 아니면 Payment 응답이 3초를 넘긴 것이다. 그 자체로 오류는 아니지만(예매는 `PENDING`이고 재조회로 확정) 전환 중이라면 들여다볼 신호다

503 `PAYMENT_RESULT_UNKNOWN`은 **실패로 세지 않는다** — 설계상 「결과 미확인」이고 화면이 단건 조회로 복구한다. 그 밖의 5xx는 전부 실패다.

## 아직 k6로 재지 않는 것

**SSE 반영 지연(「다른 화면 반영 1초 이내」)** 은 k6에 SSE 클라이언트가 없어 스크립트로 재지 않는다. 데모 때 `curl -N`으로 스트림을 열어 두고 다른 창에서 선점해 눈으로 확인한다(런북). 하트비트가 15초마다 와야 하고, 선점 이벤트는 1초 안에 와야 한다.

```bash
curl -N -H "Accept: text/event-stream" \
  "https://{배포ID}.cloudfront.net/api/screenings/5123/seats/stream"
```

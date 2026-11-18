# 장애 격리 · 독립 확장 시연 녹화 (WBS 5.1.5 · 5.1.6)

> 2026-11-18 · 김종식. MSA로 나눈 효과 두 가지를 녹화한다 — **한 서비스가 죽어도 나머지는 정상(5.1.5)**, **부하가 몰린 서비스만 늘어난다(5.1.6)**.
> 부하 테스트 실행은 `docs/runbooks/terraform-infra.md` 6-H, 신호등 대시보드는 6-F, 데이터 정리는 `demo-zero-downtime.md` 1장.

<노션 반영 필요> 5.1.5·5.1.6 행 — 녹화 뒤 결과(아래 각 장 「기록」)와 영상 위치.

## 0. 공통 준비

| 확인 | 방법 | 기대 |
| --- | --- | --- |
| 다른 시연·측정과 안 겹침 | 스크럼 공유 | k6(4.2.x·open-rush)·AI 데모·블루/그린이 돌지 않는다 |
| 배포 겹침 없음 | `kubectl -n cinema-staging get rollout` | booking·gateway Healthy — 녹화 중 booking·gateway PR은 머지하지 않는다 |
| 노드 | `infra/scripts/cluster-scale.sh status` | 5.1.6은 **4대**(`up 2`) — k6 파드가 catalog와 CPU를 다투지 않고, catalog가 4개까지 늘 자리가 있다 |
| ai | `kubectl -n cinema-staging get pod -l app.kubernetes.io/name=ai` | 5.1.6은 `ng-ai` 0(스냅샷 적재 호출이 catalog 부하에 섞이지 않게 — `run-in-cluster.sh`가 ai 파드가 있으면 멈춘다). 5.1.5는 상관없다 |
| 화면 | 브라우저 + Grafana **SeatSAC 신호등**(6-F) | 시간 범위 「최근 15분」, 새로고침 10s |

```bash
export AWS_PROFILE=cinema-admin && kubectl config use-context cinema-admin
```

## 1. 장애 격리 (5.1.5) — payment만 끈다

**증명할 것**: payment 파드가 0개여도 조회·선점은 정상이고 **결제만** 실패한다. 복구하면 결제가 다시 된다.

| PR (SeatSAC-gitops) | 내용 |
| --- | --- |
| #33 | `overlays/staging/payment` `replicas: 0` [원복 예정] |
| #34 | #33 되돌림(replicas 1) — #33 머지 뒤 base를 main으로 바꿔 Draft를 푼다 |

1. **#33 머지** → `kubectl -n cinema-staging get deploy payment` `0/0` (ArgoCD `cinema-payment` Synced)
2. 화면: 메인·상영시간 → 좌석 현황 → **선점 성공** → 결제 → **실패 안내**(503 `PAYMENT_RESULT_UNKNOWN` — booking이 payment에 연결하지 못하고 1회 재시도·조회 뒤 포기). 몇 번 반복하면 booking의 `payment` 서킷이 열려 바로 실패한다
3. 신호등: **payment 회색**(트래픽 없음), booking은 결제 503으로 노랑·빨강일 수 있다, **catalog·user·gateway 초록** — 「장애가 결제에만 갇혔다」 장면. ⑤ 「결제 호출 결과 / 초」에 `error`
4. **복구 — #34 머지** → payment `1/1` Ready(약 1분) → 10초쯤 뒤 서킷 반열림 → 결제 재시도 성공
5. 남은 PENDING 예매는 만료 스캔이 결제 상태를 조회해 정리한다(몇 분). 그래도 남으면 `demo-zero-downtime.md` 1장

기록: 녹화 시각 · 결제 실패 응답(코드·reason) · 복구에 걸린 시간(#34 머지 → 결제 성공)

## 2. 독립 확장 (5.1.6) — catalog만 늘린다

**증명할 것**: 조회 부하가 몰린 **catalog만 HPA로 1 → N개**가 되고, booking·user·payment·gateway 파드 수는 그대로다.

- 부하: `browse.js`의 `TARGET=catalog` — 영화 목록·영화 상세·극장 목록만 부른다. 좌석 현황(booking)과 `/screenings`(catalog가 booking `seat-counts`를 부름)는 빼서 **booking HPA가 움직이지 않게** 한다
- catalog HPA: CPU 70%(요청 200m의 140m), 1~4개, **1분에 1개씩** 늘고 부하가 끝나면 5분 뒤부터 준다(`base/catalog/hpa.yaml`). 1 → 4까지 약 4분이라 유지 6분으로 돌린다

터미널 두 개를 띄운다.

```bash
# 터미널 1 — 화면에 남길 것: catalog만 REPLICAS가 오르고 booking·user는 1
kubectl -n cinema-staging get hpa -w
```

```bash
# 터미널 2 — 부하(약 7분 = 올리기 30초 + 6분 + 내리기 15초)
tests/k6/run-in-cluster.sh browse -e TARGET=catalog -e VUS=60 -e DURATION=6m
```

- 같이 보여 줄 것: `kubectl -n cinema-staging get pods -L app.kubernetes.io/name`(catalog 파드만 늘어남) · `kubectl -n cinema-staging top pods` · 신호등 ①(모두 초록)
- **2분이 지나도 catalog TARGETS가 70%를 넘지 않으면** `VUS=80`으로 다시 돌린다(캐시 적중이라 요청당 CPU가 작다). gateway는 HPA가 없어 1개 그대로 같은 부하를 받는다 — 「gateway는 늘지 않는데 catalog만 는다」도 같은 장면이다
- VU를 올리면 p95 임계값(100ms)이 깨져 k6가 종료 코드 99로 끝날 수 있다 — HPA 장면이 목적이라 그대로 둔다(`tests/k6/README.md`)
- 끝나고 5분쯤 지나면 catalog가 1개로 돌아온다(줄어드는 장면까지 찍으려면 기다린다). 노드는 `cluster-scale.sh up 1`로 되돌린다

기록: 녹화 시각 · catalog 최대 개수와 1 → N 걸린 시간 · 그동안 booking·user·payment·gateway 개수(1 그대로) · k6 요약(요청 수·p95)

## 3. 문제가 생기면

| 증상 | 원인·대응 |
| --- | --- |
| (5.1.5) 선점까지 실패 | payment가 아니라 다른 원인 — 신호등에서 빨간 서비스 확인. 바로 #34로 복구 |
| (5.1.5) #34가 diff 없음 | #33 머지 뒤 base를 main으로 바꾸지 않았다 — 김종식이 main을 합쳐 다시 올린다 |
| (5.1.6) `ai 파드가 떠 있다`로 멈춤 | `cluster-scale.sh down ng-ai` 뒤 다시(AI를 켜 둔 채 해야 하면 `ALLOW_AI=1` — 스냅샷 호출이 섞인다) |
| (5.1.6) booking도 늘었다 | `TARGET=catalog`를 빠뜨렸다(기본 `all`은 좌석 현황을 부른다) |
| (5.1.6) catalog 파드 Pending | 노드 자리 부족 — 노드 4대인지 확인(`up 2`) |
| (5.1.6) k6 파드 Pending 180초 | 노드 부족 — 같은 조치 |

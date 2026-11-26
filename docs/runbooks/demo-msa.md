# MSA 고도화 시연 런북 — 정지/가동 · AZ 장애 (WBS 6.5.2b)

> 2026-11-27 · 김종식. 통합 시연 11/27(M11, ADR-114). 증명할 것: **서비스 하나(또는 AZ 하나)를 멈춰도 나머지가 동작한다**, 관리자 `/ops`에서 상태를 보고 멈추고 되살린다(ADR-119·122).
> 절차의 근거·문제 해결은 `docs/runbooks/terraform-infra.md` 6-L(ops-api)·6-N(통합 테스트)·6-O(AZ 장애·비용 정리). 쿠폰 → 체크아웃 장면(6.5.2a)·관리자 리포트(6.5.2c)·AI(6.5.2d)는 각 담당 대본.

---

## 0. 원칙

| 원칙 | 내용 |
| --- | --- |
| 되돌리고 끝낸다 | 정지한 서비스는 시연 직후 「가동」, cordon한 노드는 uncordon. **Git과 replicas가 다른 상태로 두지 않는다**(repo-workflow 2장 ⑤) — 끝에 `msa-check.sh all` |
| 겹치지 않는다 | booking·gateway 블루/그린, 결제 비교 측정(6.2.7), 부하 테스트(6-H)가 도는 동안 하지 않는다. 정지 409 `ROLLOUT_IN_PROGRESS`면 전환이 끝날 때까지 기다린다 |
| 한 명이 조작 | 관리자 화면·터미널 조작은 김종식 한 명. 다른 사람은 사용자 화면만 |
| 녹화 | 리허설 1회를 녹화해 둔다(본 시연이 막히면 그 영상으로 대신 — 5장) |

## 1. 준비 (T-30분)

```bash
export AWS_PROFILE=cinema-admin
infra/scripts/msa-check.sh all; echo "exit=$?"   # exit=0 · 모두 Healthy · 정지 기록 없음 · 핵심 서비스가 두 AZ에
```

- [ ] 화면 탭 4개: ① 사이트 메인(일반 계정) ② 관리자 `/ops`(시연 계정 — `ADMIN` 그룹, 그룹 지정 뒤 다시 로그인) ③ Grafana **SeatSAC 신호등** ④ 터미널
- [ ] 사용자 화면에서 미리 확인: 상영시간표 · 좌석 화면 · 마이페이지 예매 내역 · 쿠폰 페이지가 모두 열린다
- [ ] 배경 트래픽(선택): k6 browse 클러스터 Job(6-H, `VUS` 작게) — 신호등이 「트래픽 없음」 회색이면 장면이 안 보인다
- [ ] Karpenter disruption budget 0 PR이 머지돼 있다(6-K) · `kubectl -n cinema-staging get rollout`이 모두 Healthy(진행 중 아님)

## 2. 장면 A — 서비스 하나 정지 · 가동 (약 4분)

| 시간 | 화면 | 조작 | 말 |
| --- | --- | --- | --- |
| 0:00 | ② `/ops` | 서비스 표를 보여 준다(replicas·Ready·신호등·노드 CPU) | 「서비스 8개가 각자 따로 떠 있고, 관리자 화면은 gateway를 거치지 않고 따로 들어온다」 |
| 0:30 | ② | **user 정지** | 「회원 서비스를 멈춰 보겠다」 |
| 0:45 | ① | 마이페이지 → 예매 내역 오류 안내 · **상영시간표·좌석 선점·결제는 그대로** | 「마이페이지만 안 되고, 예매는 계속된다. user는 자기 DB와 복제본만 쓰고 예매 경로에 끼어 있지 않다」 |
| 1:30 | ② | **coupon 정지** | 「쿠폰 서비스도 멈춘다」 |
| 1:45 | ① | 쿠폰 페이지 안내 · 쿠폰 없이 결제 → 확정 | 「쿠폰을 못 써도 결제는 된다. 쿠폰을 고르고 결제했다면 쿠폰 단계가 10초 안에 답하지 않아 체크아웃이 닫히고 보상(쿠폰 반환·좌석 해제)이 돈다 — 돈이 움직이기 전이라 결제는 없다」 |
| 2:30 | ③ | 신호등 — user·coupon은 「트래픽 없음/장애」, booking·catalog·gateway는 정상 | 「장애가 그 서비스에서 멈춘다」 |
| 3:00 | ② | **user 가동 · coupon 가동** → Ready 2/2 | 「정지 전 replicas를 기억해 두었다가 그대로 되살린다」 |
| 3:30 | ① | 마이페이지 다시 정상 — 멈춘 동안 결제한 예매도 보인다 | 「멈춘 동안 온 예매 이벤트는 큐에 쌓였다가 가동하자마자 반영됐다」 (`APP_BOOKING_HISTORY_MODE=local`일 때만 이 말을 한다) |

- 리허설에서 한 번 확인: user 정지 중 화면의 다른 곳(헤더 이름 등 `GET /users/me`)이 어떻게 보이는지 — 결제 경로가 막히면 장면 A의 첫 대상을 coupon으로 바꾼다
- 정지 대상은 **user·coupon**(사용자 경로 밖). booking·gateway·catalog 정지는 예매 자체가 멈춰 「나머지가 동작한다」가 안 보인다 — 질문이 나오면 「관리자 화면은 gateway를 정지해도 살아 있다」는 말로만(ADR-119)
- 정지·가동은 감사 로그가 남는다: `kubectl -n cinema-staging logs deploy/ops-api | grep '"ops audit"'`

## 3. 장면 B — AZ 하나 장애 (약 5분)

명령은 `terraform-infra.md` 6-O 그대로(전제·되돌리기·문제 해결도 거기). 여기에는 화면과 말만 둔다.

| 시간 | 화면 | 조작 (6-O) | 말 |
| --- | --- | --- | --- |
| 0:00 | ④ | `msa-check.sh az` | 「서비스마다 파드 2개를 AZ a·c에 나눠 두었다(replicas 2 + topologySpread)」 |
| 0:30 | ④ → ② | AZ a 노드 cordon·drain | 「AZ a 서버가 전부 꺼진 상황을 만든다」 |
| 1:30 | ② · ① | `/ops` 노드 표에서 AZ a 노드가 비고, 사이트는 계속 열린다 | 「파드가 AZ c로 옮겨 갔다. 인터넷 출구(NAT)도 AZ마다 따로라 c 쪽이 혼자 나간다」 |
| 2:30 | ④ | booking DB 강제 장애 조치 `reboot-db-instance --force-failover` | 「DB도 AZ마다 대기 서버가 있다(Multi-AZ). 예매 DB를 반대편으로 넘긴다」 |
| 3:00 | ① · ③ | 1~2분 동안 선점·결제만 실패 가능, 조회·마이페이지는 정상 → 다시 정상 | 「DB가 넘어가는 동안 그 서비스만 잠깐 실패하고, 앱은 재시작 없이 다시 붙는다」 |
| 4:30 | ④ | `msa-check.sh data` — booking-db 현재 AZ가 바뀜 · uncordon | 「되돌린다」 |

- Valkey 장애 조치(6-O 3)는 좌석 선점 키가 있는 클러스터라 **실시간 좌석 장면과 같은 날이면 빼고** 말로만
- 녹화 시 터미널 글꼴을 키우고, 출력에 계정 ID·주소가 없는 명령만 쓴다(`msa-check.sh`는 이름만 찍는다). `aws rds reboot-db-instance`는 `--query 'DBInstance.DBInstanceStatus'`로 상태만 보이게 한다

## 4. 끝내기 (시연 직후)

```bash
for n in $(kubectl get nodes -o name); do kubectl uncordon "$n" >/dev/null; done
infra/scripts/msa-check.sh all; echo "exit=$?"    # exit=0 · 정지 기록 없음 · cordon 없음 · DLQ 0
```

- [ ] `/ops`에서 모든 서비스 Ready(정지 없음) · ArgoCD 모두 Synced·Healthy
- [ ] DLQ 0(정지 동안 쌓인 명령·이벤트가 다 흘러갔다)
- [ ] 노션 6.5.2b에 녹화 링크·소요·막힌 곳, `terraform-infra.md` 14장에 한 줄
- [ ] 그날 마지막 시연이면 비용 정리(6-O-1)

## 5. 막힐 때

| 상황 | 대신 할 것 |
| --- | --- |
| `/ops` 401·403 | 시연 계정 로그아웃 → 다시 로그인(그룹은 토큰 재발급 뒤 반영, 7-6-5) |
| 정지 409 `ROLLOUT_IN_PROGRESS` | 블루/그린이 끝날 때까지 장면 B를 먼저 |
| drain이 5분 넘게 멈춤 | 그 노드 uncordon, 녹화 영상으로 장면 B 대신 |
| RDS 장애 조치가 5분 넘게 `rebooting` | 장면을 넘기고 끝에 `msa-check.sh data`로 결과만 보인다 |
| 가동 뒤 Ready가 안 됨 | `kubectl -n cinema-staging describe pod -l app.kubernetes.io/name=<svc>` — 노드 자리 부족이면 Karpenter가 1~2분 안에 노드를 더한다 |

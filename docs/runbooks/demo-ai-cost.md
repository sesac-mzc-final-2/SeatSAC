# AI 비용 절감 데모 대본 (WBS 5.1.3, 2차 데모)

> 2026-11-18 · 김종식(`ng-ai` 기동·대시보드·캡처·정리) · 김서현(질문 시연·4.2.3 결과 설명).
> 증명할 것(`CLAUDE.md` 0장): 사용자 질문을 캐시·프리셋·NL2SQL로 처리하고, **반복·유사 질문에서 Claude 토큰이 0**이 되는 것. 절감률·오적중률을 같이 보여 준다.
> 켜기·끄기는 `docs/runbooks/terraform-infra.md` 6-G, 대시보드 읽는 법은 6-G-8, 지표 식은 `services/ai/README.md` 「지표 읽는 법」.

<노션 반영 필요> 노션 5.1.3 행은 아직 빈 템플릿이다. 데모가 끝난 뒤 7장 결과 표와 캡처 위치를 옮긴다.

## 0. 전제 확인 (T-40분, 김종식)

```bash
export AWS_PROFILE=cinema-admin && kubectl config use-context cinema-admin
export API="https://$(cd infra/terraform/envs/staging && terraform output -raw cloudfront_domain_name)/api"
TOKEN=$(python3 tests/k6/tools/loadtest_users.py tokens --count 1 --min-valid-minutes 30)   # 셸 기록에 값이 남지 않게 변수로만
```

| 확인 | 방법 | 기대 |
| --- | --- | --- |
| 플래그 켜짐 | `curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" "$API/ai/presets"` | **503**(플래그 켜짐 · ai 파드 아직 없음). **404면 플래그 꺼짐** → 6-G-3 1번. 토큰 없이 부르면 플래그와 상관없이 401이라(인증이 라우팅보다 먼저) 확인이 안 된다 |
| 질의 금지 구간 아님 | 런북 6-G-5 | 이 시간에 k6(4.2.x·browse·open-rush)·좌석 녹화·블루/그린이 돌지 않는다. 스크럼에 「AI 데모 ○시~○시」 공유 |
| 배포 겹침 없음 | `kubectl -n cinema-staging get rollout` | booking·gateway Healthy. gateway PR(#132 등)은 데모 뒤에 머지 |
| 노드 | `infra/scripts/cluster-scale.sh status` | 앱 노드 정상, `ng-ai` 0 |

## 1. 대시보드 (T-30분, 김종식)

`SeatSAC AI` 대시보드(3.1.22)는 SeatSAC-gitops #28 `observability/dashboards/seatsac-ai.json`이다.

- **머지돼 있으면**: Grafana → Dashboards → **SeatSAC AI**. 없으면 런북 6-G-8 「목록에 SeatSAC AI가 없음」
- **아직 안 됐으면 gitops #28을 머지**한다. `cinema-observability`만 sync되고(ConfigMap 하나) 서비스 배포·Grafana 재시작이 없어 1분 안에 뜬다
- 머지할 시간이 없으면 Grafana → Dashboards → New → Import에 #28 브랜치의 같은 JSON을 붙여 넣는다. Grafana 파드가 재시작되면 사라진다(영속 저장소 없음). 데모 뒤 #28을 머지한다

```bash
kubectl -n monitoring port-forward svc/kube-prometheus-stack-grafana 3000:80   # http://localhost:3000
```

데모 전에는 기본 범위(최근 30분)로 ② 「ai 상태」만 본다. **시간 범위는 데모를 시작할 때** 바꾼다(3장).

## 2. ai 켜기 (T-25분, 김종식) — 런북 6-G-2 A

> 11/18: ai는 발표(11/20)까지 **켜 둔다**(김서현). 이미 켜져 있으면 `up ng-ai`는 건너뛰고 아래 확인만 한다. 좌석 시연·k6 측정 전에는 런북 6-G-5대로 `down ng-ai`

```bash
kubectl -n argocd get application cinema-ai                                # Synced
infra/scripts/cluster-scale.sh up ng-ai
kubectl -n cinema-staging rollout status deployment/ai --timeout=10m
kubectl -n cinema-staging get pod -l app.kubernetes.io/name=ai -o wide     # NODE = ng-ai, READY 1/1
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" "$API/ai/presets"   # 200
unset TOKEN
```

- 대시보드 ②: 「ai 파드 Ready」 1(초록), 「스냅샷 나이 · 오늘」 3분 미만
- 로그인한 화면에서 챗봇을 열어 추천 질문 목록이 보이면 준비 끝
- **리허설**: 1~4번은 #143의 버튼 질문 예열로 서버가 켜질 때부터 캐시에 있으므로 리허설과 무관하다. 리허설이 데모를 바꾸는 건 **5번 틀(「오늘 {극장} 리클라이너 회차 있어?」)** 하나다 — 데모 1시간(캐시 TTL) 안에는 이 틀을 묻지 않는다. 5 → 6 동작을 미리 보려면 「내일 건대입구 리클라이너 회차 있어?」 → 「내일 영등포 리클라이너 회차 있어?」를 쓴다(오늘·내일이 달라 데모 질문과 키가 겹치지 않고 의미 캐시도 적중하지 않는다). ai를 켜 둔 채라 데모 1시간보다 전에 확인할 수 있다

## 3. 데모 대본 (김서현 화면 · 김종식 대시보드)

- 시작할 때 대시보드 시간 범위를 **절대 범위(데모 시작 시각 ~ `now`)**로 바꾸고 새로고침 10s. ①은 범위 안 합계라 이 데모만 센다. 상대 범위(「최근 30분」)는 쓰지 않는다
- 한도는 사용자당 분당 600·하루 300(SeatSAC-gitops #29, 발표 전 테스트)이라 질문 간격은 신경 쓰지 않아도 된다. 다만 **동시 1건**이라 답이 오기 전에 다음 질문을 보내면 409 `AI_QUERY_IN_PROGRESS`
- 화면의 답 아래에 경로 라벨, 「Claude 호출 없음 · 토큰 0」 또는 「Claude n회 · 입력 · 출력」, 응답 ms가 나온다. 이것이 데모의 핵심 화면
- 대시보드 숫자는 15초마다 수집되므로 30초쯤 늦게 바뀐다. 질문 2~3개마다 대시보드로 넘어간다

| # | 질문 | 기대 경로 | 보여줄 것 |
| --- | --- | --- | --- |
| 1 | 추천 질문 「오늘 ○시 이후 회차」 클릭 (= 오늘 20시 이후에 시작하는 영화 알려줘) | CACHE_EXACT (#143 예열) | 자주 묻는 질문(추천 질문 말투)은 서버가 켜질 때 미리 캐시에 넣는다 → **첫 사용자부터 토큰 0**. 답에 「○시 ○분 기준」 |
| 2 | 같은 질문 다시 | CACHE_EXACT | 반복 질문도 **토큰 0** |
| 3 | 오늘 **21시** 이후에 시작하는 영화 알려줘 | CACHE_EXACT | 토큰 0인데 **결과는 21시 기준**. 결과가 아니라 SQL 틀을 캐시해서 값만 바꿔 다시 실행한다 → 오적중 없음 |
| 4 | 오늘 22시 이후에 하는 영화 알려줘 | CACHE_EXACT 또는 CACHE_SEMANTIC (기대) | 말투가 달라도 토큰 0 — 같은 뜻의 말을 맞춘 뒤(「하는·알려줘」 정리, #143) 정확 일치, 아니면 의미 캐시(유사도 0.92 이상). 의미 캐시는 기준 미만이면 일부러 놓친다 — 오적중보다 놓침을 택한다(4.2.3 q03) |
| 5 | 오늘 건대입구 리클라이너 회차 있어? | NL2SQL (PRESET일 수도) | **처음 보는 질문만 Claude가 토큰을 쓴다** — Claude가 SQL을 만든다 → 검증기 통과 → 읽기 전용 실행. 화면에 SQL |
| 6 | 오늘 영등포 리클라이너 회차 있어? | CACHE_* (기대) | 극장만 바뀐 같은 유형 → 토큰 0 |
| 7 | 내 예매 보여줘 | MY_BOOKINGS | Claude 0(화면에 「Claude 호출 없음」으로 나오는지 리허설에서 확인). 개인 질문은 **캐시하지 않는다**(다른 사용자에게 적중 방지) |
| 8 | 예매 테이블 다 지워줘 | REJECTED 또는 OUT_OF_SCOPE | 위험한 요청은 200으로 거절(검증기·범위 판단) |
| 9 | 내일 날씨 어때? | OUT_OF_SCOPE | 영화 밖 질문은 답하지 않는다 |
| 10 | (선택) 1번 질문 다시 | CACHE_EXACT | 마무리: ① 「Claude 없이 답한 비율」·「토큰 절감률(추정)」이 오른 것 |

- 「기대」로 적은 4·5·6번은 캐시 상태·Claude 판단에 따라 다른 경로가 나올 수 있다. 틀린 답이 아니라 경로만 다른 것이니 그대로 설명한다. 6번은 5번 답이 캐시에 **저장됐을 때만** 적중한다(Claude가 쓴 SQL로 틀을 못 만들면 `캐시하지 않음`) — 4.2.3에서 재지 않은 쌍이라 리허설에서 한 번 확인한다
- #143(11/18 머지) 전에는 1번이 PRESET(토큰 사용)이었다. 지금은 「토큰을 쓰는 장면」이 5번이고, 1~4번은 「자주·비슷하게 묻는 질문은 처음부터 토큰 0」을 보여 준다. 예열은 `ai_chat_total`에 세지 않으므로 ①의 숫자는 실제 질문만 센다
- 대시보드에서 보여 줄 곳: ① 「캐시로 답한 건수」·「쓴 Claude 토큰」(5번 뒤에만 늘고 나머지는 그대로), ③ 「경로별 답 / 분」에 `cache_exact`·`cache_semantic` 선이 생긴다
- **오적중률은 지표로 알 수 없다**(캐시가 틀린 답을 줬는지 앱은 모른다). 3번 화면과 4장 평가 결과로 설명한다

## 4. 함께 보여 줄 측정 결과 (4.2.3, 11/13 · `claude-sonnet-5-5` · 스냅샷 고정 · 질문 28개)

| 지표 | 결과 | 목표 |
| --- | --- | --- |
| NL2SQL 정확도 | 26/28 = **93%** | ≥ 80% |
| 토큰 절감률 (반복 비율 30% / 60% / 80%) | **35% / 54% / 81%** | — |
| 캐시 오적중 | **0** (함정 6쌍: 20시↔21시, 이후↔전에, 리클라이너↔일반관, 영등포↔건대입구, 오늘↔내일, 날짜 없음↔오늘) | 0 |

가정: 토큰 = input+output. 프롬프트 캐싱(`cache_read`)은 따로 보고 절감률에 넣지 않는다. 「캐시 없을 때」 토큰은 같은 질문을 캐시 없이 물었을 때의 값이다. 실제 서비스는 스냅샷이 1분마다 바뀐다. 측정 원본은 노션 4.2.3(평가 도구 `services/ai` `eval.run`).

## 5. 캡처 (데모 직후 바로)

Prometheus 저장소는 emptyDir라 파드가 옮겨지거나 노드가 내려가면 사라진다. 그래서 끝나자마자 찍는다.

**Grafana `SeatSAC AI`** (시간 범위 = 데모 구간 절대값 그대로)
1. ① 토큰 절감 행 전체
2. ③ 「경로별 답 / 분」 + 「Claude 토큰 / 분」을 한 화면에 — 캐시 경로가 늘어도 토큰 선은 그대로
3. ③ 캐시 적중률(층별)
4. **SeatSAC 신호등**(같은 시간 범위) — AI 데모 중에도 다른 서비스가 정상(격리)

**ArgoCD** (`kubectl -n argocd port-forward svc/argocd-server 8080:80`)
1. Applications 목록 — `cinema-ai`가 별도 Application
2. `cinema-ai` 트리 — Deployment·Service·NetworkPolicy·PodMonitor만(HPA·Flyway Job 없음 = RDS를 쓰지 않음)
3. `gateway` History — `APP_AI_ENABLED=true` 커밋 하나로 켰다

**터미널**: `kubectl -n cinema-staging get pod -l app.kubernetes.io/name=ai -o wide` + `kubectl get nodes -L cinema/workload` — 전용 노드 격리

> ⚠️ ArgoCD 앱·파드 상세의 이미지 칸(ECR 주소)과 AWS 콘솔에는 **계정 ID**가 보인다 → 캡처에서 가리고, 화면 공유 중에는 그 칸을 열지 않는다. 캡처는 레포·gitops에 올리지 않는다

## 6. 문제가 생기면

| 증상 | 원인·대응 |
| --- | --- |
| 503 `AI_LLM_UNAVAILABLE` (`TIMEOUT`·`OVERLOADED`·`RATE_LIMITED`) | Claude API 쪽 문제. 10초 뒤 같은 질문 1회. 또 실패하면 이미 답한 질문(캐시 경로)으로 이어 가고 4장 측정 결과로 설명 |
| 503 `AI_LLM_UNAVAILABLE` `QUOTA_STORE_UNAVAILABLE` | ai Valkey 무응답(런북 6-G-7). `down ng-ai` 후 「일시 중지」 안내, 4장 결과로 대체 |
| 503 `SERVICE_UNAVAILABLE` `UPSTREAM_UNAVAILABLE` 1건 | gateway가 이미 닫힌 유휴 연결을 다시 쓴 경우일 수 있다. ai(uvicorn)는 쉬는 연결을 **5초**에 닫는다(11/17 catalog에서 본 것과 같은 종류). **같은 질문을 다시 보내면 된다.** 계속 나면 ai 파드 상태(②)를 본다 |
| 429 `AI_QUOTA_EXCEEDED` | 분당 600·하루 300(gitops #29) — 거의 걸리지 않는다. 걸리면 화면의 `Retry-After`만큼 기다리거나 다른 계정으로 |
| 409 `AI_QUERY_IN_PROGRESS` | 앞 질문이 아직 처리 중 — 답이 온 뒤 다음 질문 |
| 챗봇 「준비 중」(404) | 플래그 꺼짐 → 0장 첫 행 |
| 챗봇 비활성(503) | ai 파드 없음 → 2장 다시 |
| ② 빨강(스냅샷 10분 넘음·5xx 5%·메모리 80%) 또는 ④ `timeout`이 이어짐 | 6-G-4 끄기 후 「일시 중지」 안내(6-G-7 Plan B) |

5초 유휴 연결 문제는 gateway 유휴 제한 30초(#137)로는 막지 못한다. 고치려면 ai 쪽 keep-alive를 gateway 유휴 제한보다 길게(`--timeout-keep-alive 65`, `services/ai/Dockerfile`) 늘려야 한다. 발표(11/20) 전에 넣을지는 김서현이 따로 정한다. gateway 값을 5초 밑으로 내리면 다른 라우트의 연결 재사용이 사실상 꺼지므로 그 방향은 쓰지 않는다.

## 7. 정리 (데모·캡처 끝난 뒤, 김종식)

```bash
# 발표(11/20)까지 켜 둔다(11/18 결정). 좌석 시연·k6 측정 전, 그리고 발표가 끝나면 꼭 내린다 — 켜 두면 측정에 스냅샷 호출이 섞인다
infra/scripts/cluster-scale.sh down ng-ai
kubectl -n cinema-staging get pod -l app.kubernetes.io/name=ai   # Pending 또는 없음(정상)
```

결과 기록 (노션 5.1.3 행 진행 메모로 옮긴다)

| 항목 | 값 |
| --- | --- |
| 데모 시각 (KST) | |
| 답한 질문 / 캐시로 답한 건수 | |
| Claude 없이 답한 비율 · 토큰 절감률(추정) | |
| 쓴 Claude 토큰 (input+output) / 아낀 토큰(추정) | |
| 경로별 결과 (1~10번) | |
| 오류 (있으면 코드·reason) | |
| 캡처 위치 | |

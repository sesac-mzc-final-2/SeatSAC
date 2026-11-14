# 무중단 배포 데모 런북 (WBS 5.1.1)

> 2026-11-11 · 김종식. 1차 데모 11/16(무중단 배포 · 실시간 좌석, ADR-52·82), 데모 주간 11/16~20.
> 실시간 좌석 대본은 `docs/runbooks/demo-realtime-seat.md`(5.1.2), 부하 테스트는 `docs/runbooks/terraform-infra.md` 6-H, 블루/그린은 6-D, 대시보드는 6-F.
> **이 문서에는 지금 「데이터 초기화」(1장)만 있다.** 무중단 배포 장면 대본은 별도 PR로 추가한다.

---

## 1. 데이터 초기화 (리허설·데모 전후 — 5.1.1·5.1.2 공용)

데모와 리허설이 남기는 것은 세 가지다: **선점(PENDING)**, **확정 예매(CONFIRMED)**, **경쟁에 쓴 회차의 지저분한 좌석**. 모두 **공개 API로만** 정리한다. RDS·Valkey를 손으로 고치지 않는다.

### 1-1. 원칙 — 하지 않는 것

| 하지 않는 것 | 이유 |
| --- | --- |
| Valkey 키 직접 삭제(`seat:*`·`user-hold:*`·`hold-expiry`·`idem:*`·`seat-seq:*`) | RDS의 PENDING 예매와 어긋난다. 만료 스캔(RDS 10초)이 결국 정리하지만 그 사이 좌석판·선점 제한이 틀리게 보인다. `idem:*`를 지우면 재시도가 새 예매를 만든다 |
| RDS 직접 DML(`booking`·`booking_seat`·`payment`) | 앱 계정만 DML 권한이 있고(마스터는 앱·Job에서 쓰지 않음), 아웃박스·결제 상태와 짝이 깨진다. 콘솔·직접 접속 금지 규칙과도 어긋난다 |
| 회차 시드 재적재(`lotte-seed/load/run.sh`) | 회차 ID가 바뀌면 booking 분석 회차 `SMOKE_SCREENING_ID`(gitops)도 고쳐야 한다 — 안 고치면 **모든 booking 배포가 abort**(런타임 계약 5장). 재적재했다면 booking 머지 마감(**11/20 05:00**) 전에 gitops PR로 함께 고친다. 마감 뒤에는 재적재하지 않는다(회차 ID가 바뀌면 열린 화면·시연 회차도 바뀌고, 고칠 gitops PR을 머지할 수 없다) |
| booking·Valkey 파드 재시작으로 "초기화" | 데이터는 그대로이고, 블루/그린·SSE 연결만 흔든다 |

**기본 방법은 「새 회차 쓰기」다.** 좌석 배치가 있는 극장 4곳(`hasLayout`)의 회차가 하루에도 여러 개라, 쓴 회차는 그대로 두고 다음 리허설에는 다른 회차를 고른다(`pick_targets.py` — 오늘 회차가 모자라면 `--date`로 다음 날). 아래 절차는 그래도 남은 것을 치우는 용도다.

### 1-2. 언제

| 시점 | 할 것 |
| --- | --- |
| 리허설·부하 테스트(6-H)가 끝날 때마다 | 1-3 전부 |
| 데모 당일 T-30분 | 1-3의 3·4(확인만). 정리할 게 나오면 1·2 |
| 데모 뒤 | 1-3 전부 + k6 리소스 정리(6-H-4) |

블루/그린 전환 중(`kubectl -n cinema-staging get rollout`이 Progressing·Paused)에는 하지 않는다 — 요청은 active로만 가서 섞이지는 않지만, 전환 순간 일부가 503으로 실패할 수 있고 무중단 데모의 5xx 그래프에 섞인다. 끝난 뒤 실행한다(실패한 것은 다시 실행하면 남은 것만 취소된다).

### 1-3. 절차 (레포 루트, 노드 켜짐 시간)

```bash
export API="https://$(cd infra/terraform/envs/staging && terraform output -raw cloudfront_domain_name)/api"
T=tests/k6/tools/loadtest_users.py

# 0) 토큰 — 경쟁용 100개 + 데모 계정(A·B). 토큰 파일은 레포 밖, 권한 600
python3 $T refresh && python3 $T tokens --min-valid-minutes 20 --out ~/.seatsac/k6-tokens.txt
#    데모 계정 A·B 토큰은 ~/.seatsac/demo-tokens.txt(줄당 1개, chmod 600)에 둔다 — 화면 로그인과 같은 계정
```

1. **선점(PENDING)** — 사용자 API로는 목록을 볼 수 없다(`/users/me/bookings`는 PENDING 제외). 아는 bookingId는 해제하고, 나머지는 **300초 기다린다**(선점 TTL, 만료 스캔이 풀어 준다)
   ```bash
   curl -s -X DELETE "$API/bookings/{bookingId}/hold" -H "Authorization: Bearer <그 선점을 잡은 계정의 토큰>"   # 데모 대본에 적어 둔 bookingId
   #   선점 주인의 토큰이어야 한다(남이 잡은 선점은 403) — 5.1.2 장면 3은 A, 장면 4 만료용은 B
   ```
2. **확정 예매(CONFIRMED)** — 시작 전 회차의 확정 예매를 취소(환불까지, BKG-07)한다. 먼저 미리보기로 무엇이 취소될지 보고, 맞으면 `--apply`
   ```bash
   R=tests/k6/tools/reset_demo_data.py
   python3 $R --base-url "$API" --tokens-file ~/.seatsac/k6-tokens.txt --tokens-file ~/.seatsac/demo-tokens.txt          # 미리보기
   python3 $R --base-url "$API" --tokens-file ~/.seatsac/k6-tokens.txt --tokens-file ~/.seatsac/demo-tokens.txt --apply  # 취소
   ```
   - 이미 시작한 회차의 예매는 취소할 수 없어(400 `SCREENING_STARTED`) 건너뛴다 — 지난 회차는 데모에 쓰지 않으므로 그대로 둔다
   - 테스트 계정 토큰만 넣는다. **실제 사용자 토큰을 넣지 않는다**(그 사람 예매가 취소된다)
3. **확인 — 데모에 쓸 회차가 깨끗한지**
   ```bash
   python3 $R --base-url "$API" --screening-id $DEMO_SCREENING --screening-id $CONTENTION_SCREENING
   #   회차 …: AVAILABLE N · 깨끗함   ← 이 줄이면 된다
   #   「남은 좌석 HELD …」 → 1단계 300초를 더 기다린다. 「RESERVED …」 → 2단계를 다시 보거나(실제 사용자 예매일 수 있다) 다른 회차를 고른다
   ```
   깨끗하지 않으면 **고치지 말고 다른 회차를 고른다**: `eval "$(python3 tests/k6/tools/pick_targets.py --base-url "$API")"`(런북 6-H-1 3)
4. **확인 — 인프라 쪽**
   ```bash
   tests/k6/run-in-cluster.sh cleanup                                                     # 남은 k6 Job·ConfigMap·Secret
   kubectl -n cinema-staging get job,configmap,secret -l app.kubernetes.io/name=k6-loadtest   # No resources
   NODE=$(aws elasticache describe-replication-groups --replication-group-id cinema-staging-valkey \
     --query 'ReplicationGroups[0].MemberClusters[0]' --output text)                      # 경보와 같은 노드(alerts.tf member_clusters[0])
   read -r FROM TO < <(python3 -c 'from datetime import datetime,timedelta,timezone as z;n=datetime.now(z.utc);print((n-timedelta(hours=1)).strftime("%FT%TZ"),n.strftime("%FT%TZ"))')
   aws cloudwatch get-metric-statistics --namespace AWS/ElastiCache --metric-name DatabaseMemoryUsagePercentage \
     --dimensions Name=CacheClusterId,Value="$NODE" --statistics Maximum \
     --start-time "$FROM" --end-time "$TO" --period 300 --query 'Datapoints[].Maximum'
   #   70% 미만이면 된다. idem:* 키(24시간)가 부하 테스트마다 쌓인다 — 80%면 경보(ADR-59). 지우지 말고 24시간 TTL을 기다리거나 노드 종류를 검토
   ```
   - 아웃박스: 대시보드 `booking_outbox_pending`이 0(릴레이 켜짐) 또는 늘어난 그대로(릴레이 꺼짐 — 정상, 런타임 계약 3장 booking 행)
   - 대시보드: 시간 범위를 **데모 직전부터**로 다시 잡는다(⑤ 건수 카드는 범위 안 합계 — 6-F-3). 지표는 지우지 않는다
5. `rm -f ~/.seatsac/k6-tokens.txt`(데모 계정 토큰 파일은 데모 주간 동안 둔다. 11/20 뒤 지운다 — 7-6-3)

### 1-4. 막힐 때

| 증상 | 원인 · 조치 |
| --- | --- |
| `토큰 #N: 예매 목록 실패 401` | 토큰 만료(Access 60분) → 0)의 `refresh`·`tokens --out` 다시 |
| `토큰 #N: 예매 목록 실패 503 BOOKING_UNAVAILABLE` | user → booking 내부 호출 실패. booking 파드·서킷 확인 후 다시 |
| `취소 실패 503 SERVICE_UNAVAILABLE` | booking → **catalog** 회차 조회 실패(#77 — 회차 404·시작 시각 없음 포함). 다시 실행하면 남은 확정 예매만 취소한다 |
| `⚠️ 취소됐지만 환불 미확인` | 취소는 커밋됐는데 환불(payment) 호출이 실패했다 — booking은 이 실패를 삼키고 200을 돌려준다. 도구가 한 번 더 불렀는데도 그렇다는 뜻. 그 예매는 이미 CANCELED라 다음 실행 목록에 안 나오므로 **같은 bookingId에 cancel을 직접 다시** 부른다(이미 취소된 예매의 cancel은 결제가 APPROVED면 환불을 다시 시도하는 멱등 경로): `curl -s -X POST "$API/bookings/{bookingId}/cancel" -H "Authorization: Bearer <그 계정 토큰>"` → 응답 `payment.status`가 `CANCELED`. 테스트 계정·모의 결제라 데모 데이터에는 영향이 없다 |
| `· 이미 처리됨(BOOKING_STATE_CONFLICT)` | 목록을 읽은 뒤 그 예매가 CONFIRMED가 아니게 됐다(만료·결제 실패 등). 할 일 없음 |
| `HELD`가 300초 뒤에도 남음 | 만료 스캔이 안 돈다 — booking 로그·`booking_hold_expiry_lag_seconds` 확인(6-F). 데모 회차는 다른 것으로 바꾼다 |
| 데모 회차가 시작 60분 안으로 들어옴 | 마감(시작 5분 전)에 걸린다 → `pick_targets.py`로 다시 고른다 |

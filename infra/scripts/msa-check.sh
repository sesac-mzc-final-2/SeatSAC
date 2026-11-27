#!/usr/bin/env bash
# ------------------------------------------------------------------
# MSA 고도화 상태 점검 — 읽기만 한다 (6.5.1b 통합 테스트 · 6.5.2b 시연 · 6.1.10 AZ 장애 전후, 런북 terraform-infra 6-N·6-O)
#   사용: infra/scripts/msa-check.sh [all|apps|az|queues|data|stopped]
#     apps    : ArgoCD Application Sync·Health
#     az      : 앱 파드가 어느 AZ 노드에 있는지(서비스별 AZ 수). 한 AZ에만 있으면 ⚠
#     queues  : 메시징 큐 5개 대기·처리 중 수, DLQ 5개 수 — DLQ가 1건 이상이면 종료 코드 1
#     data    : RDS 6개 Multi-AZ·현재 AZ, Valkey 2개 primary·복제본 AZ
#     stopped : ops-api가 정지해 둔 서비스(ConfigMap ops-api-state) — 시연 뒤 비어 있어야 한다
#   권한: kubectl(cinema-developer 조회로 충분 — Secret은 읽지 않는다) · AWS는 sqs:GetQueueAttributes·GetQueueUrl,
#         rds:DescribeDBInstances, elasticache:DescribeReplicationGroups (cinema-admin 또는 cinema-dev)
#   바꾸는 명령은 없다(get·describe만). 큐를 비우거나(purge) 메시지를 받지 않는다 — 받으면 가시성 제한 동안 소비자가 못 본다
#   출력에 계정 ID·엔드포인트를 찍지 않는다(큐는 이름만) — 결과를 PR·노션에 붙여도 된다
# ------------------------------------------------------------------
set -euo pipefail

NS="${NS:-cinema-staging}"
PREFIX="${PREFIX:-cinema-staging}"
export AWS_REGION="${AWS_REGION:-ap-northeast-2}"
SERVICES="${SERVICES:-gateway catalog booking user payment coupon ops-api ai}"
QUEUES="${QUEUES:-coupon-commands payment-commands booking-replies user-booking-events ai-catalog-events}"
DBS="${DBS:-booking payment user catalog coupon ai}"
VALKEYS="${VALKEYS:-valkey ai-valkey}"

ACTION="${1:-all}"
FAIL=0

need() { command -v "$1" >/dev/null 2>&1 || { echo "필요: $1" >&2; exit 2; }; }

apps() {
  echo "== ArgoCD Application =="
  kubectl -n argocd get applications \
    -o custom-columns='NAME:.metadata.name,SYNC:.status.sync.status,HEALTH:.status.health.status' --no-headers \
    | awk '{ mark = ($2 == "Synced" && $3 == "Healthy") ? "✅" : "⚠"; printf "%s %-24s %-10s %s\n", mark, $1, $2, $3 }'
}

az() {
  echo "== 파드 AZ 분산 (Running만) =="
  local zones
  zones=$(kubectl get nodes -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.metadata.labels.topology\.kubernetes\.io/zone}{" "}{.spec.unschedulable}{"\n"}{end}')
  echo "$zones" | awk 'NF { printf "   노드 %-46s %s%s\n", $1, $2, ($3 == "true" ? "  (cordon)" : "") }'
  local svc pods
  for svc in $SERVICES; do
    pods=$(kubectl -n "$NS" get pods -l "app.kubernetes.io/name=$svc" --field-selector=status.phase=Running \
      -o jsonpath='{range .items[*]}{.spec.nodeName}{"\n"}{end}' 2>/dev/null || true)
    if [ -z "$pods" ]; then
      printf "   %-9s 파드 0\n" "$svc"
      continue
    fi
    # 노드 → AZ 목록은 환경변수로 넘긴다(macOS awk는 -v 값의 줄바꿈을 받지 못한다)
    echo "$pods" | ZL="$zones" awk -v svc="$svc" '
      BEGIN { n = split(ENVIRON["ZL"], rows, "\n"); for (i = 1; i <= n; i++) { split(rows[i], f, " "); z[f[1]] = f[2] } }
      NF { total++; c[z[$1]]++ }
      END {
        line = ""; k = 0
        for (zone in c) { line = line " " zone "=" c[zone]; k++ }
        mark = (total >= 2 && k < 2) ? "⚠ 한 AZ에만" : ""
        printf "   %-9s 파드 %d :%s %s\n", svc, total, line, mark
      }'
  done
}

queue_counts() {   # $1 = 큐 이름(접두사 없이)
  local url
  url=$(aws sqs get-queue-url --queue-name "$PREFIX-$1" --query QueueUrl --output text 2>/dev/null) || { echo "없음"; return; }
  aws sqs get-queue-attributes --queue-url "$url" \
    --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
    --query '[Attributes.ApproximateNumberOfMessages, Attributes.ApproximateNumberOfMessagesNotVisible]' --output text
}

queues() {
  echo "== 메시징 큐 (대기 · 처리 중) / DLQ =="
  local q main dlq dlq_n
  for q in $QUEUES; do
    main=$(queue_counts "$q")
    dlq=$(queue_counts "$q-dlq")
    dlq_n=$(echo "$dlq" | awk '{print $1 + $2}')
    if [ "$dlq" = "없음" ]; then
      printf "?  %-22s %-10s DLQ 없음\n" "$q" "$main"
    elif [ "$dlq_n" -gt 0 ]; then
      printf "❌ %-22s %-10s DLQ %s\n" "$q" "$main" "$dlq_n"
      FAIL=1
    else
      printf "✅ %-22s %-10s DLQ 0\n" "$q" "$main"
    fi
  done
  echo "   (ai-catalog-events는 ng-ai가 0대인 동안 쌓이는 것이 정상)"
}

data() {
  echo "== RDS (Multi-AZ · 현재 AZ · 대기 AZ · 상태) =="
  local db
  for db in $DBS; do
    aws rds describe-db-instances --db-instance-identifier "$PREFIX-$db-db" \
      --query 'DBInstances[0].[DBInstanceIdentifier, MultiAZ, AvailabilityZone, SecondaryAvailabilityZone, DBInstanceStatus]' \
      --output text 2>/dev/null | awk '{ printf "   %-28s multiAZ=%-5s 현재=%-16s 대기=%-16s %s\n", $1, $2, $3, $4, $5 }' \
      || printf "   %-28s 없음\n" "$PREFIX-$db-db"
  done
  echo "== Valkey (역할 · AZ) =="
  local rg
  for rg in $VALKEYS; do
    aws elasticache describe-replication-groups --replication-group-id "$PREFIX-$rg" \
      --query 'ReplicationGroups[0].NodeGroups[0].NodeGroupMembers[].[CacheClusterId, CurrentRole, PreferredAvailabilityZone]' \
      --output text 2>/dev/null | awk '{ printf "   %-34s %-8s %s\n", $1, $2, $3 }' \
      || printf "   %-34s 없음\n" "$PREFIX-$rg"
  done
}

stopped() {
  echo "== ops-api 정지 상태 (ConfigMap ops-api-state) =="
  local data
  data=$(kubectl -n "$NS" get configmap ops-api-state -o jsonpath='{.data}' 2>/dev/null || true)
  if [ -z "$data" ] || [ "$data" = "{}" ] || [ "$data" = "map[]" ]; then
    echo "✅ 정지해 둔 서비스 없음"
  else
    echo "⚠ 정지 기록이 남아 있다 — 시연 뒤면 관리자 화면 「가동」으로 되돌린다: $data"
  fi
}

case "$ACTION" in
  apps) need kubectl; apps ;;
  az) need kubectl; az ;;
  queues) need aws; queues ;;
  data) need aws; data ;;
  stopped) need kubectl; stopped ;;
  all) need kubectl; need aws; apps; az; queues; data; stopped ;;
  *) echo "사용: $0 [all|apps|az|queues|data|stopped]" >&2; exit 2 ;;
esac
exit "$FAIL"

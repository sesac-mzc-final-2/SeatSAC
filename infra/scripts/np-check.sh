#!/usr/bin/env bash
# ------------------------------------------------------------------
# 3.1.13 NetworkPolicy 확인 (OPS-15) — 런타임 계약 6장 허용 목록이 실제로 지켜지는지 TCP 연결로 본다
#   사용: infra/scripts/np-check.sh            (관리자 cinema-admin, 노드 켜짐)
#   방법: 각 서비스 파드 안에서 bash /dev/tcp 로 다른 서비스 8080에 붙어 본다(curl 불필요)
#         허용 = 연결 성공(OPEN), 차단 = NetworkPolicy가 패킷을 버려 타임아웃(BLOCKED)
#   kubectl exec만 쓴다(읽기 — 클러스터를 바꾸지 않는다). 파드가 없는 서비스(gateway는 Cognito 전)는 건너뛴다
#   결과가 기대와 다르면 종료 코드 1
# ------------------------------------------------------------------
set -uo pipefail

NS="${NS:-cinema-staging}"
TIMEOUT="${TIMEOUT:-3}"
SERVICES="gateway catalog booking user payment"

# 런타임 계약 6장: 호출하는 쪽 → 허용된 대상 (monitoring·ALB·분석 Job은 앱 파드가 아니라 여기서 보지 않는다)
allowed() {
  case "$1" in
    gateway) echo "catalog booking user" ;;
    catalog) echo "booking" ;;
    booking) echo "catalog payment" ;;
    user)    echo "booking catalog" ;;
    payment) echo "" ;;
  esac
}

pod_of() {
  kubectl -n "$NS" get pods -l "app.kubernetes.io/name=$1" --field-selector=status.phase=Running \
    -o jsonpath='{.items[0].metadata.name}' 2>/dev/null
}

probe() {   # $1 파드 $2 대상 서비스 $3 컨테이너(= 서비스 이름) → OPEN | BLOCKED | ERROR
  local out
  out=$(kubectl -n "$NS" exec "$1" -c "$3" -- bash -c \
    "timeout $TIMEOUT bash -c '</dev/tcp/$2/8080' 2>/dev/null && echo OPEN || echo BLOCKED" 2>&1) || { echo ERROR; return; }
  case "$out" in *OPEN*) echo OPEN ;; *BLOCKED*) echo BLOCKED ;; *) echo ERROR ;; esac
}

fail=0
printf '%-8s → %-8s  %-8s %-8s %s\n' "from" "to" "기대" "결과" ""
for from in $SERVICES; do
  pod=$(pod_of "$from")
  if [ -z "$pod" ]; then echo "$from: Running 파드 없음 — 건너뜀"; continue; fi
  ok=" $(allowed "$from") "
  for to in $SERVICES; do
    [ "$to" = "$from" ] && continue
    if [ -z "$(pod_of "$to")" ]; then continue; fi    # 대상이 없으면 판단 불가
    case "$ok" in *" $to "*) want=OPEN ;; *) want=BLOCKED ;; esac
    got=$(probe "$pod" "$to" "$from")
    mark="✅"; [ "$got" != "$want" ] && { mark="❌"; fail=1; }
    printf '%-8s → %-8s  %-8s %-8s %s\n' "$from" "$to" "$want" "$got" "$mark"
  done
done

if [ "$fail" -ne 0 ]; then
  echo "기대와 다른 연결이 있다 — base/{svc}/networkpolicy.yaml·런타임 계약 6장 확인 (ERROR는 exec 실패: 파드 상태·권한)"
  exit 1
fi
echo "모두 기대대로 (런타임 계약 6장)"

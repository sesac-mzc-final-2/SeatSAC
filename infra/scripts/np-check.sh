#!/usr/bin/env bash
# ------------------------------------------------------------------
# 3.1.13 NetworkPolicy 확인 (OPS-15) — 런타임 계약 6장 허용 목록이 실제로 지켜지는지 TCP 연결로 본다
#   사용: infra/scripts/np-check.sh            (관리자 cinema-admin, 노드 켜짐)
#         REQUIRE_GATEWAY=1 infra/scripts/np-check.sh   (Cognito 뒤 — gateway 파드가 없으면 실패)
#   방법: 각 서비스 파드 안에서 bash /dev/tcp 로 다른 서비스 8080에 붙어 본다(curl 불필요)
#     OPEN    = 연결 성공
#     BLOCKED = 시간 초과(timeout 종료 코드 124) — VPC CNI 정책은 패킷을 버리므로 차단은 시간 초과로 보인다
#     ERROR   = 차단이 아닌 실패: DNS 조회 실패·연결 거부(대상이 안 듣고 있음)·대상 Service에 Ready 엔드포인트 없음·exec 실패
#               → 정책을 판단할 수 없으므로 기대와 상관없이 실패로 센다
#   kubectl get·exec만 쓴다(읽기 — 클러스터를 바꾸지 않는다)
#   종료 코드: 0 모두 기대대로 · 1 기대와 다름/판단 불가 · 2 클러스터 조회 실패(권한·연결)
# ------------------------------------------------------------------
set -uo pipefail

NS="${NS:-cinema-staging}"
TIMEOUT="${TIMEOUT:-3}"
SERVICES="gateway catalog booking user payment"
REQUIRED="catalog booking user payment"                 # 항상 있어야 하는 서비스
[ "${REQUIRE_GATEWAY:-0}" = "1" ] && REQUIRED="gateway $REQUIRED"   # Cognito 전에는 gateway가 없어도 된다

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

die() { echo "❌ $*" >&2; exit 2; }
ERR=$(mktemp); trap 'rm -f "$ERR"' EXIT

# 클러스터 조회 자체가 되는지 먼저 (권한·연결 오류를 '파드 없음'으로 착각하지 않게)
kubectl -n "$NS" get pods -o name >/dev/null 2>"$ERR" || die "kubectl get pods 실패: $(head -1 "$ERR") — cinema-admin 자격 증명·노드 켜짐 확인"

pod_of() {   # Running 파드 이름(없으면 빈 값). 조회 실패는 종료 코드 2
  local out
  # .items[0]은 목록이 비면 jsonpath 오류(array index out of bounds)라 [*]로 받고 첫 이름만 쓴다
  out=$(kubectl -n "$NS" get pods -l "app.kubernetes.io/name=$1" --field-selector=status.phase=Running \
    -o jsonpath='{.items[*].metadata.name}' 2>"$ERR") || die "$1 파드 조회 실패: $(head -1 "$ERR")"
  echo "${out%% *}"
}

ready_endpoints() {   # 대상 Service의 Ready 엔드포인트 수 (0이면 연결 실패가 정책 때문인지 알 수 없다)
  local ips
  ips=$(kubectl -n "$NS" get endpoints "$1" -o jsonpath='{.subsets[*].addresses[*].ip}' 2>"$ERR") \
    || die "$1 엔드포인트 조회 실패: $(head -1 "$ERR")"
  wc -w <<<"$ips" | tr -d ' '
}

probe() {   # $1 파드 $2 대상 서비스 $3 컨테이너(= 서비스 이름) → OPEN | BLOCKED | ERROR(이유)
  local out
  # shellcheck disable=SC2016  # 아래 $rc는 파드 안 bash가 펼친다
  out=$(kubectl -n "$NS" exec "$1" -c "$3" -- bash -c "
    getent hosts $2 >/dev/null 2>&1 || { echo ERROR:DNS; exit 0; }
    timeout $TIMEOUT bash -c '</dev/tcp/$2/8080' 2>/dev/null; rc=\$?
    case \$rc in 0) echo OPEN ;; 124) echo BLOCKED ;; *) echo ERROR:REFUSED ;; esac" 2>&1) \
    || { echo "ERROR:EXEC"; return; }
  case "$out" in
    *OPEN*) echo OPEN ;;
    *BLOCKED*) echo BLOCKED ;;
    *ERROR:*) echo "ERROR:${out##*ERROR:}" ;;
    *) echo "ERROR:EXEC" ;;
  esac
}

# 서비스별 Running 파드·Ready 엔드포인트 수를 한 번만 조회한다.
# die는 $( ) 안에서는 그 서브셸만 끝내므로 조회마다 종료 상태를 받아 여기서 종료 코드 2로 멈춘다
for svc in $SERVICES; do
  p=$(pod_of "$svc") || exit 2
  printf -v "POD_$svc" '%s' "$p"
  e=0
  if [ -n "$p" ]; then e=$(ready_endpoints "$svc") || exit 2; fi
  printf -v "READY_$svc" '%s' "$e"
done
pod_var() { local v="POD_$1"; echo "${!v}"; }
ready_var() { local v="READY_$1"; echo "${!v}"; }

fail=0; checked=0; missing=""
printf '%-8s → %-8s  %-8s %-14s %s\n' "from" "to" "기대" "결과" ""
for from in $SERVICES; do
  pod=$(pod_var "$from")
  if [ -z "$pod" ]; then
    case " $REQUIRED " in
      *" $from "*) missing="$missing $from" ;;
      *) echo "$from: Running 파드 없음 — 건너뜀 (Cognito 전 허용. 뒤에는 REQUIRE_GATEWAY=1)" ;;
    esac
    continue
  fi
  ok=" $(allowed "$from") "
  for to in $SERVICES; do
    [ "$to" = "$from" ] && continue
    [ -z "$(pod_var "$to")" ] && continue              # 대상 파드가 없으면(gateway) 위에서 이미 판단
    case "$ok" in *" $to "*) want=OPEN ;; *) want=BLOCKED ;; esac
    if [ "$(ready_var "$to")" -eq 0 ]; then
      got="ERROR:NOT_READY"
    else
      got=$(probe "$pod" "$to" "$from")
    fi
    checked=$((checked + 1))
    mark="✅"; [ "$got" != "$want" ] && { mark="❌"; fail=1; }
    printf '%-8s → %-8s  %-8s %-14s %s\n' "$from" "$to" "$want" "$got" "$mark"
  done
done

if [ -n "$missing" ]; then
  echo "❌ 미완료: 필수 서비스 파드가 Running이 아님 —$missing"; fail=1
fi
if [ "$checked" -eq 0 ]; then
  echo "❌ 검사한 연결이 0개 — 판단 불가"; fail=1
fi
if [ "$fail" -ne 0 ]; then
  echo "기대와 다르거나 판단할 수 없는 연결이 있다 ($checked개 검사). ERROR:DNS·REFUSED·NOT_READY는 정책이 아니라 대상 상태 문제 — 런북 6-E"
  exit 1
fi
echo "모두 기대대로 ($checked개 검사, 런타임 계약 6장)"

#!/usr/bin/env bash
# ------------------------------------------------------------------
# 4.2.1 (OPS-10) k6 시나리오를 클러스터 안 일회성 Job으로 돌린다 — gateway Service를 직접 부른다(http://gateway:8080, /api 없음)
#   사용: tests/k6/run-in-cluster.sh <시나리오> [-e KEY=VALUE ...]     (레포 루트, 관리자 cinema-admin, 노드 켜짐)
#         tests/k6/run-in-cluster.sh cleanup                           (남은 k6 리소스 지우기)
#     contention  같은 좌석 100명 동시 선점 (seat-hold-contention.js)  토큰 VUS(기본 100)개
#     user-hold   사용자당 진행 중 선점 1건 (user-hold-limit.js)        토큰 1개
#     browse      조회 캐시 (browse.js)                                토큰 없음
#     deploy      배포 중 부하 (deploy-during-load.js)                 토큰 VUS(기본 10)개
#   -e 값은 k6에 그대로 넘긴다(시나리오 환경변수는 tests/k6/README.md 「데이터」). 대상은 tools/pick_targets.py로 고른다
#   토큰: K6_TOKENS_FILE(줄당 1개, tools/loadtest_users.py tokens --out) → 실행마다 Secret으로 만들어 파일로 마운트하고 끝나면 지운다
#
#   GitOps 예외(런북 6-H): 이 스크립트가 만드는 것은 실행 중에만 있는 Job·ConfigMap(스크립트)·Secret(토큰) 셋뿐이다.
#   ArgoCD가 관리하는 리소스는 건드리지 않고, 라벨 app.kubernetes.io/name=k6-loadtest로만 만들고 지운다.
#   스크립트가 중간에 죽어도 ConfigMap·Secret은 Job의 ownerReference로 묶여 Job TTL(15분) 뒤 같이 지워진다
#
#   환경변수: NS(기본 cinema-staging) · K6_IMAGE(기본 grafana/k6:1.7.1 — analysis/와 같은 버전)
#             K6_RESULTS_DIR(기본 ~/.seatsac/k6-results — 로그 저장, 레포 밖) · KEEP=1(끝나도 Job·ConfigMap 남김, Secret은 항상 지움)
#             ALLOW_AI=1(ai 파드가 떠 있어도 진행 — 기본은 멈춘다, ADR-87) · DRY_RUN=1(클러스터를 건드리지 않고 Job 매니페스트만 출력)
#   종료 코드: 0 임계값 통과 · 99 임계값 실패(k6) · 1 실행 실패(Job·파드) · 2 사용법·사전 점검 실패
# ------------------------------------------------------------------
set -euo pipefail
command -v jq >/dev/null || { echo "❌ jq가 필요하다 (brew install jq)" >&2; exit 2; }

NS="${NS:-cinema-staging}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:1.7.1}"
RESULTS_DIR="${K6_RESULTS_DIR:-$HOME/.seatsac/k6-results}"
LABEL_NAME="k6-loadtest"     # gitops base/gateway/networkpolicy.yaml이 이 라벨로 gateway 접근을 허용한다
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

die() { echo "❌ $*" >&2; exit 2; }
usage() { sed -n '3,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2; exit 2; }

[ $# -ge 1 ] || usage
SCENARIO="$1"; shift

if [ "$SCENARIO" = "cleanup" ]; then
  kubectl -n "$NS" delete job,configmap,secret -l "app.kubernetes.io/name=$LABEL_NAME" --ignore-not-found
  exit 0
fi

# 시나리오 → 스크립트 · 토큰 수 · 실행 한도(초)
case "$SCENARIO" in
  contention) FILE=seat-hold-contention.js; TOKENS_NEEDED=100; DEADLINE=300 ;;
  user-hold)  FILE=user-hold-limit.js;      TOKENS_NEEDED=1;   DEADLINE=180 ;;
  browse)     FILE=browse.js;               TOKENS_NEEDED=0;   DEADLINE=420 ;;
  deploy)     FILE=deploy-during-load.js;   TOKENS_NEEDED=10;  DEADLINE=1200 ;;
  *) echo "모르는 시나리오: $SCENARIO" >&2; usage ;;
esac

# -e KEY=VALUE만 받는다(다른 k6 옵션을 섞으면 Job 인자가 예측하기 어려워진다). VUS는 토큰 수 점검에 쓴다
K6_ENV=()
while [ $# -gt 0 ]; do
  [ "$1" = "-e" ] && [ $# -ge 2 ] || die "인자는 -e KEY=VALUE 형식만: $*"
  [[ "$2" =~ ^[A-Z][A-Z0-9_]*=[^[:space:]]*$ ]] || die "-e 값 형식이 아니다(KEY=VALUE, 공백 없음): $2"
  case "$2" in
    K6_TOKENS_FILE=*|K6_TOKENS=*) die "토큰은 -e로 넘기지 않는다 — K6_TOKENS_FILE 환경변수(파일)만 쓴다" ;;
    VUS=*) [[ "${2#VUS=}" =~ ^[1-9][0-9]*$ ]] || die "VUS는 양의 정수: $2"
           [ "$SCENARIO" != "user-hold" ] && TOKENS_NEEDED="${2#VUS=}" ;;
  esac
  K6_ENV+=("$2"); shift 2
done

TOKENS_FILE=""
if [ "$TOKENS_NEEDED" -gt 0 ]; then
  TOKENS_FILE="${K6_TOKENS_FILE:-}"
  [ -n "$TOKENS_FILE" ] && [ -f "$TOKENS_FILE" ] || die "$SCENARIO 는 토큰이 필요하다 — K6_TOKENS_FILE(tools/loadtest_users.py tokens --out)"
  perm=$(stat -f '%Lp' "$TOKENS_FILE" 2>/dev/null || stat -c '%a' "$TOKENS_FILE")   # macOS(BSD) · 리눅스(GNU)
  [[ "$perm" =~ 00$ ]] || die "$TOKENS_FILE 권한이 넓다($perm) — chmod 600"
  have=$(grep -c '[^[:space:]]' "$TOKENS_FILE" || true)
  [ "$have" -ge "$TOKENS_NEEDED" ] || die "토큰 ${have}개 < 필요 ${TOKENS_NEEDED}개 (서로 다른 사용자가 VU 수만큼 필요 — README)"
fi

RUN="k6-${SCENARIO}-$(date +%m%d-%H%M%S)"     # Job·ConfigMap·Secret 이름 = 실행 ID
LABELS=$(jq -cn --arg n "$LABEL_NAME" --arg r "$RUN" --arg s "$SCENARIO" \
  '{"app.kubernetes.io/name":$n,"app.kubernetes.io/part-of":"cinema","app.kubernetes.io/instance":$r,"app.kubernetes.io/component":$s,"app.kubernetes.io/managed-by":"run-in-cluster"}')

# ConfigMap 키에는 '/'를 못 쓴다 → lib/common.js는 키 common.js로 넣고 마운트할 때 lib/ 아래로 둔다
SCRIPT_FILES=(seat-hold-contention.js user-hold-limit.js browse.js deploy-during-load.js)
ITEMS=$(printf '%s\n' "${SCRIPT_FILES[@]}" | jq -R '{key:., path:.}' | jq -cs '. + [{key:"common.js", path:"lib/common.js"}]')

ARGS=$(jq -cn --arg f "/scripts/$FILE" '["run","--no-usage-report",$f]')
# macOS 기본 bash 3.2는 set -u에서 빈 배열 "${a[@]}"를 오류로 본다 → ${a[@]+...}로 감싼다
for kv in ${K6_ENV[@]+"${K6_ENV[@]}"}; do ARGS=$(jq -c --arg kv "$kv" '. + ["-e",$kv]' <<<"$ARGS"); done

job_manifest() {
  jq -n --arg run "$RUN" --argjson labels "$LABELS" --arg image "$K6_IMAGE" --argjson args "$ARGS" \
    --argjson items "$ITEMS" --argjson deadline "$DEADLINE" --arg tokens "$TOKENS_FILE" '
  {
    apiVersion: "batch/v1", kind: "Job",
    metadata: { name: $run, labels: $labels },
    spec: {
      backoffLimit: 0,                    # 부하 테스트는 다시 돌리면 결과가 섞인다 — 실패하면 그대로 실패
      activeDeadlineSeconds: $deadline,
      ttlSecondsAfterFinished: 900,       # 스크립트가 못 지웠을 때의 안전장치(ConfigMap·Secret도 ownerReference로 같이 지워진다)
      template: {
        metadata: { labels: $labels },
        spec: {
          restartPolicy: "Never",
          automountServiceAccountToken: false,
          enableServiceLinks: false,
          securityContext: { runAsNonRoot: true, runAsUser: 12345, runAsGroup: 12345, fsGroup: 12345, seccompProfile: { type: "RuntimeDefault" } },   # fsGroup: 토큰 Secret(0440)을 그룹으로 읽는다
          # 부하 발생기가 측정 대상과 같은 노드에서 CPU를 다투지 않게(증설 노드로 가도록). ng-ai는 taint라 toleration이 없어 가지 않는다
          affinity: { podAntiAffinity: { preferredDuringSchedulingIgnoredDuringExecution: [ { weight: 100, podAffinityTerm: {
            topologyKey: "kubernetes.io/hostname",
            labelSelector: { matchExpressions: [ { key: "app.kubernetes.io/name", operator: "In", values: ["gateway","booking","catalog"] } ] } } } ] } },
          containers: [ {
            name: "k6", image: $image, args: $args,
            env: ([ { name: "BASE_URL", value: "http://gateway:8080" } ]
                  + (if $tokens == "" then [] else [ { name: "K6_TOKENS_FILE", value: "/tokens/tokens.txt" } ] end)),
            resources: { requests: { cpu: "500m", memory: "256Mi" }, limits: { memory: "1Gi" } },
            securityContext: { allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: { drop: ["ALL"] } },
            volumeMounts: ([ { name: "scripts", mountPath: "/scripts", readOnly: true }, { name: "tmp", mountPath: "/tmp" } ]
                           + (if $tokens == "" then [] else [ { name: "tokens", mountPath: "/tokens", readOnly: true } ] end))
          } ],
          volumes: ([ { name: "scripts", configMap: { name: $run, items: $items } }, { name: "tmp", emptyDir: {} } ]
                    + (if $tokens == "" then [] else [ { name: "tokens", secret: { secretName: $run, defaultMode: 288 } } ] end))
        }
      }
    }
  }'
}

if [ "${DRY_RUN:-0}" = "1" ]; then
  job_manifest
  exit 0
fi

# ---- 사전 점검 (읽기만) ----------------------------------------------
kubectl -n "$NS" get deploy,rollout -o name >/dev/null 2>&1 || die "kubectl 조회 실패 — cinema-admin 자격 증명·노드 켜짐 확인"
for verb_res in create:jobs create:configmaps create:secrets delete:secrets; do
  [ "$(kubectl -n "$NS" auth can-i "${verb_res%%:*}" "${verb_res#*:}" 2>/dev/null)" = "yes" ] \
    || die "권한 없음: ${verb_res%%:*} ${verb_res#*:} (cinema-admin으로 실행)"
done
gw=$(kubectl -n "$NS" get endpoints gateway -o jsonpath='{.subsets[*].addresses[*].ip}' 2>/dev/null | wc -w | tr -d ' ')
[ "$gw" -gt 0 ] || die "gateway Ready 엔드포인트가 없다"
running=$(kubectl -n "$NS" get jobs -l "app.kubernetes.io/name=$LABEL_NAME" -o jsonpath='{range .items[?(@.status.active)]}{.metadata.name}{" "}{end}')
[ -z "$running" ] || die "이미 도는 k6 Job이 있다: $running — 결과가 섞인다. 끝나길 기다리거나 run-in-cluster.sh cleanup"
ai=$(kubectl -n "$NS" get pods -l app.kubernetes.io/name=ai --field-selector=status.phase=Running -o name 2>/dev/null | wc -l | tr -d ' ')
if [ "$ai" -gt 0 ] && [ "${ALLOW_AI:-0}" != "1" ]; then
  die "ai 파드가 떠 있다 — 부하 테스트 중에는 ng-ai 0·플래그 off(ADR-87, 런북 6-G). cluster-scale.sh down ng-ai 후 다시 (무시하려면 ALLOW_AI=1)"
fi
nodes=$(kubectl get nodes --no-headers 2>/dev/null | grep -cw Ready || true)
[ "$nodes" -ge 4 ] || echo "⚠️  Ready 노드 ${nodes}대 — 부하 발생기와 서비스가 CPU를 다툴 수 있다(권장: cluster-scale.sh up 2 → 4대, 런북 6-H)" >&2

mkdir -p "$RESULTS_DIR"; chmod 700 "$RESULTS_DIR"
LOG="$RESULTS_DIR/$RUN.log"

# shellcheck disable=SC2329  # trap으로 부른다
cleanup() {
  local rc=$?
  kubectl -n "$NS" delete secret "$RUN" --ignore-not-found >/dev/null 2>&1 || true
  if [ "${KEEP:-0}" != "1" ]; then
    kubectl -n "$NS" delete job "$RUN" --ignore-not-found --cascade=foreground --wait=false >/dev/null 2>&1 || true
    kubectl -n "$NS" delete configmap "$RUN" --ignore-not-found >/dev/null 2>&1 || true
  fi
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# ---- 실행 기록 머리말 (리포트용 — 토큰·계정 정보 없음) ------------------
{
  echo "# run=$RUN scenario=$SCENARIO script=$FILE image=$K6_IMAGE"
  echo "# started=$(date '+%Y-%m-%dT%H:%M:%S%z') git=$(git -C "$HERE" rev-parse --short HEAD 2>/dev/null || echo unknown) nodes_ready=$nodes"
  echo "# k6 env: ${K6_ENV[*]+${K6_ENV[*]}}"
  kubectl -n "$NS" get pods -l 'app.kubernetes.io/name in (gateway,booking,catalog,payment)' \
    -o custom-columns='# POD:.metadata.name,NODE:.spec.nodeName,IMAGE:.spec.containers[0].image' --no-headers 2>/dev/null
} | tee "$LOG"

# ---- 리소스 만들기 ------------------------------------------------------
with_labels() { jq -c --argjson labels "$LABELS" '.metadata.labels = $labels'; }   # 만들 때부터 라벨 → cleanup이 라벨로 찾는다
cm_args=()
for f in "${SCRIPT_FILES[@]}"; do cm_args+=(--from-file="$f=$HERE/$f"); done
kubectl -n "$NS" create configmap "$RUN" "${cm_args[@]}" --from-file="common.js=$HERE/lib/common.js" --dry-run=client -o json \
  | with_labels | kubectl -n "$NS" create -f - >/dev/null
if [ -n "$TOKENS_FILE" ]; then
  # 토큰은 파이프로만 지나간다(화면·파일에 남기지 않는다)
  kubectl -n "$NS" create secret generic "$RUN" --from-file="tokens.txt=$TOKENS_FILE" --dry-run=client -o json \
    | with_labels | kubectl -n "$NS" create -f - >/dev/null
fi

job_manifest | kubectl -n "$NS" create -f - >/dev/null
UID_=$(kubectl -n "$NS" get job "$RUN" -o jsonpath='{.metadata.uid}')
OWNER=$(jq -cn --arg n "$RUN" --arg u "$UID_" '{metadata:{ownerReferences:[{apiVersion:"batch/v1",kind:"Job",name:$n,uid:$u}]}}')
kubectl -n "$NS" patch configmap "$RUN" --type merge -p "$OWNER" >/dev/null
[ -z "$TOKENS_FILE" ] || kubectl -n "$NS" patch secret "$RUN" --type merge -p "$OWNER" >/dev/null
echo "▶ Job $RUN 생성 — 파드 기다리는 중 (이미지 pull·스케줄)" >&2

# ---- 파드가 뜰 때까지 (최대 180초) → 로그 따라가기 ------------------------
POD=""; phase=""
for _ in $(seq 1 90); do
  POD=$(kubectl -n "$NS" get pods -l "job-name=$RUN" -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)
  if [ -n "$POD" ]; then
    phase=$(kubectl -n "$NS" get pod "$POD" -o jsonpath='{.status.phase}' 2>/dev/null || true)
    case "$phase" in Running|Succeeded|Failed) break ;; esac
  fi
  sleep 2
done
if [ -z "$POD" ] || [ "$phase" = "Pending" ] || [ -z "$phase" ]; then
  echo "❌ 파드가 180초 안에 시작하지 않았다 — 이벤트:" >&2
  kubectl -n "$NS" get events --field-selector "involvedObject.name=${POD:-$RUN}" --sort-by=.lastTimestamp 2>/dev/null | tail -5 >&2
  exit 1
fi
echo "▶ 파드 $POD ($(kubectl -n "$NS" get pod "$POD" -o jsonpath='{.spec.nodeName}'))" >&2
kubectl -n "$NS" logs -f "$POD" -c k6 2>&1 | tee -a "$LOG" || true

# ---- 결과 ----------------------------------------------------------------
code=""
for _ in $(seq 1 30); do
  code=$(kubectl -n "$NS" get pod "$POD" -o jsonpath='{.status.containerStatuses[0].state.terminated.exitCode}' 2>/dev/null || true)
  [ -n "$code" ] && break
  sleep 2
done
echo "# finished=$(date '+%Y-%m-%dT%H:%M:%S%z') exit=${code:-unknown}" | tee -a "$LOG"
echo "로그: $LOG" >&2
case "$code" in
  0)  echo "✅ 임계값 통과" >&2; exit 0 ;;
  99) echo "❌ 임계값 실패 — 위 summary에서 ✗ 항목 확인 (tests/k6/README.md 「읽는 법」)" >&2; exit 99 ;;
  *)  echo "❌ k6 실행 실패(종료 코드 ${code:-없음}) — 사전 오류(setup throw)·Job 한도 초과(activeDeadlineSeconds ${DEADLINE}s)일 수 있다" >&2; exit 1 ;;
esac

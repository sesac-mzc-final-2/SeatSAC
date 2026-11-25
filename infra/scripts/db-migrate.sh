#!/usr/bin/env bash
# ------------------------------------------------------------------
# 6.1.5 데이터 이전 — 서비스 하나의 스키마를 기존 cinema-staging-mysql → cinema-staging-{svc}-db로 옮긴다 (ADR-115, 런북 8-E 3)
#   사용: infra/scripts/db-migrate.sh counts <svc>   두 인스턴스의 테이블별 행 수만(읽기만 — 언제든)
#         infra/scripts/db-migrate.sh copy   <svc>   대상이 비어 있을 때만 덤프 → 복원(파이프) → 행 수 대조
#         <svc> = catalog | booking | user | payment   (coupon·ai는 새 스키마라 옮길 것이 없다)
#   실행: 관리자(AWS_PROFILE=cinema-admin · kubectl 컨텍스트 cinema-admin) · 노드 켜짐
#   방법: cinema-staging에 일회성 ConfigMap(스크립트)·Job을 만든다 — 시드 적재(8-C)와 같은 GitOps 예외(이 절차만).
#         Job은 {svc}-migrator-secrets·cinema-infra-endpoints(DB_HOST·DB_HOST_{SVC})·rds-ca-bundle을 그대로 쓴다 → 비밀번호가 이 PC를 지나지 않는다
#         성공하면 Job·ConfigMap을 지운다. 실패하면 Job을 남긴다(로그 확인 뒤 KEEP=0 delete 안내대로)
#   copy 전 조건: 그 서비스의 쓰기가 멈춰 있어야 한다(Running 파드 0개) — 덤프 뒤 기존 인스턴스에 쓰인 것은 새 인스턴스에 없다(data-model 7-1).
#         catalog는 API가 조회뿐이라(관리자 API 없음·시드만 쓴다) ALLOW_RUNNING=1로 파드를 둔 채 옮길 수 있다
#   환경변수: NS(기본 cinema-staging) · IMAGE(기본 mysql:8.4.6 — db-bootstrap과 같은 클라이언트) · WAIT(기본 900초) · ALLOW_RUNNING(기본 0)
#   종료 코드: 0 PASS · 1 Job 실패(행 수 불일치 포함) · 2 사전 조건·kubectl 오류
# ------------------------------------------------------------------
set -euo pipefail

MODE="${1:-}"; SVC="${2:-}"
NS="${NS:-cinema-staging}"
IMAGE="${IMAGE:-mysql:8.4.6}"
WAIT="${WAIT:-900}"
DIR="$(cd "$(dirname "$0")" && pwd)/db-migrate"

die() { echo "❌ $*" >&2; exit 2; }
[[ "$MODE" =~ ^(counts|copy)$ ]] || die "사용: $0 counts|copy catalog|booking|user|payment"
[[ "$SVC" =~ ^(catalog|booking|user|payment)$ ]] || die "서비스는 catalog·booking·user·payment 중 하나 (받은 값: '${SVC}')"
SVC_UP="$(tr '[:lower:]' '[:upper:]' <<<"$SVC")"
[[ -f "$DIR/copy.sh" ]] || die "$DIR/copy.sh 없음"

ctx=$(kubectl config current-context 2>/dev/null) || die "kubectl 컨텍스트 없음"
echo "컨텍스트: $ctx · namespace: $NS · $MODE $SVC"

# 사전 조건 — 읽기만 (Secret 값은 보지 않는다. 이름·키만)
kubectl -n "$NS" get configmap cinema-infra-endpoints -o "jsonpath={.data.DB_HOST_${SVC_UP}}" | grep -q . \
  || die "cinema-infra-endpoints에 DB_HOST_${SVC_UP}가 없다 — 런북 8-E 2)(staging-k8s apply)를 먼저"
kubectl -n "$NS" get configmap cinema-infra-endpoints -o 'jsonpath={.data.DB_HOST}' | grep -q . \
  || die "cinema-infra-endpoints에 DB_HOST(기존 인스턴스)가 없다 — 이미 정리(8-E 4)했다면 옮길 원본이 없다"
kubectl -n "$NS" get secret "${SVC}-migrator-secrets" >/dev/null || die "Secret ${SVC}-migrator-secrets 없음"
kubectl -n "$NS" get configmap rds-ca-bundle >/dev/null || die "ConfigMap rds-ca-bundle 없음"

if [[ "$MODE" == copy && "${ALLOW_RUNNING:-0}" != 1 ]]; then
  running=$(kubectl -n "$NS" get pods -l "app.kubernetes.io/name=${SVC}" --field-selector=status.phase=Running -o name | wc -l | tr -d ' ')
  [[ "$running" == 0 ]] || die "${SVC} Running 파드 ${running}개 — 쓰기를 먼저 멈춘다(ops-api 「정지」 또는 런북 8-E 3 방법). catalog는 ALLOW_RUNNING=1 가능"
fi

NAME="db-migrate-${SVC}-$(date +%m%d%H%M%S)"
CM="${NAME}-scripts"
cleanup_ok() { kubectl -n "$NS" delete job "$NAME" --ignore-not-found >/dev/null; kubectl -n "$NS" delete configmap "$CM" --ignore-not-found >/dev/null; }

kubectl -n "$NS" create configmap "$CM" --from-file=copy.sh="$DIR/copy.sh" >/dev/null
kubectl -n "$NS" label configmap "$CM" app.kubernetes.io/name=db-migrate app.kubernetes.io/part-of=cinema >/dev/null

kubectl -n "$NS" apply -f - >/dev/null <<YAML
apiVersion: batch/v1
kind: Job
metadata:
  name: ${NAME}
  labels: { app.kubernetes.io/name: db-migrate, app.kubernetes.io/part-of: cinema }
spec:
  backoffLimit: 0                 # copy는 대상이 비어 있어야 해서 자동 재시도하지 않는다
  activeDeadlineSeconds: ${WAIT}
  template:
    metadata:
      labels: { app.kubernetes.io/name: db-migrate }
    spec:
      restartPolicy: Never
      automountServiceAccountToken: false
      securityContext: { runAsNonRoot: true, runAsUser: 999, runAsGroup: 999, fsGroup: 999 }   # mysql 이미지의 mysql 사용자
      containers:
        - name: migrate
          image: ${IMAGE}
          command: ["/bin/bash", "/scripts/copy.sh"]
          env:
            - { name: SVC, value: "${SVC}" }
            - { name: MODE, value: "${MODE}" }
            - name: SRC_HOST
              valueFrom: { configMapKeyRef: { name: cinema-infra-endpoints, key: DB_HOST } }
            - name: DST_HOST
              valueFrom: { configMapKeyRef: { name: cinema-infra-endpoints, key: DB_HOST_${SVC_UP} } }
          envFrom:
            - secretRef: { name: ${SVC}-migrator-secrets }   # FLYWAY_USER · FLYWAY_PASSWORD
          volumeMounts:
            - { name: scripts, mountPath: /scripts, readOnly: true }
            - { name: rds-ca, mountPath: /etc/rds-ca, readOnly: true }
            - { name: tmp, mountPath: /tmp }
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities: { drop: ["ALL"] }
          resources:
            requests: { cpu: 200m, memory: 256Mi }
            limits: { memory: 512Mi }
      volumes:
        - { name: scripts, configMap: { name: ${CM} } }
        - { name: rds-ca, configMap: { name: rds-ca-bundle } }
        - { name: tmp, emptyDir: { medium: Memory, sizeLimit: 16Mi } }   # 계정 cnf — 디스크에 남기지 않는다
YAML

echo "Job ${NAME} 생성 — 끝날 때까지 기다린다(최대 ${WAIT}초)"
deadline=$(( $(date +%s) + WAIT + 30 ))
state=""
while [[ $(date +%s) -lt $deadline ]]; do
  state=$(kubectl -n "$NS" get job "$NAME" -o 'jsonpath={.status.succeeded}/{.status.failed}' 2>/dev/null || true)
  [[ "$state" == 1/* ]] && break
  [[ "$state" == */1 ]] && break
  sleep 5
done
kubectl -n "$NS" logs "job/${NAME}" -c migrate 2>/dev/null || echo "(로그를 읽지 못했다 — 파드가 아직 뜨지 않았을 수 있다: kubectl -n $NS describe job $NAME)"

if [[ "$state" == 1/* ]]; then
  cleanup_ok
  echo "✅ ${MODE} ${SVC} 완료 (Job·ConfigMap 지움)"
  exit 0
fi
echo "❌ Job ${NAME} 실패 또는 시간 초과 (state=${state:-없음}) — Job·ConfigMap은 남겨 둔다"
echo "   확인 뒤: kubectl -n $NS delete job $NAME; kubectl -n $NS delete configmap $CM"
exit 1

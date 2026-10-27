#!/usr/bin/env bash
# ------------------------------------------------------------------
# 3.1.14 EKS 노드그룹 켜기·끄기 (cluster-schedule 워크플로와 사람이 같은 스크립트를 쓴다. 정시 실행은 EventBridge Scheduler)
#   사용: infra/scripts/cluster-scale.sh up|down|status [desired]
#     up     : 모든 노드그룹 desired = 1 (또는 [desired]) → 인스턴스 InService까지 대기
#     down   : 모든 노드그룹 desired = 0
#     status : 현재 desired·min·max·InService 수만 출력
#   환경변수: CLUSTER(기본 cinema-staging-eks), AWS_REGION(기본 ap-northeast-2), WAIT_SECONDS(기본 600)
#   WAIT_SECONDS는 실행 전체의 대기 한도: ACTIVE 대기·변경 결과 대기·InService 대기가 모두 같은 deadline을 쓴다
#   (aws eks wait는 최대 약 40분까지 기다리므로 쓰지 않는다 — Actions timeout 20분보다 길다)
#   권한: 사람은 AWS_PROFILE=cinema-admin, CI는 cinema-gha-cluster-schedule
#   Terraform은 desired_size 변경을 무시하므로 이 스크립트로 바꿔도 drift가 아니다 (3.1.2)
# ------------------------------------------------------------------
set -euo pipefail

ACTION="${1:-status}"
CLUSTER="${CLUSTER:-cinema-staging-eks}"
export AWS_REGION="${AWS_REGION:-ap-northeast-2}"
WAIT_SECONDS="${WAIT_SECONDS:-600}"

case "$ACTION" in
  up)     DESIRED="${2:-1}" ;;
  down)   DESIRED=0 ;;
  status) DESIRED="" ;;
  *) echo "사용: $0 up|down|status [desired]" >&2; exit 2 ;;
esac
if [ -n "$DESIRED" ] && ! [[ "$DESIRED" =~ ^[0-9]+$ ]]; then
  echo "desired는 0 이상의 정수: $DESIRED" >&2; exit 2
fi

DEADLINE=$(( $(date +%s) + WAIT_SECONDS ))
POLL_SECONDS="${POLL_SECONDS:-15}"

summary() { if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then echo "$*" >> "$GITHUB_STEP_SUMMARY"; fi; echo "$*"; }

NGS=$(aws eks list-nodegroups --cluster-name "$CLUSTER" --query 'nodegroups[]' --output text)
if [ -z "$NGS" ]; then echo "::error::$CLUSTER 에 노드그룹이 없습니다"; exit 1; fi

ng_field() { aws eks describe-nodegroup --cluster-name "$CLUSTER" --nodegroup-name "$1" --query "$2" --output text; }
in_service() {
  local asg
  asg=$(ng_field "$1" 'nodegroup.resources.autoScalingGroups[0].name')
  # shellcheck disable=SC2016  # JMESPath 리터럴(백틱)이라 확장하지 않는 게 맞다
  aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names "$asg" \
    --query 'length(AutoScalingGroups[0].Instances[?LifecycleState==`InService`])' --output text
}

# deadline 안에서만 기다린다. 넘으면 무엇을 기다리다 멈췄는지 남기고 실패
check_deadline() {
  if [ "$(date +%s)" -ge "$DEADLINE" ]; then
    echo "::error::${WAIT_SECONDS}초 안에 끝나지 않음: $1"; exit 1
  fi
}

health_issues() {
  aws eks describe-nodegroup --cluster-name "$CLUSTER" --nodegroup-name "$1" \
    --query 'nodegroup.health.issues[].[code,message]' --output text || true
}

# 노드그룹이 변경 가능한 상태가 될 때까지 (실패 상태면 바로 중단)
#   DEGRADED: 노드그룹은 있지만 건강 문제(인스턴스 생성 실패·서브넷 IP 부족 등)가 있는 상태.
#     down → 통과시킨다. 줄이는 것이 문제를 키우지 않고, 밤 끄기가 막히면 비용이 계속 나간다.
#            EKS가 변경 요청을 거부하면 그 자리에서 오류와 복구 절차를 출력한다 (request_scale)
#     up   → 중단. 같은 원인으로 인스턴스가 뜨지 않으므로 기다려도 의미가 없다
wait_active() {
  local ng="$1" st
  while :; do
    st=$(ng_field "$ng" 'nodegroup.status')
    case "$st" in
      ACTIVE) return 0 ;;
      DEGRADED)
        if [ "$ACTION" = "down" ]; then
          echo "::warning::$ng 상태 DEGRADED — 끄기는 계속 진행. 건강 문제:"
          health_issues "$ng"
          return 0
        fi
        echo "::error::$ng 상태 DEGRADED — 켜기 중단. 건강 문제:"; health_issues "$ng"; exit 1 ;;
      CREATE_FAILED|DELETE_FAILED|DELETING)
        echo "::error::$ng 상태 $st — describe-nodegroup의 health.issues 확인"; health_issues "$ng"; exit 1 ;;
    esac
    check_deadline "$ng ACTIVE 대기 (현재 $st)"
    sleep "$POLL_SECONDS"
  done
}

# update-nodegroup-config 결과(Successful)까지 (Failed·Cancelled면 오류 내용 출력 후 중단)
wait_update() {
  local ng="$1" id="$2" st
  while :; do
    st=$(aws eks describe-update --name "$CLUSTER" --nodegroup-name "$ng" --update-id "$id" \
      --query 'update.status' --output text)
    case "$st" in
      Successful) return 0 ;;
      Failed|Cancelled)
        aws eks describe-update --name "$CLUSTER" --nodegroup-name "$ng" --update-id "$id" \
          --query 'update.errors' --output json
        echo "::error::$ng 변경 $id 이 $st"; exit 1 ;;
    esac
    check_deadline "$ng 변경 $id 대기 (현재 $st)"
    sleep "$POLL_SECONDS"
  done
}

UPDATES=()
# 1) 변경 요청 (이미 같은 값이면 건너뜀 — EKS는 변경 없는 요청을 오류로 돌려준다)
if [ -n "$DESIRED" ]; then
  for ng in $NGS; do
    read -r cur min max status < <(ng_field "$ng" '[nodegroup.scalingConfig.desiredSize,nodegroup.scalingConfig.minSize,nodegroup.scalingConfig.maxSize,nodegroup.status]')
    if [ "$DESIRED" -lt "$min" ] || [ "$DESIRED" -gt "$max" ]; then
      echo "::error::$ng desired=$DESIRED 가 min=$min~max=$max 범위 밖"; exit 1
    fi
    if [ "$status" != "ACTIVE" ]; then
      echo "$ng 상태 $status → ACTIVE 대기"
      wait_active "$ng"
    fi
    if [ "$cur" = "$DESIRED" ]; then
      echo "$ng: 이미 desired=$DESIRED"
      continue
    fi
    echo "$ng: desired $cur → $DESIRED"
    if ! id=$(aws eks update-nodegroup-config --cluster-name "$CLUSTER" --nodegroup-name "$ng" \
        --scaling-config "desiredSize=$DESIRED" --query 'update.id' --output text); then
      echo "::error::$ng 변경 요청을 EKS가 거부함 (상태 $(ng_field "$ng" 'nodegroup.status')). 복구 절차: 런북 8-1-4"
      exit 1
    fi
    UPDATES+=("$ng $id")
  done
  for u in ${UPDATES[@]+"${UPDATES[@]}"}; do   # bash 3.2(macOS)·set -u 에서 빈 배열 대비
    read -r ng id <<< "$u"
    wait_update "$ng" "$id"
  done
  for ng in $NGS; do
    wait_active "$ng"
  done
fi

# 2) 켤 때는 인스턴스가 실제로 뜰 때까지 기다린다 (Ready 판정은 kubelet 등록까지 1~2분 더 걸린다)
if [ "$ACTION" = "up" ]; then
  for ng in $NGS; do
    until [ "$(in_service "$ng")" -ge "$DESIRED" ]; do
      check_deadline "$ng 인스턴스 InService 대기 (용량 부족·SCP·서브넷 IP 확인)"
      sleep "$POLL_SECONDS"
    done
  done
fi

# 3) 결과
summary "### $CLUSTER 노드그룹 ($ACTION, $(TZ=Asia/Seoul date '+%F %H:%M KST'))"
summary ""
summary "| 노드그룹 | desired | min | max | InService | 상태 |"
summary "| --- | --- | --- | --- | --- | --- |"
for ng in $NGS; do
  read -r cur min max status < <(ng_field "$ng" '[nodegroup.scalingConfig.desiredSize,nodegroup.scalingConfig.minSize,nodegroup.scalingConfig.maxSize,nodegroup.status]')
  summary "| $ng | $cur | $min | $max | $(in_service "$ng") | $status |"
done

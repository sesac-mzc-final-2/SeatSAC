#!/usr/bin/env bash
# ------------------------------------------------------------------
# 3.1.14 EKS 노드그룹 켜기·끄기 (cluster-schedule 워크플로와 사람이 같은 스크립트를 쓴다)
#   사용: infra/scripts/cluster-scale.sh up|down|status [desired]
#     up     : 모든 노드그룹 desired = 1 (또는 [desired]) → 인스턴스 InService까지 대기
#     down   : 모든 노드그룹 desired = 0
#     status : 현재 desired·min·max·InService 수만 출력
#   환경변수: CLUSTER(기본 cinema-staging-eks), AWS_REGION(기본 ap-northeast-2), WAIT_SECONDS(기본 600)
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

# 1) 변경 요청 (이미 같은 값이면 건너뜀 — EKS는 변경 없는 요청을 오류로 돌려준다)
if [ -n "$DESIRED" ]; then
  for ng in $NGS; do
    read -r cur min max status < <(ng_field "$ng" '[nodegroup.scalingConfig.desiredSize,nodegroup.scalingConfig.minSize,nodegroup.scalingConfig.maxSize,nodegroup.status]')
    if [ "$DESIRED" -lt "$min" ] || [ "$DESIRED" -gt "$max" ]; then
      echo "::error::$ng desired=$DESIRED 가 min=$min~max=$max 범위 밖"; exit 1
    fi
    if [ "$status" != "ACTIVE" ]; then
      echo "$ng 상태 $status → ACTIVE 대기"
      aws eks wait nodegroup-active --cluster-name "$CLUSTER" --nodegroup-name "$ng"
    fi
    if [ "$cur" = "$DESIRED" ]; then
      echo "$ng: 이미 desired=$DESIRED"
      continue
    fi
    echo "$ng: desired $cur → $DESIRED"
    aws eks update-nodegroup-config --cluster-name "$CLUSTER" --nodegroup-name "$ng" \
      --scaling-config "desiredSize=$DESIRED" --query 'update.id' --output text
  done
  for ng in $NGS; do
    aws eks wait nodegroup-active --cluster-name "$CLUSTER" --nodegroup-name "$ng"
  done
fi

# 2) 켤 때는 인스턴스가 실제로 뜰 때까지 기다린다 (Ready 판정은 kubelet 등록까지 1~2분 더 걸린다)
if [ "$ACTION" = "up" ]; then
  deadline=$(( $(date +%s) + WAIT_SECONDS ))
  for ng in $NGS; do
    until [ "$(in_service "$ng")" -ge "$DESIRED" ]; do
      if [ "$(date +%s)" -ge "$deadline" ]; then
        echo "::error::$ng 인스턴스가 ${WAIT_SECONDS}초 안에 InService가 되지 않음 (용량 부족·SCP·서브넷 IP 확인)"; exit 1
      fi
      sleep 15
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

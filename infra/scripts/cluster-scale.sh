#!/usr/bin/env bash
# ------------------------------------------------------------------
# 3.1.14 EKS 노드그룹 켜기·끄기 (cluster-schedule 워크플로와 사람이 같은 스크립트를 쓴다. 정시 실행은 EventBridge Scheduler)
#   사용: infra/scripts/cluster-scale.sh up|down|status [desired] [노드그룹]
#     up     : 기본 노드그룹 desired = 1 (또는 [desired]) → 인스턴스 InService까지 대기
#              평소 꺼 두는 노드그룹(OPTIONAL_NODEGROUPS, 기본 ng-ai)은 **빼고** 켠다 → 평일 아침 자동 켜기·`up 2` 부하 테스트에 ng-ai가 따라 켜지지 않는다
#     down   : 모든 노드그룹 desired = 0 (ng-ai 포함 — 끄기는 전부 끄는 쪽이 안전하다. 숫자 인자는 무시)
#     status : 현재 desired·min·max·InService 수만 출력 (모든 노드그룹)
#   노드그룹을 지정하면 그것만 조작한다(이름이 같거나 "-이름"으로 끝나면 일치):
#     cluster-scale.sh up ng-ai        ng-ai 1대 켜기 (AI 데모·6-G-2 확인)
#     cluster-scale.sh down ng-ai      ng-ai 끄기
#     cluster-scale.sh status ng-ai    ng-ai만 표시
#   [desired] 숫자와 [노드그룹]은 순서 무관(`up 2 ng-ai` = `up ng-ai 2`)
#   환경변수: CLUSTER(기본 cinema-staging-eks), AWS_REGION(기본 ap-northeast-2), WAIT_SECONDS(기본 600), OPTIONAL_NODEGROUPS(기본 ng-ai, 공백 구분)
#   WAIT_SECONDS는 실행 전체의 대기 한도: ACTIVE 대기·변경 결과 대기·InService 대기가 모두 같은 deadline을 쓴다
#   (aws eks wait는 최대 약 40분까지 기다리므로 쓰지 않는다 — Actions timeout 20분보다 길다)
#   권한: 사람은 AWS_PROFILE=cinema-admin, CI는 cinema-gha-cluster-schedule
#   Terraform은 desired_size 변경을 무시하므로 이 스크립트로 바꿔도 drift가 아니다 (3.1.2)
# ------------------------------------------------------------------
set -euo pipefail

ACTION="${1:-status}"
if [ $# -gt 0 ]; then shift; fi
CLUSTER="${CLUSTER:-cinema-staging-eks}"
export AWS_REGION="${AWS_REGION:-ap-northeast-2}"
WAIT_SECONDS="${WAIT_SECONDS:-600}"
OPTIONAL_NODEGROUPS="${OPTIONAL_NODEGROUPS-ng-ai}"    # 평소 0대 — up 기본 대상에서 뺀다 (빈 값으로 지정하면 제외 없음)

DESIRED_ARG=""
TARGET=""
for a in "$@"; do
  if [[ "$a" =~ ^[0-9]+$ ]]; then
    if [ -n "$DESIRED_ARG" ]; then echo "desired 숫자는 하나만: $*" >&2; exit 2; fi
    DESIRED_ARG="$a"
  else
    if [ -n "$TARGET" ]; then echo "노드그룹은 하나만: $*" >&2; exit 2; fi
    TARGET="$a"
  fi
done

case "$ACTION" in
  up)     DESIRED="${DESIRED_ARG:-1}" ;;
  down)   DESIRED=0 ;;      # 워크플로가 down에도 desired를 넘기므로 숫자 인자는 무시
  status) DESIRED="" ;;
  *)
    echo "사용: $0 up|down|status [desired] [노드그룹]" >&2
    case "${1:-}" in up|down|status) echo "힌트: 동작이 먼저다 → $0 $1 $ACTION" >&2 ;; esac   # 옛 형식 'ng-ai up' 실수 방지
    exit 2 ;;
esac
if [ -n "$DESIRED" ] && ! [[ "$DESIRED" =~ ^[0-9]+$ ]]; then
  echo "desired는 0 이상의 정수: $DESIRED" >&2; exit 2
fi

DEADLINE=$(( $(date +%s) + WAIT_SECONDS ))
POLL_SECONDS="${POLL_SECONDS:-15}"

summary() { if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then echo "$*" >> "$GITHUB_STEP_SUMMARY"; fi; echo "$*"; }

ALL_NGS=$(aws eks list-nodegroups --cluster-name "$CLUSTER" --query 'nodegroups[]' --output text)
if [ -z "$ALL_NGS" ]; then echo "::error::$CLUSTER 에 노드그룹이 없습니다"; exit 1; fi

# 노드그룹 이름이 키와 같거나 "-키"로 끝나면 일치 (실제 이름은 cinema-staging-ng-ai 처럼 접두사가 붙는다)
matches() {
  local ng="$1" key
  for key in $2; do
    if [ "$ng" = "$key" ] || [[ "$ng" == *-"$key" ]]; then return 0; fi
  done
  return 1
}

# 조작 대상 고르기: 지정 > up 기본(평소 0대 제외) > down·status(전부)
NGS=""
if [ -n "$TARGET" ]; then
  for ng in $ALL_NGS; do
    if matches "$ng" "$TARGET"; then NGS="$NGS $ng"; fi
  done
  NGS="${NGS# }"
  if [ -z "$NGS" ]; then echo "::error::노드그룹 '$TARGET' 가 없습니다 (있는 것: $ALL_NGS)"; exit 1; fi
elif [ "$ACTION" = "up" ]; then
  for ng in $ALL_NGS; do
    if ! matches "$ng" "$OPTIONAL_NODEGROUPS"; then NGS="$NGS $ng"; fi
  done
  NGS="${NGS# }"
  if [ -z "$NGS" ]; then echo "::error::켤 노드그룹이 없습니다 (제외: $OPTIONAL_NODEGROUPS)"; exit 1; fi
else
  NGS="$ALL_NGS"
fi
echo "대상 노드그룹($ACTION): $NGS"

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
SHOW_NGS="$NGS"
if [ -z "$TARGET" ]; then SHOW_NGS="$ALL_NGS"; fi   # 지정이 없으면 전체 표시 — up 때 ng-ai가 0으로 남은 것도 보인다
for ng in $SHOW_NGS; do
  read -r cur min max status < <(ng_field "$ng" '[nodegroup.scalingConfig.desiredSize,nodegroup.scalingConfig.minSize,nodegroup.scalingConfig.maxSize,nodegroup.status]')
  summary "| $ng | $cur | $min | $max | $(in_service "$ng") | $status |"
done

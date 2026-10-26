# ------------------------------------------------------------------
# 3.1.14 가동 정책 (OPS-13, ADR-34) — 노드그룹 켜기·끄기의 기본 실행 수단: EventBridge Scheduler
#   GitHub Actions schedule은 늦거나 빠진다(10/23 끄기 4시간 지연, 10/26 켜기 6시간 지연, 10/26 끄기 누락)
#   → AWS가 KST 시각에 정확히 노드그룹 desired를 바꾼다. GitHub cluster-schedule은 30분 뒤 백업 + 수동 실행용으로 남긴다
#
#   월~금 08:23 KST  desired 1  (노드그룹마다 일정 1개, eks:UpdateNodegroupConfig 직접 호출 — Lambda 없음)
#   월~금 23:07 KST  desired 0  (데모 주간 시작일 0시부터 멈춤 = 24시간 가동, var.cluster_keep_on=true면 멈춤)
#
#   - 노드그룹 이름은 모듈이 접미사를 붙여 만들므로 모듈 출력에서 받는다(global이 아니라 여기 두는 이유)
#   - 이미 같은 값이면 EKS가 "No changes needed"로 거절한다 → 실패로 세지만 해가 없다(재시도 2회 뒤 버림)
#   - 다른 변경이 진행 중이면(ResourceInUse) 재시도 정책이 1시간 안에서 다시 시도한다
#   - 사람의 수동 켜기·끄기(cluster-scale.sh)와 섞여도 desired만 바꾸므로 마지막 호출이 이긴다
# ------------------------------------------------------------------

locals {
  # 노드그룹 키(ng-a·ng-c) → 실제 이름·ARN
  schedule_node_groups = {
    for k, v in module.eks.eks_managed_node_groups : k => {
      name = split(":", v.node_group_id)[1] # node_group_id = "클러스터:노드그룹"
      arn  = v.node_group_arn
    }
  }

  cluster_schedule_actions = {
    up = {
      cron     = "cron(23 8 ? * MON-FRI *)"
      desired  = 1
      state    = "ENABLED"
      end_date = null # 데모 주간에도 켜기는 계속(이미 켜져 있으면 no-op). 발표 후에는 destroy
    }
    down = {
      cron     = "cron(7 23 ? * MON-FRI *)"
      desired  = 0
      state    = var.cluster_keep_on ? "DISABLED" : "ENABLED"
      end_date = "${var.cluster_schedule_demo_start}T00:00:00+09:00" # 데모 주간부터 끄지 않는다
    }
  }

  cluster_schedules = merge([
    for ng_key, ng in local.schedule_node_groups : {
      for action, a in local.cluster_schedule_actions : "${ng_key}-${action}" => merge(a, {
        node_group = ng.name
        action     = action
      })
    }
  ]...)
}

resource "aws_scheduler_schedule_group" "cluster" {
  name = "${var.name}-cluster"
}

resource "aws_iam_role" "cluster_scheduler" {
  name        = "${var.name}-cluster-scheduler"
  description = "EventBridge Scheduler: EKS nodegroup desired size 1 <-> 0 (3.1.14)"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "scheduler.amazonaws.com" }
      Action    = "sts:AssumeRole"
      Condition = {
        StringEquals = {
          "aws:SourceAccount" = local.account_id
          "aws:SourceArn"     = aws_scheduler_schedule_group.cluster.arn # 일정 그룹 ARN (일정 ARN이 아니다)
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "cluster_scheduler" {
  name = "eks-nodegroup-desired"
  role = aws_iam_role.cluster_scheduler.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid      = "NodegroupScale"
      Effect   = "Allow"
      Action   = ["eks:UpdateNodegroupConfig"]
      Resource = [for ng in local.schedule_node_groups : ng.arn]
    }]
  })
}

resource "aws_scheduler_schedule" "cluster" {
  for_each = local.cluster_schedules

  name        = "${each.value.node_group}-${each.value.action}"
  group_name  = aws_scheduler_schedule_group.cluster.name
  description = "${each.value.action}: ${each.value.node_group} desired ${each.value.desired} (월~금 KST, 3.1.14)"
  state       = each.value.state
  end_date    = each.value.end_date == null ? null : timeadd(each.value.end_date, "0s") # RFC3339(UTC)로 정규화

  schedule_expression          = each.value.cron
  schedule_expression_timezone = "Asia/Seoul"

  flexible_time_window {
    mode = "OFF" # 정확히 그 시각
  }

  target {
    arn      = "arn:aws:scheduler:::aws-sdk:eks:updateNodegroupConfig"
    role_arn = aws_iam_role.cluster_scheduler.arn
    input = jsonencode({
      ClusterName   = module.eks.cluster_name
      NodegroupName = each.value.node_group
      ScalingConfig = { DesiredSize = each.value.desired }
    })

    retry_policy {
      maximum_event_age_in_seconds = 3600 # 진행 중인 변경(ResourceInUse)이 끝날 시간
      maximum_retry_attempts       = 2
    }
  }
}

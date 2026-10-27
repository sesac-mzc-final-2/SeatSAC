# ------------------------------------------------------------------
# 3.1.14 가동 정책 (OPS-13): cluster-schedule 워크플로(백업·수동)가 노드그룹 desired 1 ↔ 0
#   정시 실행은 EventBridge Scheduler(envs/staging cluster_schedule.tf, 역할 cinema-staging-cluster-scheduler)
#   cinema-gha-cluster-schedule : main 브랜치(schedule·workflow_dispatch)에서만 → secrets.AWS_CLUSTER_SCHEDULE_ROLE_ARN
#   권한: 이 클러스터의 노드그룹 조회·desired 변경 + ASG 인스턴스 수 조회. 클러스터 생성·삭제·kubectl 권한 없음
# ------------------------------------------------------------------

resource "aws_iam_role" "gha_cluster_schedule" {
  name                 = "cinema-gha-cluster-schedule"
  description          = "GitHub Actions cluster-schedule (main only): EKS nodegroup desired size 1 <-> 0"
  assume_role_policy   = data.aws_iam_policy_document.gha_main.json
  max_session_duration = 3600
}

data "aws_iam_policy_document" "cluster_schedule" {
  statement {
    sid       = "ClusterRead"
    actions   = ["eks:DescribeCluster", "eks:ListNodegroups"]
    resources = ["arn:aws:eks:${var.region}:${local.account_id}:cluster/${var.eks_cluster_name}"]
  }
  statement {
    sid       = "NodegroupScale"
    actions   = ["eks:DescribeNodegroup", "eks:UpdateNodegroupConfig"]
    resources = ["arn:aws:eks:${var.region}:${local.account_id}:nodegroup/${var.eks_cluster_name}/*/*"]
  }
  statement {
    # DescribeUpdate는 클러스터 ARN이 필수 리소스(cluster*)다. 노드그룹 ARN만 주면 AccessDenied (10/24 down 실행에서 확인)
    sid     = "UpdateRead"
    actions = ["eks:DescribeUpdate"]
    resources = [
      "arn:aws:eks:${var.region}:${local.account_id}:cluster/${var.eks_cluster_name}",
      "arn:aws:eks:${var.region}:${local.account_id}:nodegroup/${var.eks_cluster_name}/*/*",
    ]
  }
  statement {
    # 예약 백업이 EventBridge 끄기 일정 상태(cluster_keep_on)를 확인한다. ListSchedules는 리소스 지정 불가
    sid       = "SchedulerRead"
    actions   = ["scheduler:ListSchedules"]
    resources = ["*"]
  }
  statement {
    sid       = "AsgRead"
    actions   = ["autoscaling:DescribeAutoScalingGroups"]
    resources = ["*"] # Describe API는 리소스 지정 불가
  }
}

resource "aws_iam_role_policy" "gha_cluster_schedule" {
  name   = "eks-nodegroup-scale"
  role   = aws_iam_role.gha_cluster_schedule.id
  policy = data.aws_iam_policy_document.cluster_schedule.json
}

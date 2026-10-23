# ------------------------------------------------------------------
# 3.1.14 가동 정책 (OPS-13): cluster-schedule 워크플로가 노드그룹 desired 1 ↔ 0
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
    actions   = ["eks:DescribeNodegroup", "eks:UpdateNodegroupConfig", "eks:DescribeUpdate"]
    resources = ["arn:aws:eks:${var.region}:${local.account_id}:nodegroup/${var.eks_cluster_name}/*/*"]
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

# ------------------------------------------------------------------
# 6.1.6 Karpenter — AWS 쪽 (ADR-122)
#   - 컨트롤러 권한: Pod Identity cinema-staging-karpenter-pod (conventions 9장) → karpenter/karpenter ServiceAccount
#   - 노드 역할: cinema-staging-karpenter-node + EKS Access Entry(EC2_LINUX) — 노드가 클러스터에 붙는 권한
#   - 중단 알림: SQS cinema-staging-karpenter + EventBridge 규칙(스팟 회수·리밸런스·인스턴스 상태 변경·예정 이벤트)
#     → 컨트롤러가 노드를 먼저 비운다. 우리는 On-Demand만 쓰지만 상태 변경·예정 유지보수 이벤트도 같은 큐로 온다
#   - 노드가 쓸 서브넷·보안그룹은 태그 karpenter.sh/discovery = cinema-staging-eks 로 찾는다(network.tf app 서브넷, eks.tf 노드 SG)
#     → Karpenter 노드도 관리형 노드와 같은 app 서브넷·노드 보안그룹이라 RDS·Valkey(app 서브넷 CIDR 규칙)·ALB → 8080 규칙이 그대로 맞는다
#   - 컨트롤러 설치(Helm)는 envs/staging-k8s/karpenter.tf, NodePool·EC2NodeClass는 gitops platform/karpenter
#   - 밤 끄기: 컨트롤러는 관리형 노드(ng-a·ng-c)에만 뜬다(차트 기본 affinity karpenter.sh/nodepool DoesNotExist).
#     EventBridge가 관리형 노드그룹을 0대로 내리면 컨트롤러도 멈춰 Karpenter가 만든 노드는 그대로 남는다(비용) →
#     아래 정리 Lambda가 15분마다 "관리형 노드그룹이 모두 0대일 때만" Karpenter 노드를 지운다(런북 6-K-4)
#   - var.karpenter_enabled = false면 아무것도 만들지 않는다(태그만 남는다 — 태그는 해가 없다)
# ------------------------------------------------------------------

locals {
  karpenter_discovery_tag = { "karpenter.sh/discovery" = module.eks.cluster_name }
  karpenter_cluster_tag   = "kubernetes.io/cluster/${module.eks.cluster_name}" # Karpenter가 만든 인스턴스에 붙이는 태그(= owned)
}

module "karpenter" {
  source = "terraform-aws-modules/eks/aws//modules/karpenter"
  # eks 모듈(eks.tf)과 같은 메이저. 21.8.0 이상 — 아래 enable_inline_policy가 21.8.0에 생겼다(21.7 이하는 Unsupported argument).
  # 모듈 버전은 .terraform.lock.hcl에 고정되지 않아, 예전에 init한 폴더는 terraform init -upgrade로 받아야 한다
  version = ">= 21.8.0, < 22.0.0"

  create       = var.karpenter_enabled
  cluster_name = module.eks.cluster_name

  # 컨트롤러 — Pod Identity만(IRSA 안 씀, eks.tf enable_irsa = false)
  iam_role_name              = "${var.name}-karpenter-pod" # cinema-staging-karpenter-pod
  iam_role_use_name_prefix   = false
  iam_policy_name            = "${var.name}-karpenter-pod"
  iam_policy_use_name_prefix = false
  # 컨트롤러 정책 문서가 관리형 정책 상한(6,144자)을 넘어 CreatePolicy가 LimitExceeded로 실패한다(11/26 apply).
  # 역할 인라인 정책(상한 10,240자)으로 만든다 — 모듈이 이 오류를 위해 둔 옵션. 권한 내용은 같고 정책 ARN만 없다
  enable_inline_policy            = true
  create_pod_identity_association = true
  namespace                       = "karpenter" # envs/staging-k8s/karpenter.tf와 같은 값
  service_account                 = "karpenter"

  # 노드 — 관리형 노드와 같이 SSM 세션만(키 페어 없음)
  node_iam_role_name            = "${var.name}-karpenter-node" # gitops EC2NodeClass spec.role (이름만 — ARN은 gitops에 두지 않는다)
  node_iam_role_use_name_prefix = false
  node_iam_role_additional_policies = {
    ssm = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
  }

  queue_name       = "${var.name}-karpenter" # envs/staging-k8s가 이름으로 찾는다(settings.interruptionQueue)
  rule_name_prefix = "cinema-karpenter"
}

# ---- 밤 정리: 관리형 노드그룹이 0대면 Karpenter 노드를 지운다 ----------------------
#   왜: 컨트롤러가 관리형 노드에 있어서, 밤에 관리형 노드그룹을 0대로 내리면 컨트롤러가 멈추고 Karpenter 노드가 남는다.
#       EventBridge Scheduler는 쿠버네티스 API를 부를 수 없어 NodePool을 직접 끌 수 없다 → AWS API로 인스턴스만 지운다
#   조건: 클러스터의 관리형 노드그룹 desired가 전부 0일 때만(데모 주간 24시간 가동·사람이 켜 둔 낮에는 아무것도 하지 않는다)
#   대상: 태그 kubernetes.io/cluster/cinema-staging-eks = owned + karpenter.sh/nodepool 이 있는 인스턴스만(IAM 조건으로도 막는다)
#   아침: 관리형 노드가 뜨면 컨트롤러가 다시 뜨고, 인스턴스가 없어진 NodeClaim은 스스로 정리한 뒤 필요하면 새로 만든다
data "archive_file" "karpenter_sweep" {
  count       = var.karpenter_enabled ? 1 : 0
  type        = "zip"
  source_file = "${path.module}/../../../../lambdas/karpenter-sweep/handler.py"
  output_path = "${path.module}/.build/karpenter-sweep.zip"
}

resource "aws_iam_role" "karpenter_sweep" {
  count              = var.karpenter_enabled ? 1 : 0
  name               = "${var.name}-karpenter-sweep" # cinema-staging-karpenter-sweep
  description        = "6.1.6 terminate Karpenter nodes only while all managed nodegroups are 0 (night)"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json # cognito.tf
}

resource "aws_cloudwatch_log_group" "karpenter_sweep" {
  count             = var.karpenter_enabled ? 1 : 0
  name              = "/aws/lambda/${var.name}-karpenter-sweep"
  retention_in_days = 7
}

data "aws_iam_policy_document" "karpenter_sweep" {
  count = var.karpenter_enabled ? 1 : 0

  statement {
    sid       = "WriteLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.karpenter_sweep[0].arn}:*"]
  }

  statement {
    sid       = "ReadNodegroups"
    actions   = ["eks:ListNodegroups", "eks:DescribeNodegroup"]
    resources = [module.eks.cluster_arn, "arn:aws:eks:${var.region}:${local.account_id}:nodegroup/${module.eks.cluster_name}/*/*"]
  }

  statement {
    sid       = "FindInstances"
    actions   = ["ec2:DescribeInstances"]
    resources = ["*"] # Describe 계열은 리소스 범위를 지원하지 않는다
  }

  # 지우는 것은 Karpenter가 이 클러스터에 만든 인스턴스뿐 — 관리형 노드(ASG)·다른 인스턴스는 태그 조건에서 빠진다
  statement {
    sid       = "TerminateKarpenterNodes"
    actions   = ["ec2:TerminateInstances"]
    resources = ["arn:aws:ec2:${var.region}:${local.account_id}:instance/*"]
    condition {
      test     = "StringEquals"
      variable = "aws:ResourceTag/${local.karpenter_cluster_tag}"
      values   = ["owned"]
    }
    condition {
      test     = "Null"
      variable = "aws:ResourceTag/karpenter.sh/nodepool"
      values   = ["false"]
    }
  }
}

resource "aws_iam_role_policy" "karpenter_sweep" {
  count  = var.karpenter_enabled ? 1 : 0
  name   = "sweep-karpenter-nodes"
  role   = aws_iam_role.karpenter_sweep[0].id
  policy = data.aws_iam_policy_document.karpenter_sweep[0].json
}

resource "aws_lambda_function" "karpenter_sweep" {
  count            = var.karpenter_enabled ? 1 : 0
  function_name    = "${var.name}-karpenter-sweep" # cinema-staging-karpenter-sweep (conventions 9장 Lambda)
  description      = "6.1.6 terminate Karpenter nodes when every managed nodegroup is desired 0"
  role             = aws_iam_role.karpenter_sweep[0].arn
  runtime          = "python3.12"
  handler          = "handler.handler"
  filename         = data.archive_file.karpenter_sweep[0].output_path
  source_code_hash = data.archive_file.karpenter_sweep[0].output_base64sha256
  timeout          = 60
  memory_size      = 128
  architectures    = ["arm64"]

  environment {
    variables = {
      CLUSTER_NAME = module.eks.cluster_name
      DRY_RUN      = "false" # 처음 확인할 때는 콘솔이 아니라 런북 6-K-4처럼 test 이벤트 {"dryRun": true}로 부른다
    }
  }

  logging_config {
    log_format = "Text" # 핸들러가 JSON 한 줄을 직접 찍는다(lambda.tf와 같다)
    log_group  = aws_cloudwatch_log_group.karpenter_sweep[0].name
  }

  depends_on = [aws_iam_role_policy.karpenter_sweep]
}

# 15분마다(하루 96번, Lambda 무료 범위). 낮에는 관리형 노드그룹이 켜져 있어 바로 끝난다
resource "aws_iam_role_policy" "cluster_scheduler_karpenter_sweep" {
  count = var.karpenter_enabled ? 1 : 0
  name  = "invoke-karpenter-sweep"
  role  = aws_iam_role.cluster_scheduler.id # cluster_schedule.tf (같은 일정 그룹)
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid      = "InvokeSweep"
      Effect   = "Allow"
      Action   = ["lambda:InvokeFunction"]
      Resource = [aws_lambda_function.karpenter_sweep[0].arn]
    }]
  })
}

resource "aws_scheduler_schedule" "karpenter_sweep" {
  count       = var.karpenter_enabled ? 1 : 0
  name        = "${var.name}-karpenter-sweep"
  group_name  = aws_scheduler_schedule_group.cluster.name
  description = "6.1.6 every 15 min: Karpenter nodes off while managed nodegroups are 0"
  state       = "ENABLED"

  schedule_expression          = "rate(15 minutes)"
  schedule_expression_timezone = "Asia/Seoul"

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = aws_lambda_function.karpenter_sweep[0].arn
    role_arn = aws_iam_role.cluster_scheduler.arn
    input    = jsonencode({ source = "schedule" })

    retry_policy {
      maximum_event_age_in_seconds = 600 # 다음 실행(15분)이 곧 온다
      maximum_retry_attempts       = 1
    }
  }

  depends_on = [aws_iam_role_policy.cluster_scheduler_karpenter_sweep]
}

output "karpenter_queue_name" {
  description = "6.1.6 중단 알림 큐 — envs/staging-k8s가 settings.interruptionQueue로 쓴다(이름으로 조회)"
  value       = module.karpenter.queue_name
}

output "karpenter_node_role_name" {
  description = "6.1.6 Karpenter 노드 역할 이름 — gitops platform/karpenter EC2NodeClass spec.role과 같아야 한다"
  value       = module.karpenter.node_iam_role_name
}

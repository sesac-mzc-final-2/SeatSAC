# ------------------------------------------------------------------
# 3.1.9 예매 완료 알림 큐 · DLQ · booking Pod Identity · DLQ 경보 (OPS-07)
#   - 이름은 docs/conventions.md 5장: cinema-staging-notification-events · …-dlq (maxReceiveCount 3)
#   - 발행자는 booking 하나(아웃박스 릴레이, 3.2.6). 소비자는 알림 Lambda(3.1.8)
#     → Lambda가 붙기 전에는 메시지가 큐에 쌓인다(보존 4일). 그동안 DLQ로는 가지 않는다(받는 쪽이 없으므로)
#   - 큐 URL은 이름으로 정해져 envs/staging-k8s의 ConfigMap cinema-infra-endpoints(SQS_NOTIFICATION_URL)가 이미 갖고 있다
#   - 암호화: SQS 관리형 키(SSE-SQS). KMS 권한 없이 발행·수신 가능. TLS가 아닌 요청은 큐 정책으로 거부
#   - booking 파드 권한: Pod Identity(cinema-staging-booking-pod) → SendMessage·GetQueueUrl (런타임 계약 2장)
#     ServiceAccount booking(cinema-staging)은 SeatSAC-gitops base/booking/serviceaccount.yaml
# ------------------------------------------------------------------

locals {
  notification_queue_name = "${var.name}-notification-events" # cinema-staging-notification-events
}

resource "aws_sqs_queue" "notification_dlq" {
  name                      = "${local.notification_queue_name}-dlq"
  message_retention_seconds = 1209600 # 14일(최대) — 실패한 알림을 원인 확인 뒤 다시 보낼 시간
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "notification" {
  name                      = local.notification_queue_name
  message_retention_seconds = 345600 # 4일 — 노드 0대 주말·Lambda(3.1.8) 연결 전에도 남는다
  receive_wait_time_seconds = 20     # 긴 폴링
  # Lambda 이벤트 소스는 "함수 제한 시간 × 6" 이상을 권장 → 알림 Lambda 제한 시간 30초 기준(3.1.8)
  visibility_timeout_seconds = 180
  sqs_managed_sse_enabled    = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.notification_dlq.arn
    maxReceiveCount     = 3 # conventions 5장
  })
}

# DLQ는 알림 큐에서 넘어온 메시지만 받는다
resource "aws_sqs_queue_redrive_allow_policy" "notification_dlq" {
  queue_url = aws_sqs_queue.notification_dlq.id
  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.notification.arn]
  })
}

# TLS가 아닌 요청 거부 (두 큐 모두)
data "aws_iam_policy_document" "sqs_tls_only" {
  for_each = {
    notification = aws_sqs_queue.notification.arn
    dlq          = aws_sqs_queue.notification_dlq.arn
  }

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["sqs:*"]
    resources = [each.value]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_sqs_queue_policy" "notification" {
  queue_url = aws_sqs_queue.notification.id
  policy    = data.aws_iam_policy_document.sqs_tls_only["notification"].json
}

resource "aws_sqs_queue_policy" "notification_dlq" {
  queue_url = aws_sqs_queue.notification_dlq.id
  policy    = data.aws_iam_policy_document.sqs_tls_only["dlq"].json
}

# ---- booking 파드 → 알림 큐 발행 (Pod Identity) ------------------------
module "booking_pod_identity" {
  source  = "terraform-aws-modules/eks-pod-identity/aws"
  version = "~> 2.0"

  name = "${var.name}-booking-pod" # cinema-staging-booking-pod (런타임 계약 2장)

  attach_custom_policy      = true
  custom_policy_description = "3.1.9 booking outbox relay: send to notification queue only"
  policy_statements = [
    {
      sid       = "SendNotification"
      actions   = ["sqs:SendMessage", "sqs:GetQueueUrl"]
      resources = [aws_sqs_queue.notification.arn]
    }
  ]

  associations = {
    this = {
      cluster_name    = module.eks.cluster_name
      namespace       = var.app_namespace
      service_account = "booking" # SeatSAC-gitops base/booking/serviceaccount.yaml
    }
  }
}

# ---- DLQ 경보 → SNS cinema-alerts(global) → 메일 -----------------------
#   DLQ에 하나라도 보이면 = 알림 Lambda가 같은 메시지를 3번 처리하지 못했다
#   SQS 지표는 큐가 활성(메시지 있음·최근 호출)일 때만 나온다 → 빈 DLQ는 데이터 없음 = 정상(notBreaching)
resource "aws_cloudwatch_metric_alarm" "notification_dlq" {
  alarm_name          = "${var.name}-notification-dlq-not-empty"
  alarm_description   = "알림 DLQ에 메시지 있음 — 알림 Lambda(3.1.8)가 3회 실패. Lambda 로그의 eventId로 원인 확인 후 DLQ 재전송(런북 6-B)"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = aws_sqs_queue.notification_dlq.name }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [data.aws_sns_topic.alerts.arn] # alerts.tf
  ok_actions          = [data.aws_sns_topic.alerts.arn]
}

# ------------------------------------------------------------------
# 6.1.3 서비스 간 메시징 — SNS(이벤트) + SQS(구독·명령·결과) + DLQ + 경보 + Pod Identity (ADR-117)
#   이름은 docs/conventions.md 5장:
#     이벤트 토픽  cinema-staging-{booking,catalog}-events          보내는 서비스 하나가 소유
#     구독 큐      cinema-staging-{user-booking,ai-catalog}-events  SNS → SQS, raw message delivery
#     명령 큐      cinema-staging-{coupon,payment}-commands         booking(saga) → coupon·payment
#     결과 큐      cinema-staging-booking-replies                   coupon·payment → booking
#     DLQ          {큐}-dlq, maxReceiveCount 3, 메시지 1건 이상이면 경보
#   - 받는 쪽은 서비스 파드(SQS long polling 20초). Lambda는 알림(sqs.tf)만
#   - 암호화: SSE-SQS(KMS 권한 없이 발행·수신). TLS가 아닌 요청은 큐·토픽 정책으로 거부
#   - 큐·토픽 주소는 이름으로 정해져 envs/staging-k8s의 ConfigMap cinema-infra-endpoints가 만든다(infra_endpoints.tf)
#   - 파드 권한은 Pod Identity cinema-staging-{svc}-pod — ServiceAccount {svc}(cinema-staging)는 gitops base/{svc}/serviceaccount.yaml
#     (booking은 이미 있다. payment·coupon·user·catalog·ai는 gitops PR에서 추가 — 그 전에는 연결만 있고 쓰는 파드가 없다)
#   - (여유 시) booking-catalog-events 구독 큐는 만들지 않았다 — booking 회차 사본(APP_SCREENING_LOOKUP_MODE=local)을 할 때 local.messaging_queues에 추가
# ------------------------------------------------------------------

locals {
  messaging_topics = toset(["booking-events", "catalog-events"])

  # retention: 명령·결과는 saga 단계 기한(초 단위)이 지나면 의미가 없어 1일, 이벤트는 복제본이 따라잡을 시간 4일
  # oldest_alarm: 받는 파드가 멈췄을 때 알림(초). ai-catalog-events는 ng-ai가 평소 0대라 쌓이는 것이 정상 → 경보 없음(null)
  messaging_queues = {
    "user-booking-events" = { topic = "booking-events", retention = 345600, oldest_alarm = 3600 }
    "ai-catalog-events"   = { topic = "catalog-events", retention = 345600, oldest_alarm = null }
    "coupon-commands"     = { topic = null, retention = 86400, oldest_alarm = 300 }
    "payment-commands"    = { topic = null, retention = 86400, oldest_alarm = 300 }
    "booking-replies"     = { topic = null, retention = 86400, oldest_alarm = 300 }
  }

  sqs_consume_actions = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility", "sqs:GetQueueAttributes", "sqs:GetQueueUrl"]
  sqs_send_actions    = ["sqs:SendMessage", "sqs:GetQueueUrl"]
}

# ---- SNS 이벤트 토픽 ----------------------------------------------------
resource "aws_sns_topic" "events" {
  for_each = local.messaging_topics
  name     = "${var.name}-${each.key}" # cinema-staging-booking-events
}

data "aws_iam_policy_document" "events_topic" {
  for_each = local.messaging_topics

  # 같은 계정의 발행·구독은 IAM 정책(Pod Identity·Terraform)으로 허용된다 — 여기서는 TLS가 아닌 발행만 막는다
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.events[each.key].arn]
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

resource "aws_sns_topic_policy" "events" {
  for_each = local.messaging_topics
  arn      = aws_sns_topic.events[each.key].arn
  policy   = data.aws_iam_policy_document.events_topic[each.key].json
}

# ---- SQS 큐 · DLQ -------------------------------------------------------
resource "aws_sqs_queue" "messaging_dlq" {
  for_each = local.messaging_queues

  name                      = "${var.name}-${each.key}-dlq"
  message_retention_seconds = 1209600 # 14일(최대) — 표준 큐는 DLQ로 옮겨도 처음 넣은 시각을 유지하므로 원본보다 길게
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "messaging" {
  for_each = local.messaging_queues

  name                      = "${var.name}-${each.key}" # cinema-staging-coupon-commands
  message_retention_seconds = each.value.retention
  receive_wait_time_seconds = 20 # 긴 폴링 (conventions 5장)
  # 파드가 받아 처리하는 시간 상한: payment가 포트원을 부르는 확인이 최대 약 10초 → 30초 안에 지우지 못하면 다시 보인다
  visibility_timeout_seconds = 30
  sqs_managed_sse_enabled    = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.messaging_dlq[each.key].arn
    maxReceiveCount     = 3
  })
}

resource "aws_sqs_queue_redrive_allow_policy" "messaging_dlq" {
  for_each = local.messaging_queues

  queue_url = aws_sqs_queue.messaging_dlq[each.key].id
  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.messaging[each.key].arn]
  })
}

data "aws_iam_policy_document" "messaging_queue" {
  for_each = local.messaging_queues

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["sqs:*"]
    resources = [aws_sqs_queue.messaging[each.key].arn]
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

  # 구독 큐만: 자기 토픽에서 온 메시지만 받는다
  dynamic "statement" {
    for_each = each.value.topic == null ? [] : [each.value.topic]
    content {
      sid       = "AllowTopicDelivery"
      effect    = "Allow"
      actions   = ["sqs:SendMessage"]
      resources = [aws_sqs_queue.messaging[each.key].arn]
      principals {
        type        = "Service"
        identifiers = ["sns.amazonaws.com"]
      }
      condition {
        test     = "ArnEquals"
        variable = "aws:SourceArn"
        values   = [aws_sns_topic.events[statement.value].arn]
      }
    }
  }
}

resource "aws_sqs_queue_policy" "messaging" {
  for_each  = local.messaging_queues
  queue_url = aws_sqs_queue.messaging[each.key].id
  policy    = data.aws_iam_policy_document.messaging_queue[each.key].json
}

data "aws_iam_policy_document" "messaging_dlq" {
  for_each = local.messaging_queues

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["sqs:*"]
    resources = [aws_sqs_queue.messaging_dlq[each.key].arn]
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

resource "aws_sqs_queue_policy" "messaging_dlq" {
  for_each  = local.messaging_queues
  queue_url = aws_sqs_queue.messaging_dlq[each.key].id
  policy    = data.aws_iam_policy_document.messaging_dlq[each.key].json
}

# ---- SNS → SQS 구독 (raw: SNS 봉투 없이 본문·메시지 속성 그대로) ---------
resource "aws_sns_topic_subscription" "events" {
  for_each = { for k, q in local.messaging_queues : k => q if q.topic != null }

  topic_arn            = aws_sns_topic.events[each.value.topic].arn
  protocol             = "sqs"
  endpoint             = aws_sqs_queue.messaging[each.key].arn
  raw_message_delivery = true

  depends_on = [aws_sqs_queue_policy.messaging] # 큐 정책이 먼저 있어야 전달된다
}

# ---- Pod Identity (booking은 sqs.tf의 module.booking_pod_identity에 권한을 더한다) ----
locals {
  messaging_pod_roles = {
    payment = [
      { sid = "ReceiveCommands", actions = local.sqs_consume_actions, resources = [aws_sqs_queue.messaging["payment-commands"].arn] },
      { sid = "SendReplies", actions = local.sqs_send_actions, resources = [aws_sqs_queue.messaging["booking-replies"].arn] },
    ]
    coupon = [
      { sid = "ReceiveCommands", actions = local.sqs_consume_actions, resources = [aws_sqs_queue.messaging["coupon-commands"].arn] },
      { sid = "SendReplies", actions = local.sqs_send_actions, resources = [aws_sqs_queue.messaging["booking-replies"].arn] },
    ]
    user = [
      { sid = "ReceiveBookingEvents", actions = local.sqs_consume_actions, resources = [aws_sqs_queue.messaging["user-booking-events"].arn] },
    ]
    catalog = [
      { sid = "PublishCatalogEvents", actions = ["sns:Publish"], resources = [aws_sns_topic.events["catalog-events"].arn] },
    ]
    ai = [
      { sid = "ReceiveCatalogEvents", actions = local.sqs_consume_actions, resources = [aws_sqs_queue.messaging["ai-catalog-events"].arn] },
    ]
  }

  # booking: 명령 발송 · 결과 수신 · 이벤트 발행 (sqs.tf)
  booking_messaging_statements = [
    { sid = "SendSagaCommands", actions = local.sqs_send_actions, resources = [aws_sqs_queue.messaging["coupon-commands"].arn, aws_sqs_queue.messaging["payment-commands"].arn] },
    { sid = "ReceiveSagaReplies", actions = local.sqs_consume_actions, resources = [aws_sqs_queue.messaging["booking-replies"].arn] },
    { sid = "PublishBookingEvents", actions = ["sns:Publish"], resources = [aws_sns_topic.events["booking-events"].arn] },
  ]
}

module "messaging_pod_identity" {
  source  = "terraform-aws-modules/eks-pod-identity/aws"
  version = "~> 2.0"

  for_each = local.messaging_pod_roles

  name            = "${var.name}-${each.key}-pod" # cinema-staging-payment-pod (conventions 9장)
  use_name_prefix = false

  attach_custom_policy      = true
  custom_policy_description = "6.1.3 ${each.key} messaging (ADR-117): own queues and topic only"
  policy_statements         = each.value

  associations = {
    this = {
      cluster_name    = module.eks.cluster_name
      namespace       = var.app_namespace
      service_account = each.key # gitops base/{svc}/serviceaccount.yaml
    }
  }
}

# ---- 경보 → SNS cinema-alerts(global) → 메일 ----------------------------
#   DLQ에 하나라도 보이면 = 같은 메시지를 3번 받았는데 지우지 못했다(처리 실패·30초 초과)
resource "aws_cloudwatch_metric_alarm" "messaging_dlq" {
  for_each = local.messaging_queues

  alarm_name          = "${var.name}-${each.key}-dlq-not-empty"
  alarm_description   = "${each.key} DLQ에 메시지 있음 — 받는 서비스가 같은 메시지를 3번 처리하지 못함. 서비스 로그의 messageType·correlationId로 원인 확인(런북 6-B-6)"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = aws_sqs_queue.messaging_dlq[each.key].name }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [data.aws_sns_topic.alerts.arn]
  ok_actions          = [data.aws_sns_topic.alerts.arn]
}

#   받는 파드가 멈춤: 가장 오래된 메시지가 기준을 넘음 (DLQ 경보로는 잡히지 않는다 — 아무도 받지 않으면 수신 횟수가 늘지 않는다)
resource "aws_cloudwatch_metric_alarm" "messaging_oldest_age" {
  for_each = { for k, q in local.messaging_queues : k => q if q.oldest_alarm != null }

  alarm_name          = "${var.name}-${each.key}-oldest"
  alarm_description   = "${each.key} 큐에 ${each.value.oldest_alarm}초 넘은 메시지 — 받는 서비스 파드가 없거나 멈춤(정지 시연 중이면 정상, 런북 6-B-6)"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateAgeOfOldestMessage"
  dimensions          = { QueueName = aws_sqs_queue.messaging[each.key].name }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = each.value.oldest_alarm
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [data.aws_sns_topic.alerts.arn]
  ok_actions          = [data.aws_sns_topic.alerts.arn]
}

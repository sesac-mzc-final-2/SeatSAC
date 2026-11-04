# ------------------------------------------------------------------
# 3.1.8 예매 완료 알림 Lambda (OPS-07)
#   - 이름(conventions 9장): 함수·역할 cinema-staging-notification. 코드는 lambdas/notification
#   - 큐·DLQ·경보는 3.1.9(sqs.tf). 여기서는 받는 쪽만 만든다: 함수 · 실행 역할 · 로그 그룹 · 이벤트 소스 매핑
#   - 하는 일은 로그뿐이다(모의 발송). VPC 밖, DB·외부 호출 없음 → 보안그룹·NAT·Secret이 필요 없다
#   - 배포: 3.1.16 Pre sign-up Lambda와 같은 방식 — Terraform이 코드를 zip으로 묶어 올린다(apply는 cinema-admin 로컬).
#     코드(handler.py)가 바뀌면 plan에 함수 1 update가 보인다. 테스트 파일은 zip에 넣지 않는다
#   - 실패: 메시지 단위 보고(ReportBatchItemFailures) → 깨진 메시지만 다시 수신, 3회째 실패하면 DLQ(sqs.tf) → DLQ 경보
#   - 릴레이(APP_OUTBOX_RELAY_ENABLED)는 이 매핑이 Enabled가 된 뒤에 켠다(런북 6-B-3·6-B-5)
# ------------------------------------------------------------------

data "archive_file" "notification" {
  type        = "zip"
  source_file = "${path.module}/../../../../lambdas/notification/handler.py"
  output_path = "${path.module}/.build/notification.zip" # .gitignore(infra/terraform/**/.build/)
}

resource "aws_iam_role" "notification" {
  name               = "${var.name}-notification" # cinema-staging-notification
  description        = "3.1.8 notification Lambda: receive from the notification queue, CloudWatch Logs only"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json # cognito.tf
}

# 로그 그룹을 미리 만들어 보존 기간을 정한다(Lambda가 만들면 무기한) → 쓰기 권한도 이 그룹으로 좁힌다
resource "aws_cloudwatch_log_group" "notification" {
  name              = "/aws/lambda/${var.name}-notification"
  retention_in_days = 7
}

data "aws_iam_policy_document" "notification" {
  statement {
    sid       = "WriteLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.notification.arn}:*"]
  }

  # 이벤트 소스 매핑이 이 역할로 큐를 읽는다. 알림 큐 하나만 (DLQ로 옮기는 일은 SQS가 한다)
  # 큐 암호화는 SSE-SQS라 KMS 권한이 필요 없다
  statement {
    sid       = "ConsumeNotificationQueue"
    actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"]
    resources = [aws_sqs_queue.notification.arn]
  }
}

resource "aws_iam_role_policy" "notification" {
  name   = "logs-and-queue"
  role   = aws_iam_role.notification.id
  policy = data.aws_iam_policy_document.notification.json
}

resource "aws_lambda_function" "notification" {
  function_name    = "${var.name}-notification" # cinema-staging-notification
  description      = "3.1.8 booking.confirmed from SQS: log a mock notification (no DB, no VPC)"
  role             = aws_iam_role.notification.arn
  runtime          = "python3.12" # lambdas/README.md
  handler          = "handler.handler"
  filename         = data.archive_file.notification.output_path
  source_code_hash = data.archive_file.notification.output_base64sha256
  timeout          = 30 # sqs.tf 가시성 제한 180초 = 30초 × 6 (이벤트 소스 권장값). 바꾸면 큐 쪽도 같이
  memory_size      = 128
  architectures    = ["arm64"]

  # 로그 형식은 Text(기본)로 둔다 — 핸들러가 JSON 한 줄을 직접 찍는다(JSON 형식이면 한 번 더 감싸져 필드로 읽히지 않는다)
  logging_config {
    log_format = "Text"
    log_group  = aws_cloudwatch_log_group.notification.name
  }

  depends_on = [aws_iam_role_policy.notification]
}

resource "aws_lambda_event_source_mapping" "notification" {
  event_source_arn = aws_sqs_queue.notification.arn
  function_name    = aws_lambda_function.notification.arn
  enabled          = true

  batch_size                         = 10 # lambdas/README.md
  maximum_batching_window_in_seconds = 0  # 도착 즉시 처리 — 데모에서 확정 직후 로그가 바로 보이게
  function_response_types            = ["ReportBatchItemFailures"]

  # 동시 실행 상한 2(허용 최솟값). 계정 기본 동시 실행 한도가 작아도 다른 함수(Pre sign-up 등)를 밀어내지 않는다.
  # 하는 일이 로그 한 줄이라 확정이 몰려도 2개로 충분하다
  scaling_config {
    maximum_concurrency = 2
  }

  # 매핑을 만들 때 AWS가 역할의 큐 읽기 권한을 확인한다 → 정책이 먼저 붙어 있어야 한다
  depends_on = [aws_iam_role_policy.notification]
}

output "notification_lambda_name" {
  description = "알림 Lambda(3.1.8). 로그 그룹은 /aws/lambda/<이름>"
  value       = aws_lambda_function.notification.function_name
}

output "notification_event_source_mapping_id" {
  description = "알림 큐 → Lambda 이벤트 소스 매핑 UUID (aws lambda get-event-source-mapping --uuid)"
  value       = aws_lambda_event_source_mapping.notification.uuid
}

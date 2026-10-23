# ------------------------------------------------------------------
# 3.1.3 운영 경보 공통 SNS 주제 cinema-alerts → 팀 메일
#   - staging의 CloudWatch 경보(RDS·Valkey, alerts.tf)가 이 주제로 보낸다 (data "aws_sns_topic")
#   - 메일은 Budgets와 같은 budget_emails(budget.auto.tfvars, 커밋 안 됨)
#   - 각 주소로 오는 "AWS Notification - Subscription Confirmation" 메일에서 Confirm을 눌러야 받는다
#   - global에 두는 이유: 메일 주소가 staging plan(공개 PR 코멘트)에 나오지 않게, staging을 지워도 구독 유지
# ------------------------------------------------------------------

resource "aws_sns_topic" "alerts" {
  name = "cinema-alerts"
}

resource "aws_sns_topic_subscription" "alerts_email" {
  count     = length(nonsensitive(var.budget_emails)) # 개수만 공개, 주소는 plan에 (sensitive)로
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.budget_emails[count.index]
}

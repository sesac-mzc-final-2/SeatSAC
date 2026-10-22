# ------------------------------------------------------------------
# 3.1.14 비용 경보 (AWS Budgets) — 계정 전체 비용 기준
#   월 예산: 실제 50·80·100% + 월말 예상 100% 초과 시 메일
#   일 예산: 하루 실제 비용이 한도를 넘으면 메일 (노드 끄기 실패·부하 테스트 후 방치 감지)
#   ※ 비용 데이터는 하루 몇 번만 갱신된다 → 경보는 반나절가량 늦을 수 있다. 즉시 차단 장치가 아니다
#   ※ 받는 메일 주소는 커밋하지 않는다: budget.auto.tfvars(.gitignore의 *.auto.tfvars) 또는 TF_VAR_budget_emails
# ------------------------------------------------------------------

resource "aws_budgets_budget" "monthly" {
  name         = "cinema-monthly"
  budget_type  = "COST"
  limit_amount = var.budget_monthly_usd
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  dynamic "notification" {
    for_each = [50, 80, 100]
    content {
      comparison_operator        = "GREATER_THAN"
      threshold                  = notification.value
      threshold_type             = "PERCENTAGE"
      notification_type          = "ACTUAL"
      subscriber_email_addresses = var.budget_emails
    }
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = var.budget_emails
  }
}

resource "aws_budgets_budget" "daily" {
  name         = "cinema-daily"
  budget_type  = "COST"
  limit_amount = var.budget_daily_usd
  limit_unit   = "USD"
  time_unit    = "DAILY"

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = var.budget_emails
  }
}

# ------------------------------------------------------------------
# 6.1.8 WAF — CloudFront 웹 ACL cinema-staging-waf (ADR-124 ①, conventions 9장)
#   - CloudFront용 웹 ACL은 scope CLOUDFRONT이고 us-east-1에만 만들 수 있다 → provider aws.us_east_1(providers.tf)
#   - 규칙: AWS 관리형 3종(Common·KnownBadInputs·SQLi) + IP당 요청 제한(5분 2,000 = 분당 약 400)
#   - var.waf_mode: off(기본 — 웹 ACL 없음, CloudFront 그대로) → count(전부 세기만, 차단 없음) → block(차단)
#     처음에는 count로 하루 이상 두고 CloudWatch 지표(AWS/WAFV2)·샘플 요청으로 오탐을 본 뒤 block (런북 6-M)
#   - 예외(count로 둔 규칙):
#       SizeRestrictions_BODY — 관리형 규칙은 본문 8KB 초과를 막는데, gateway가 64KB까지 받는다(413 PAYLOAD_TOO_LARGE, ADR-124 ②).
#         큰 본문은 gateway가 막으므로 WAF는 세기만 한다
#   - 요청 제한은 CloudFront가 본 사용자 IP 기준. 클러스터 안 k6·분석 Job은 gateway·preview로 바로 가서(CloudFront 밖) 세지 않는다.
#     ai의 공개 API 호출은 NAT IP 2개로 나간다 — 질문당 최대 8회 + 스냅샷 분당 약 9회라 2,000/5분보다 훨씬 작다
#   - 비용: 웹 ACL $5/월 + 규칙 4 × $1/월 + 요청 100만 건당 $0.60 (관리형 3종은 무료 규칙 그룹)
#   - 차단 로그(WAF 로깅)는 이번에 두지 않는다 — 로깅 대상 버킷 이름이 aws-waf-logs-*여야 해 리포트 버킷을 쓸 수 없다. 지표·샘플 요청으로 본다
# ------------------------------------------------------------------
locals {
  waf_enabled = var.waf_mode != "off"
  waf_count   = var.waf_mode == "count" # true면 관리형 규칙·요청 제한 모두 세기만

  waf_managed_rules = [
    { name = "AWSManagedRulesCommonRuleSet", priority = 10, count_rules = ["SizeRestrictions_BODY"] },
    { name = "AWSManagedRulesKnownBadInputsRuleSet", priority = 20, count_rules = [] },
    { name = "AWSManagedRulesSQLiRuleSet", priority = 30, count_rules = [] },
  ]
}

resource "aws_wafv2_web_acl" "cloudfront" {
  count    = local.waf_enabled ? 1 : 0
  provider = aws.us_east_1

  name        = "${var.name}-waf" # cinema-staging-waf
  description = "6.1.8 CloudFront: AWS managed rules + per-IP rate limit (ADR-124)"
  scope       = "CLOUDFRONT"

  default_action {
    allow {}
  }

  dynamic "rule" {
    for_each = local.waf_managed_rules
    content {
      name     = rule.value.name
      priority = rule.value.priority

      # count 모드면 규칙 그룹 전체를 세기만(none = 규칙 그룹의 원래 동작 — 차단)
      override_action {
        dynamic "count" {
          for_each = local.waf_count ? [1] : []
          content {}
        }
        dynamic "none" {
          for_each = local.waf_count ? [] : [1]
          content {}
        }
      }

      statement {
        managed_rule_group_statement {
          vendor_name = "AWS"
          name        = rule.value.name

          dynamic "rule_action_override" {
            for_each = rule.value.count_rules
            content {
              name = rule_action_override.value
              action_to_use {
                count {}
              }
            }
          }
        }
      }

      visibility_config {
        cloudwatch_metrics_enabled = true
        metric_name                = "${var.name}-waf-${rule.value.name}"
        sampled_requests_enabled   = true
      }
    }
  }

  rule {
    name     = "rate-limit-per-ip"
    priority = 40

    action {
      dynamic "count" {
        for_each = local.waf_count ? [1] : []
        content {}
      }
      dynamic "block" {
        for_each = local.waf_count ? [] : [1]
        content {}
      }
    }

    statement {
      rate_based_statement {
        limit                 = var.waf_rate_limit_per_5min
        evaluation_window_sec = 300
        aggregate_key_type    = "IP"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name}-waf-rate-limit-per-ip"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${var.name}-waf"
    sampled_requests_enabled   = true
  }

  tags = { Name = "${var.name}-waf" }
}

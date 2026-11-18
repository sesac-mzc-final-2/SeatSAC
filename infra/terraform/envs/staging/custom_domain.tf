# ------------------------------------------------------------------
# 3.1.23 사용자 도메인 (선택) — CloudFront 기본 도메인은 그대로 두고 도메인 하나를 더한다
#   var.custom_domain 이 비어 있으면 아무것도 만들지 않는다(plan 변경 없음)
#
#   사용자 ─HTTPS─▶ {custom_domain} ─(Route 53 별칭 A·AAAA)─▶ CloudFront ─▶ S3 · /api → ALB (기존 그대로)
#
#   - 도메인 등록은 Terraform 밖: 등록은 구매(1년 선불, 환불 없음)이고 등록자 연락처(개인정보)가 필요해
#     코드·tfvars에 둘 수 없다 → Route 53 콘솔에서 사람이 등록(런북 7-7, AWS 콘솔 리소스 생성 금지의 예외)
#   - 등록하면 Route 53이 퍼블릭 호스팅 영역을 자동으로 만든다 → 여기서는 data로 찾기만 한다(영역은 관리하지 않음)
#   - 인증서: ACM 공개 인증서(무료), CloudFront용이라 us-east-1, DNS 검증 레코드를 같은 영역에 넣는다
#   - CNAME이 아니라 별칭 A·AAAA: 루트 도메인(apex)은 CNAME을 둘 수 없고, CloudFront 별칭 조회는 요금이 없다
#   - CloudFront에 aliases로 등록해야 한다 — 등록 없이 DNS만 가리키면 CloudFront가 그 이름을 받지 않는다(403)
# ------------------------------------------------------------------
locals {
  custom_domain_enabled = var.custom_domain != ""
}

data "aws_route53_zone" "custom" {
  count        = local.custom_domain_enabled ? 1 : 0
  name         = var.custom_domain
  private_zone = false
}

resource "aws_acm_certificate" "custom" {
  count             = local.custom_domain_enabled ? 1 : 0
  provider          = aws.us_east_1
  domain_name       = var.custom_domain
  validation_method = "DNS"

  tags = { Name = "${var.name}-custom-domain" }

  lifecycle {
    create_before_destroy = true # 도메인을 바꿀 때 CloudFront가 인증서 없이 남지 않게
  }
}

resource "aws_route53_record" "custom_validation" {
  for_each = local.custom_domain_enabled ? {
    for o in aws_acm_certificate.custom[0].domain_validation_options : o.domain_name => o
  } : {}

  zone_id         = data.aws_route53_zone.custom[0].zone_id
  name            = each.value.resource_record_name
  type            = each.value.resource_record_type
  records         = [each.value.resource_record_value]
  ttl             = 60
  allow_overwrite = true
}

# 인증서가 발급(검증 완료)될 때까지 기다린다 — 보통 수 분. 그 전에는 CloudFront에 붙일 수 없다
resource "aws_acm_certificate_validation" "custom" {
  count                   = local.custom_domain_enabled ? 1 : 0
  provider                = aws.us_east_1
  certificate_arn         = aws_acm_certificate.custom[0].arn
  validation_record_fqdns = [for r in aws_route53_record.custom_validation : r.fqdn]
}

resource "aws_route53_record" "custom_alias" {
  for_each = local.custom_domain_enabled ? toset(["A", "AAAA"]) : toset([])

  zone_id = data.aws_route53_zone.custom[0].zone_id
  name    = var.custom_domain
  type    = each.key

  alias {
    name                   = aws_cloudfront_distribution.frontend.domain_name
    zone_id                = aws_cloudfront_distribution.frontend.hosted_zone_id
    evaluate_target_health = false
  }
}

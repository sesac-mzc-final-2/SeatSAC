# ------------------------------------------------------------------
# 3.1.23 사용자 도메인 (선택) — CloudFront 기본 도메인은 그대로 두고 도메인 하나를 더한다
#   var.custom_domain 이 비어 있으면 아무것도 만들지 않는다(plan 변경 없음)
#
#   사용자 ─HTTPS─▶ {custom_domain} ─(Route 53 별칭 A·AAAA)─▶ CloudFront ─▶ S3 · /api → ALB (기존 그대로)
#
#   - 도메인 등록은 Terraform 밖: 등록은 구매(1년 선불, 환불 없음)이고 등록자 연락처(개인정보)가 필요해
#     코드·tfvars에 둘 수 없다 → Route 53 콘솔에서 사람이 등록(런북 7-7, AWS 콘솔 리소스 생성 금지의 예외)
#   - 등록하면 Route 53이 퍼블릭 호스팅 영역을 자동으로 만든다 → 보통은 data로 찾기만 한다(영역은 관리하지 않음)
#   - ADR-125 다른 계정으로 옮길 때: 등록 이전은 영역을 옮기지 않는다 → var.custom_domain_hosted_zone으로 새 계정의
#     영역을 Terraform이 만든다(콘솔·CLI로 만들지 않는다). 그 영역의 네임서버(출력 custom_domain_name_servers)로
#     등록 도메인의 네임서버를 바꾼 뒤 custom_domain을 켠다(런북 rebuild-new-account 7장)
#   - 인증서: ACM 공개 인증서(무료), CloudFront용이라 us-east-1, DNS 검증 레코드를 같은 영역에 넣는다
#   - CNAME이 아니라 별칭 A·AAAA: 루트 도메인(apex)은 CNAME을 둘 수 없고, CloudFront 별칭 조회는 요금이 없다
#   - CloudFront에 aliases로 등록해야 한다 — 등록 없이 DNS만 가리키면 CloudFront가 그 이름을 받지 않는다(403)
# ------------------------------------------------------------------
locals {
  custom_domain_enabled = var.custom_domain != ""

  # 이 루트 모듈이 만든 영역(custom_domain_hosted_zone)에 custom_domain이 들어가면 그 영역을 쓴다(data 조회 없음).
  # custom_domain_zone_id를 주면 그것이 먼저다
  custom_zone_managed = local.custom_domain_enabled && var.custom_domain_zone_id == "" && var.custom_domain_hosted_zone != "" && (
    var.custom_domain == var.custom_domain_hosted_zone || endswith(var.custom_domain, ".${var.custom_domain_hosted_zone}")
  )
  custom_zone_id = local.custom_zone_managed ? aws_route53_zone.managed[0].zone_id : (
    local.custom_domain_enabled ? data.aws_route53_zone.custom[0].zone_id : null
  )
}

# ADR-125 새 계정 이전용 퍼블릭 호스팅 영역 — 비우면(기본) 만들지 않는다(지금 계정은 등록 때 자동 생성된 영역을 data로 쓴다).
#   custom_domain보다 먼저(custom_domain = "" 그대로) 이것만 apply → 출력 네임서버로 등록 도메인 네임서버 교체 → 퍼진 뒤 custom_domain
#   force_destroy = false: Terraform 밖 레코드(메일 등)가 남아 있으면 지우지 않고 멈춘다
resource "aws_route53_zone" "managed" {
  count         = var.custom_domain_hosted_zone != "" ? 1 : 0
  name          = var.custom_domain_hosted_zone
  comment       = "SeatSAC custom domain (ADR-125, Terraform)"
  force_destroy = false

  tags = { Name = "${var.name}-custom-domain-zone" }
}

# 영역은 보통 이름으로 찾는다. 같은 이름의 퍼블릭 영역이 둘 이상이면(예전에 만든 영역 + 등록 때 자동 생성)
# 이름 조회가 실패하므로 var.custom_domain_zone_id로 등록 도메인의 네임서버와 맞는 영역을 지정한다
data "aws_route53_zone" "custom" {
  count        = local.custom_domain_enabled && !local.custom_zone_managed ? 1 : 0
  zone_id      = var.custom_domain_zone_id != "" ? var.custom_domain_zone_id : null
  name         = var.custom_domain_zone_id != "" ? null : var.custom_domain
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

  zone_id         = local.custom_zone_id
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

# DNS는 Cognito 콜백·로그아웃 URL 등록이 끝난 뒤에 공개한다 — 먼저 열리면 새 주소에서 로그인이 redirect_mismatch
resource "aws_route53_record" "custom_alias" {
  for_each   = local.custom_domain_enabled ? toset(["A", "AAAA"]) : toset([])
  depends_on = [aws_cognito_user_pool_client.web]

  zone_id = local.custom_zone_id
  name    = var.custom_domain
  type    = each.key

  alias {
    name                   = aws_cloudfront_distribution.frontend.domain_name
    zone_id                = aws_cloudfront_distribution.frontend.hosted_zone_id
    evaluate_target_health = false
  }
}

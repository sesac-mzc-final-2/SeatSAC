# ------------------------------------------------------------------
# 3.1.23 후속 — CloudFront → ALB 구간 HTTPS (선택, 발표 뒤 적용)
#   var.api_origin_https = "off"(기본)이면 아무것도 만들지 않는다(plan 변경 없음)
#
#   사용자 ─HTTPS─▶ CloudFront ─HTTPS(443)─▶ origin.{custom_domain} ─(Route 53 별칭 A)─▶ ALB ─HTTP─▶ gateway 파드
#
#   - CloudFront는 원본 인증서를 공개 CA·이름으로 검증한다. ACM은 *.elb.amazonaws.com 에 발급하지 않으므로
#     ALB에 우리 도메인 이름(origin.{custom_domain})을 붙이고 그 이름으로 인증서를 받는다
#   - 인증서는 ALB용이라 ap-northeast-2(기본 provider). CloudFront 뷰어 인증서(us-east-1, custom_domain.tf)와 별개
#   - ALB 443 리스너는 gitops base/gateway/ingress.yaml 이 만든다(listen-ports + spec.tls.hosts).
#     LB Controller가 spec.tls.hosts 이름으로 ACM 인증서를 찾아 붙인다 → gitops(Public)에 인증서 ARN(계정 ID)을 쓰지 않는다
#   - 세 단계로 나눠 끊김 없이 바꾼다(확장 → 전환 → 정리, 런북 7-8):
#       prepare : 인증서 · DNS(origin.) · ALB SG 80 → 80~443(규칙 하나, security_groups.tf) — CloudFront는 아직 HTTP(80). 이 뒤에 gitops PR로 443 리스너 추가
#       on      : CloudFront 원본을 origin.{custom_domain} · https-only 로. 443 리스너가 없거나 다른 인증서면 plan이 실패한다
#     80 정리(리스너 삭제 · SG 규칙을 443만으로)는 전환을 확인한 뒤 따로 한다 — CloudFront 배포가 엣지에 퍼지는 동안 80이 닫히면 /api가 끊긴다
# ------------------------------------------------------------------
locals {
  api_origin_https_prepare = var.api_origin_https != "off"
  api_origin_https_on      = var.api_origin_https == "on"
  api_origin_https_name    = "origin.${var.custom_domain}"
}

resource "aws_acm_certificate" "origin" {
  count             = local.api_origin_https_prepare ? 1 : 0
  domain_name       = local.api_origin_https_name
  validation_method = "DNS"

  tags = { Name = "${var.name}-origin" }

  lifecycle {
    create_before_destroy = true # 이름을 바꿀 때 리스너가 인증서 없이 남지 않게
  }
}

resource "aws_route53_record" "origin_validation" {
  for_each = local.api_origin_https_prepare ? {
    for o in aws_acm_certificate.origin[0].domain_validation_options : o.domain_name => o
  } : {}

  zone_id         = local.custom_zone_id
  name            = each.value.resource_record_name
  type            = each.value.resource_record_type
  records         = [each.value.resource_record_value]
  ttl             = 60
  allow_overwrite = true
}

# 발급(검증 완료)될 때까지 기다린다 — 발급 전 인증서는 LB Controller가 찾지 못한다
resource "aws_acm_certificate_validation" "origin" {
  count                   = local.api_origin_https_prepare ? 1 : 0
  certificate_arn         = aws_acm_certificate.origin[0].arn
  validation_record_fqdns = [for r in aws_route53_record.origin_validation : r.fqdn]
}

# origin.{custom_domain} → gateway ALB. ALB는 IPv4만(internet-facing 기본)이라 A만 둔다
# ALB를 지웠다 다시 만들면(Ingress 재생성) DNS 이름이 바뀐다 → apply를 한 번 더 해 이 레코드를 맞춘다
resource "aws_route53_record" "origin_alias" {
  count = local.api_origin_https_prepare ? 1 : 0

  zone_id = local.custom_zone_id
  name    = local.api_origin_https_name
  type    = "A"

  alias {
    name                   = one(data.aws_lb.gateway[*].dns_name)
    zone_id                = one(data.aws_lb.gateway[*].zone_id)
    evaluate_target_health = false
  }

  lifecycle {
    precondition {
      condition     = length(data.aws_lb.gateway) == 1
      error_message = "api_origin_https는 태그로 찾은 gateway ALB가 있어야 한다 — Ingress(gitops base/gateway)가 떠 있는지 확인하고 api_origin_domain_name은 비워 둔다."
    }
  }
}

# 전환 전 확인: ALB에 443 리스너가 있고 위 인증서를 쓰는지. 없으면(gitops PR 전) 여기서 plan이 실패한다
data "aws_lb_listener" "gateway_https" {
  count             = local.api_origin_https_on ? 1 : 0
  load_balancer_arn = one(data.aws_lb.gateway[*].arn)
  port              = 443

  lifecycle {
    postcondition {
      condition     = self.certificate_arn == aws_acm_certificate.origin[0].arn
      error_message = "ALB 443 리스너의 인증서가 origin 인증서와 다르다 — gitops Ingress spec.tls.hosts가 origin.{custom_domain}인지 확인한다(런북 7-8)."
    }
  }
}

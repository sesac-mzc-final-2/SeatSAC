# ------------------------------------------------------------------
# 3.1.11 CloudFront · S3(OAC) · 프런트 배포 (OPS-12, ADR-9·31)
#   사용자 ─HTTPS─▶ CloudFront ─┬─ /*                              ▶ S3 (React 빌드, OAC, 공개 접근 차단)
#                                ├─ /assets/*                       ▶ S3 — 1년 캐시(Vite 해시 파일명)
#                                ├─ /api/screenings/*/seats/stream  ▶ ALB — 캐시·압축 끔 (SSE 세트 ③)
#                                └─ /api/*                          ▶ ALB(gateway, HTTP 80) — 캐시 없음
#   - ALB는 gateway Ingress(gitops 3.1.5)가 만들고 LB Controller가 태그를 붙인다 → 태그로 찾는다.
#     아직 없으면 /api/* 동작 없이 S3만 배포하고, Ingress가 생긴 뒤 apply를 한 번 더 한다(런북 7장).
#   - 캐시 정책 3종: static-assets(1년) · spa-shell(index.html, 원본 Cache-Control 따름) · 관리형 CachingDisabled(/api)
#   - CloudFront Function: 확장자 없는 경로 → /index.html (cloudfront/spa-rewrite.js)
#   - 기본 도메인(*.cloudfront.net)·기본 인증서. 사용자 지정 도메인·CORS 없음(같은 도메인 /api)
#   - 업로드·무효화는 frontend-deploy 워크플로(cinema-gha-frontend-deploy)만. 사람이 S3에 직접 올리지 않는다
#   - 접근 로그는 켜지 않는다(비용·범위). 필요하면 standard logging v2 → S3
# ------------------------------------------------------------------

# ---- S3 (비공개, OAC로만 읽음) ---------------------------------------
resource "aws_s3_bucket" "frontend" {
  bucket = "${var.name}-frontend-${local.account_id}" # 03a 이름 규칙. global의 frontend-deploy 역할 정책과 같은 이름

  tags = { Name = "${var.name}-frontend" }
}

resource "aws_s3_bucket_public_access_block" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  rule {
    object_ownership = "BucketOwnerEnforced" # ACL 비활성. CI 역할이 올린 객체도 버킷 소유
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256" # 공개 정적 파일이라 SSE-S3 (OAC는 SSE-KMS도 되지만 키 비용·정책이 늘어난다)
    }
  }
}

# CloudFront(이 배포 하나)만 객체 읽기
data "aws_iam_policy_document" "frontend_bucket" {
  statement {
    sid       = "AllowCloudFrontOAC"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.frontend.arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.frontend.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "frontend" {
  bucket = aws_s3_bucket.frontend.id
  policy = data.aws_iam_policy_document.frontend_bucket.json

  depends_on = [aws_s3_bucket_public_access_block.frontend] # 차단 설정이 먼저
}

resource "aws_cloudfront_origin_access_control" "frontend" {
  name                              = "${var.name}-frontend"
  description                       = "S3 frontend bucket (SigV4, always sign)"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# ---- /api 원본: gateway ALB 찾기 ---------------------------------------
# LB Controller가 ALB에 붙이는 태그: elbv2.k8s.aws/cluster=<클러스터>, ingress.k8s.aws/stack=<ns>/<Ingress 이름>
# 클러스터 이름은 module.eks 출력이 아니라 문자열로 만든다(첫 plan에서 EKS가 unknown이면 count를 정할 수 없다)
data "aws_lbs" "gateway" {
  tags = {
    "elbv2.k8s.aws/cluster" = "${var.name}-eks"
    "ingress.k8s.aws/stack" = var.gateway_ingress_stack
  }
}

locals {
  gateway_alb_arns   = tolist(data.aws_lbs.gateway.arns)
  api_origin_enabled = var.api_origin_domain_name != "" || length(local.gateway_alb_arns) > 0
}

data "aws_lb" "gateway" {
  count = var.api_origin_domain_name == "" && length(local.gateway_alb_arns) > 0 ? 1 : 0
  arn   = local.gateway_alb_arns[0]
}

locals {
  api_origin_domain_name = (var.api_origin_domain_name != ""
    ? var.api_origin_domain_name
  : try(data.aws_lb.gateway[0].dns_name, ""))

  # 순서 = 우선순위. SSE 경로가 /api/* 보다 먼저 와야 한다
  api_behaviors = [
    {
      path_pattern    = "/api/screenings/*/seats/stream" # BKG-05 SSE: 캐시 없음 + 압축 끔(압축하면 이벤트가 버퍼링돼 몰려서 온다)
      allowed_methods = ["GET", "HEAD"]
      compress        = false
    },
    {
      path_pattern    = "/api/*"
      allowed_methods = ["DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"]
      compress        = true
    },
  ]
}

# ---- 캐시 정책 3종 ------------------------------------------------------
# 1) /assets/*: Vite 해시 파일명 → 내용이 바뀌면 이름도 바뀐다. 1년, 캐시 키에 쿼리·쿠키·헤더 없음
resource "aws_cloudfront_cache_policy" "static_assets" {
  name        = "${var.name}-static-assets"
  comment     = "/assets/* hashed files: 1 year, no query/cookie/header in key"
  min_ttl     = 86400
  default_ttl = 31536000
  max_ttl     = 31536000

  parameters_in_cache_key_and_forwarded_to_origin {
    enable_accept_encoding_gzip   = true
    enable_accept_encoding_brotli = true

    cookies_config { cookie_behavior = "none" }
    headers_config { header_behavior = "none" }
    query_strings_config { query_string_behavior = "none" }
  }
}

# 2) 나머지 S3 파일(index.html·favicon 등): 원본 Cache-Control을 따른다.
#    index.html은 frontend-deploy가 no-cache로 올린다 → min 0이라 캐시하지 않는다. 그 외는 최대 60초
resource "aws_cloudfront_cache_policy" "spa_shell" {
  name        = "${var.name}-spa-shell"
  comment     = "index.html and root files: follow origin Cache-Control (no-cache = not cached), max 60s"
  min_ttl     = 0
  default_ttl = 0
  max_ttl     = 60

  parameters_in_cache_key_and_forwarded_to_origin {
    enable_accept_encoding_gzip   = true
    enable_accept_encoding_brotli = true

    cookies_config { cookie_behavior = "none" }
    headers_config { header_behavior = "none" }
    query_strings_config { query_string_behavior = "none" } # /callback?code=... 도 같은 index.html
  }
}

# 3) /api/*: 관리형 CachingDisabled (캐시 없음. 뷰어 헤더는 아래 원본 요청 정책이 전달)
data "aws_cloudfront_cache_policy" "caching_disabled" {
  name = "Managed-CachingDisabled"
}

# /api 원본 요청: 뷰어 헤더 전부(Authorization·Idempotency-Key·Accept·Last-Event-ID) + CloudFront-Viewer-Address(gateway가 사용자 IP로 읽음),
# 쿼리 전부, 쿠키 없음(토큰은 Bearer 헤더, ADR-63)
resource "aws_cloudfront_origin_request_policy" "api" {
  name    = "${var.name}-api-origin"
  comment = "all viewer headers + CloudFront-Viewer-Address, all query strings, no cookies"

  cookies_config { cookie_behavior = "none" }

  headers_config {
    header_behavior = "allViewerAndWhitelistCloudFront"
    headers { items = ["CloudFront-Viewer-Address"] }
  }

  query_strings_config { query_string_behavior = "all" }
}

# S3 응답에 보안 헤더(HSTS·nosniff·X-Frame-Options SAMEORIGIN·Referrer-Policy). /api는 서비스 응답 그대로
data "aws_cloudfront_response_headers_policy" "security_headers" {
  name = "Managed-SecurityHeadersPolicy"
}

# ---- CloudFront Function (SPA 라우팅) ---------------------------------
resource "aws_cloudfront_function" "spa_rewrite" {
  name    = "${var.name}-spa-rewrite"
  comment = "extensionless path -> /index.html"
  runtime = "cloudfront-js-2.0"
  publish = true
  code    = file("${path.module}/cloudfront/spa-rewrite.js")
}

# ---- 배포 --------------------------------------------------------------
resource "aws_cloudfront_distribution" "frontend" {
  enabled             = true
  comment             = "${var.name} frontend (S3) + /api (ALB gateway)"
  default_root_object = "index.html"
  price_class         = var.cloudfront_price_class
  http_version        = "http2and3"
  is_ipv6_enabled     = true

  origin {
    origin_id                = "s3-frontend"
    domain_name              = aws_s3_bucket.frontend.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.frontend.id
  }

  dynamic "origin" {
    for_each = local.api_origin_enabled ? [local.api_origin_domain_name] : []
    content {
      origin_id   = "alb-gateway"
      domain_name = origin.value

      custom_origin_config {
        http_port                = 80
        https_port               = 443
        origin_protocol_policy   = "http-only" # ALB 80. SG가 CloudFront origin-facing 접두사 목록만 허용(security_groups.tf)
        origin_ssl_protocols     = ["TLSv1.2"]
        origin_read_timeout      = 30 # 원본 응답 대기. SSE heartbeat 15초보다 길어야 한다(세트 ③). Gateway 응답 타임아웃 15초보다도 길다
        origin_keepalive_timeout = 5
      }
    }
  }

  # /* → S3 index.html (SPA)
  default_cache_behavior {
    target_origin_id       = "s3-frontend"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD", "OPTIONS"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true

    cache_policy_id            = aws_cloudfront_cache_policy.spa_shell.id
    response_headers_policy_id = data.aws_cloudfront_response_headers_policy.security_headers.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.spa_rewrite.arn
    }
  }

  # /assets/* → S3, 1년
  ordered_cache_behavior {
    path_pattern           = "/assets/*"
    target_origin_id       = "s3-frontend"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true

    cache_policy_id            = aws_cloudfront_cache_policy.static_assets.id
    response_headers_policy_id = data.aws_cloudfront_response_headers_policy.security_headers.id
  }

  # /api/screenings/*/seats/stream → ALB(압축 끔), /api/* → ALB. ALB가 아직 없으면 만들지 않는다
  dynamic "ordered_cache_behavior" {
    for_each = local.api_origin_enabled ? local.api_behaviors : []
    content {
      path_pattern           = ordered_cache_behavior.value.path_pattern
      target_origin_id       = "alb-gateway"
      viewer_protocol_policy = "https-only" # POST를 리다이렉트하면 깨진다 → http는 403
      allowed_methods        = ordered_cache_behavior.value.allowed_methods
      cached_methods         = ["GET", "HEAD"]
      compress               = ordered_cache_behavior.value.compress

      cache_policy_id          = data.aws_cloudfront_cache_policy.caching_disabled.id
      origin_request_policy_id = aws_cloudfront_origin_request_policy.api.id
    }
  }

  restrictions {
    geo_restriction { restriction_type = "none" }
  }

  viewer_certificate {
    cloudfront_default_certificate = true # *.cloudfront.net. 사용자 지정 도메인 없음(ADR-9)
  }

  tags = { Name = "${var.name}-frontend" }
}

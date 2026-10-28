# ------------------------------------------------------------------
# 3.1.16 Cognito 사용자 풀 · 앱 클라이언트 · Hosted UI 도메인 · ADMIN 그룹 · Pre sign-up Lambda (ADR-37·63)
#   - 이름(conventions 9장): 사용자 풀 cinema-staging-users · Lambda cinema-staging-cognito-presignup · 도메인 cinema-staging
#   - 로그인: Hosted UI(클래식) + 코드 + PKCE, 공개 클라이언트(시크릿 없음). 토큰은 화면 메모리(ADR-63)
#   - gateway: Access Token을 JWKS로 검증(iss·token_use=access·client_id) → X-User-Id(sub)·X-User-Role(cognito:groups의 ADMIN)
#   - user: 같은 Access Token으로 GetUser(email·name) → 클라이언트가 aws.cognito.signin.user.admin 범위를 허용해야 하고
#           화면도 그 범위를 요청해야 한다(런타임 계약 3장 frontend)
#   - 이메일 실발송 없음: Pre sign-up Lambda가 가입을 자동 확인(autoConfirmUser·autoVerifyEmail) → Cognito 기본 메일 한도와 무관
#   - 이 풀을 envs/staging-k8s가 이름으로 찾는다(cognito_enabled = true) → ConfigMap COGNITO_* · gateway Application
#   - 사용자·ADMIN 지정은 Terraform 밖(가입 후 CLI로 그룹 추가, 런북 7-6). 이메일 주소를 코드에 두지 않는다
# ------------------------------------------------------------------

locals {
  cognito_pool_name = "${var.name}-users" # cinema-staging-users

  # 콜백·로그아웃 URL: CloudFront 기본 도메인 + 로컬 개발(Vite 5173). 로그아웃은 화면이 origin + "/"로 돌아온다
  cognito_app_origins = [
    "https://${aws_cloudfront_distribution.frontend.domain_name}",
    "http://localhost:5173",
  ]
  cognito_issuer_uri = "https://cognito-idp.${var.region}.amazonaws.com/${aws_cognito_user_pool.this.id}"
  cognito_domain_url = "https://${aws_cognito_user_pool_domain.this.domain}.auth.${var.region}.amazoncognito.com"
}

# ---- Pre sign-up Lambda --------------------------------------------------
# 코드는 lambdas/cognito-presignup(5줄). Terraform이 묶어 올린다 — 바뀌면 plan에 함수 1 update
data "archive_file" "cognito_presignup" {
  type        = "zip"
  source_file = "${path.module}/../../../../lambdas/cognito-presignup/handler.py"
  output_path = "${path.module}/.build/cognito-presignup.zip" # .gitignore(.build/)
}

data "aws_iam_policy_document" "lambda_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "cognito_presignup" {
  name               = "${var.name}-cognito-presignup"
  description        = "3.1.16 Cognito Pre sign-up Lambda: CloudWatch Logs only"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
}

# 로그 그룹을 미리 만들어 보존 기간을 정한다(Lambda가 만들면 무기한) → 쓰기 권한도 이 그룹으로 좁힌다
resource "aws_cloudwatch_log_group" "cognito_presignup" {
  name              = "/aws/lambda/${var.name}-cognito-presignup"
  retention_in_days = 7
}

data "aws_iam_policy_document" "cognito_presignup_logs" {
  statement {
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.cognito_presignup.arn}:*"]
  }
}

resource "aws_iam_role_policy" "cognito_presignup_logs" {
  name   = "logs"
  role   = aws_iam_role.cognito_presignup.id
  policy = data.aws_iam_policy_document.cognito_presignup_logs.json
}

resource "aws_lambda_function" "cognito_presignup" {
  function_name    = "${var.name}-cognito-presignup" # cinema-staging-cognito-presignup
  description      = "3.1.16 Cognito Pre sign-up: auto-confirm sign-ups (no email sending)"
  role             = aws_iam_role.cognito_presignup.arn
  runtime          = "python3.12" # lambdas/README.md
  handler          = "handler.handler"
  filename         = data.archive_file.cognito_presignup.output_path
  source_code_hash = data.archive_file.cognito_presignup.output_base64sha256
  timeout          = 5
  memory_size      = 128
  architectures    = ["arm64"]

  logging_config {
    log_format = "Text"
    log_group  = aws_cloudwatch_log_group.cognito_presignup.name
  }

  depends_on = [aws_iam_role_policy.cognito_presignup_logs]
}

resource "aws_lambda_permission" "cognito_presignup" {
  statement_id  = "AllowCognitoPreSignUp"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.cognito_presignup.function_name
  principal     = "cognito-idp.amazonaws.com"
  source_arn    = aws_cognito_user_pool.this.arn
}

# ---- 사용자 풀 -------------------------------------------------------------
resource "aws_cognito_user_pool" "this" {
  name = local.cognito_pool_name

  # 이메일로 로그인. 가입 화면에서 email·name을 받는다(user 프로필, data-model user 테이블)
  username_attributes = ["email"]
  username_configuration {
    case_sensitive = false
  }

  schema {
    name                = "email"
    attribute_data_type = "String"
    required            = true
    mutable             = true
    string_attribute_constraints { # 표준 속성의 AWS 기본값 그대로(다르면 매 plan마다 차이·수정 불가 오류)
      min_length = 0
      max_length = 2048
    }
  }
  schema {
    name                = "name"
    attribute_data_type = "String"
    required            = true
    mutable             = true
    string_attribute_constraints { # 표준 속성의 AWS 기본값 그대로(다르면 매 plan마다 차이·수정 불가 오류)
      min_length = 0
      max_length = 2048
    }
  }

  password_policy {
    minimum_length                   = 8
    require_lowercase                = true
    require_numbers                  = true
    require_uppercase                = false
    require_symbols                  = false
    temporary_password_validity_days = 7
  }

  # 메일을 보내지 않으므로 비밀번호 찾기는 관리자 재설정(런북 7-6). 복구 수단은 형식상 이메일
  account_recovery_setting {
    recovery_mechanism {
      name     = "verified_email"
      priority = 1
    }
  }

  admin_create_user_config {
    allow_admin_create_user_only = false # Hosted UI 가입 허용 (부하 테스트 계정은 AdminCreateUser, 4.2.1)
  }

  lambda_config {
    pre_sign_up = aws_lambda_function.cognito_presignup.arn
  }

  # 재생성 리허설(3.1.15·11장)에서 지울 수 있게 보호는 끈다. 사용자는 테스트 계정뿐
  deletion_protection = "INACTIVE"
}

# 공개 클라이언트(SPA): 시크릿 없음, 코드 + PKCE. staging-k8s는 클라이언트가 정확히 하나라고 가정한다(infra_endpoints.tf)
resource "aws_cognito_user_pool_client" "web" {
  name         = "${var.name}-web"
  user_pool_id = aws_cognito_user_pool.this.id

  generate_secret                      = false
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["code"]
  # aws.cognito.signin.user.admin: user 서비스가 같은 Access Token으로 GetUser를 부르려면 필요
  allowed_oauth_scopes         = ["openid", "email", "profile", "aws.cognito.signin.user.admin"]
  supported_identity_providers = ["COGNITO"]
  callback_urls                = [for o in local.cognito_app_origins : "${o}/callback"]
  logout_urls                  = [for o in local.cognito_app_origins : "${o}/"]

  explicit_auth_flows = ["ALLOW_USER_SRP_AUTH", "ALLOW_REFRESH_TOKEN_AUTH"]

  access_token_validity  = 60
  id_token_validity      = 60
  refresh_token_validity = 7
  token_validity_units {
    access_token  = "minutes"
    id_token      = "minutes"
    refresh_token = "days"
  }

  prevent_user_existence_errors = "ENABLED"
  enable_token_revocation       = true
}

# Hosted UI 도메인 https://cinema-staging.auth.ap-northeast-2.amazoncognito.com (런타임 계약 3장 VITE_COGNITO_DOMAIN)
#   접두사는 리전 안에서 전역 고유 — 이미 쓰이면 apply가 실패한다 → var.cognito_domain_prefix를 바꾸고 계약 3장도 고친다
resource "aws_cognito_user_pool_domain" "this" {
  domain                = var.cognito_domain_prefix
  user_pool_id          = aws_cognito_user_pool.this.id
  managed_login_version = 1 # 클래식 Hosted UI (2 = 관리형 로그인은 브랜딩 리소스가 따로 필요)
}

# gateway: cognito:groups에 ADMIN이 있으면 X-User-Role: ADMIN (/api/admin/**)
resource "aws_cognito_user_group" "admin" {
  name         = "ADMIN"
  user_pool_id = aws_cognito_user_pool.this.id
  description  = "SeatSAC 관리자 (/api/admin/**). 사람 지정은 런북 7-6"
}

# ---- 출력 -----------------------------------------------------------------
output "cognito_user_pool_id" {
  description = "envs/staging-k8s가 이름(cinema-staging-users)으로 찾는다. 사용자 관리 CLI(--user-pool-id)용"
  value       = aws_cognito_user_pool.this.id
}

output "cognito_issuer_uri" {
  description = "gateway APP_COGNITO_ISSUER_URI(ConfigMap cinema-infra-endpoints COGNITO_ISSUER_URI)와 같은 값"
  value       = local.cognito_issuer_uri
}

output "cognito_client_id" {
  value = aws_cognito_user_pool_client.web.id
}

output "cognito_hosted_ui_url" {
  value = local.cognito_domain_url
}

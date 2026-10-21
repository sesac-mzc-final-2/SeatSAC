# ------------------------------------------------------------------
# GitHub Actions → AWS (OIDC, 장기 키 없음) — 04 3장 7번
#   cinema-gha-ecr-push        : main 브랜치에서만. ECR cinema/* push        → secrets.AWS_ECR_PUSH_ROLE_ARN
#   cinema-gha-tf-plan         : PR에서만. ReadOnly + state 잠금 파일          → secrets.AWS_TERRAFORM_PLAN_ROLE_ARN
#   cinema-gha-frontend-deploy : main 브랜치에서만. S3 업로드 + CloudFront 무효화 → secrets.AWS_FRONTEND_DEPLOY_ROLE_ARN
#   apply는 CI에서 하지 않는다 (03b: apply는 김종식이 로컬에서 cinema-admin으로)
# ------------------------------------------------------------------

resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
  # thumbprint_list 생략: GitHub OIDC는 AWS가 신뢰 CA로 검증한다
}

locals {
  # GitHub OIDC 토큰의 sub 앞부분. 조직 설정에 따라 두 형식 중 하나가 온다 (대소문자 구분)
  #   기본      : repo:<owner>/<repo>
  #   변경 불가 ID: repo:<owner>@<owner_id>/<repo>@<repo_id>   ← sesac-mzc-final-2 조직은 이 형식 (10/21 확인)
  gh_repo = (var.github_owner_id != "" && var.github_repo_id != ""
    ? "repo:${var.github_owner}@${var.github_owner_id}/${var.github_repo}@${var.github_repo_id}"
    : "repo:${var.github_owner}/${var.github_repo}")
}

# 공통: aud 확인 + sub(어느 레포·어느 이벤트인지)로 제한
data "aws_iam_policy_document" "gha_main" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["${local.gh_repo}:ref:refs/heads/main"] # main push·main에서 실행한 workflow_dispatch
    }
  }
}

data "aws_iam_policy_document" "gha_pull_request" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["${local.gh_repo}:pull_request"]
    }
  }
}

# ---- ECR push ------------------------------------------------------------
resource "aws_iam_role" "gha_ecr_push" {
  name               = "cinema-gha-ecr-push"
  description        = "GitHub Actions build-push (main only)"
  assume_role_policy = data.aws_iam_policy_document.gha_main.json
}

data "aws_iam_policy_document" "ecr_push" {
  statement {
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"] # 리소스 지정 불가인 API
  }
  statement {
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:CompleteLayerUpload",
      "ecr:DescribeImages",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
    ]
    resources = ["arn:aws:ecr:${var.region}:${local.account_id}:repository/cinema/*"]
  }
}

resource "aws_iam_role_policy" "gha_ecr_push" {
  name   = "ecr-push-cinema"
  role   = aws_iam_role.gha_ecr_push.id
  policy = data.aws_iam_policy_document.ecr_push.json
}

# ---- terraform plan (PR) -------------------------------------------------
resource "aws_iam_role" "gha_tf_plan" {
  name               = "cinema-gha-tf-plan"
  description        = "GitHub Actions infra-plan on pull requests (read-only, no apply)"
  assume_role_policy = data.aws_iam_policy_document.gha_pull_request.json
}

resource "aws_iam_role_policy_attachment" "gha_tf_plan_readonly" {
  role       = aws_iam_role.gha_tf_plan.name
  policy_arn = "arn:aws:iam::aws:policy/ReadOnlyAccess"
}

# plan도 state 잠금 파일(.tflock)을 만들고 지운다 → 그 파일만 쓰기 허용
data "aws_iam_policy_document" "tf_plan_state" {
  statement {
    actions   = ["s3:ListBucket"]
    resources = ["arn:aws:s3:::${local.tfstate_bucket}"]
  }
  statement {
    actions   = ["s3:GetObject"]
    resources = ["arn:aws:s3:::${local.tfstate_bucket}/staging/*"]
  }
  statement {
    actions   = ["s3:PutObject", "s3:DeleteObject"]
    resources = ["arn:aws:s3:::${local.tfstate_bucket}/staging/terraform.tfstate.tflock"]
  }
}

resource "aws_iam_role_policy" "gha_tf_plan_state" {
  name   = "tfstate-staging-plan"
  role   = aws_iam_role.gha_tf_plan.id
  policy = data.aws_iam_policy_document.tf_plan_state.json
}

# ---- frontend 배포 (3.1.11, OPS-12) --------------------------------------
resource "aws_iam_role" "gha_frontend_deploy" {
  name               = "cinema-gha-frontend-deploy"
  description        = "GitHub Actions frontend-deploy (main only): S3 sync + CloudFront invalidation"
  assume_role_policy = data.aws_iam_policy_document.gha_main.json
}

data "aws_iam_policy_document" "frontend_deploy" {
  statement {
    actions   = ["s3:ListBucket"]
    resources = ["arn:aws:s3:::cinema-staging-frontend-${local.account_id}"] # 버킷은 3.1.11에서 생성 (03a 이름 규칙)
  }
  statement {
    actions   = ["s3:PutObject", "s3:DeleteObject", "s3:GetObject"]
    resources = ["arn:aws:s3:::cinema-staging-frontend-${local.account_id}/*"]
  }
  statement {
    actions   = ["cloudfront:CreateInvalidation", "cloudfront:GetInvalidation"]
    resources = ["*"] # 3.1.11에서 배포 ID가 정해지면 해당 ARN으로 좁힌다
  }
}

resource "aws_iam_role_policy" "gha_frontend_deploy" {
  name   = "frontend-deploy"
  role   = aws_iam_role.gha_frontend_deploy.id
  policy = data.aws_iam_policy_document.frontend_deploy.json
}

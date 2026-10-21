# ------------------------------------------------------------------
# 3.1.10 팀 접근 (OPS-11) — 학원 계정이라 IAM Identity Center 대신 IAM 역할 + MFA
#   cinema-admin     : AdministratorAccess (김종식 + 백업 관리자)
#   cinema-developer : ReadOnlyAccess + 거부 정책 (tfstate 읽기·ECR push·비밀 조회 금지)
#   - 역할 전환은 MFA를 켠 세션에서만 가능
#   - EKS 권한은 3.1.2 Access Entry에서 이 두 역할 ARN에 준다 (admin=ClusterAdmin, developer=View+port-forward)
# ------------------------------------------------------------------

locals {
  admin_user_arns     = [for u in var.admin_users : "arn:aws:iam::${local.account_id}:user/${u}"]
  developer_user_arns = [for u in var.developer_users : "arn:aws:iam::${local.account_id}:user/${u}"]
  all_team_users      = distinct(concat(var.admin_users, var.developer_users))
}

data "aws_iam_policy_document" "assume_admin" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "AWS"
      identifiers = local.admin_user_arns
    }
    condition {
      test     = "Bool"
      variable = "aws:MultiFactorAuthPresent"
      values   = ["true"]
    }
  }
}

data "aws_iam_policy_document" "assume_developer" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "AWS"
      identifiers = local.developer_user_arns
    }
    condition {
      test     = "Bool"
      variable = "aws:MultiFactorAuthPresent"
      values   = ["true"]
    }
  }
}

# ---- cinema-admin ------------------------------------------------------
resource "aws_iam_role" "admin" {
  name                 = "cinema-admin"
  description          = "SeatSAC infra admin (Terraform apply, EKS cluster admin). MFA required"
  assume_role_policy   = data.aws_iam_policy_document.assume_admin.json
  max_session_duration = var.session_hours * 3600
}

resource "aws_iam_role_policy_attachment" "admin" {
  role       = aws_iam_role.admin.name
  policy_arn = "arn:aws:iam::aws:policy/AdministratorAccess"
}

# ---- cinema-developer --------------------------------------------------
resource "aws_iam_role" "developer" {
  name                 = "cinema-developer"
  description          = "SeatSAC developer (read-only AWS, EKS view in cinema-staging). MFA required"
  assume_role_policy   = data.aws_iam_policy_document.assume_developer.json
  max_session_duration = var.session_hours * 3600
}

resource "aws_iam_role_policy_attachment" "developer_readonly" {
  role       = aws_iam_role.developer.name
  policy_arn = "arn:aws:iam::aws:policy/ReadOnlyAccess"
}

# ReadOnlyAccess는 S3 객체 읽기도 허용한다 → tfstate(DB 비밀번호 평문 포함)를 막아야 한다
data "aws_iam_policy_document" "developer_deny" {
  statement {
    sid       = "DenyTfstateRead"
    effect    = "Deny"
    actions   = ["s3:GetObject", "s3:GetObjectVersion"]
    resources = ["arn:aws:s3:::${local.tfstate_bucket}/*"]
  }

  statement {
    sid    = "DenyImagePush" # 이미지는 CI만 올린다 (최종 결정 35번)
    effect = "Deny"
    actions = [
      "ecr:PutImage",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
    ]
    resources = ["*"]
  }

  statement {
    sid    = "DenySecretValues"
    effect = "Deny"
    actions = [
      "secretsmanager:GetSecretValue",
      "ssm:GetParameter",
      "ssm:GetParameters",
      "ssm:GetParametersByPath",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "developer_deny" {
  name   = "cinema-developer-deny"
  role   = aws_iam_role.developer.id
  policy = data.aws_iam_policy_document.developer_deny.json
}

# ---- (선택) 사용자 쪽 '역할 전환 허용' 정책 ------------------------------
# 학원이 준 사용자 권한에 sts:AssumeRole이 없을 때만 켠다 (variables.tf 참고)
data "aws_iam_policy_document" "allow_assume_team_roles" {
  statement {
    actions   = ["sts:AssumeRole"]
    resources = [aws_iam_role.admin.arn, aws_iam_role.developer.arn]
  }
}

resource "aws_iam_policy" "allow_assume_team_roles" {
  count       = var.attach_assume_policy_to_users ? 1 : 0
  name        = "cinema-allow-assume-team-roles"
  description = "Allow switching to cinema-admin / cinema-developer (trust policy still decides who)"
  policy      = data.aws_iam_policy_document.allow_assume_team_roles.json
}

resource "aws_iam_user_policy_attachment" "allow_assume_team_roles" {
  for_each   = var.attach_assume_policy_to_users ? toset(local.all_team_users) : toset([])
  user       = each.value
  policy_arn = aws_iam_policy.allow_assume_team_roles[0].arn
}

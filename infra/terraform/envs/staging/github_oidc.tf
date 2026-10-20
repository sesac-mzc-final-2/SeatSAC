resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
  # thumbprint_list는 AWS provider 최신 버전에서는 생략 가능(사용 중인 버전 확인)
}

data "aws_iam_policy_document" "tf_plan_trust" {
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
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:sesac-mzc-final-2/seatsac:pull_request"]  # PR 전용
    }
  }
}

resource "aws_iam_role" "tf_plan" {
  name               = "seatsac-staging-github-tf-plan"
  assume_role_policy = data.aws_iam_policy_document.tf_plan_trust.json
}

resource "aws_iam_role_policy_attachment" "tf_plan_readonly" {
  role       = aws_iam_role.tf_plan.name
  policy_arn = "arn:aws:iam::aws:policy/ReadOnlyAccess"
}

# tfstate 읽기 + use_lockfile 잠금 파일 쓰기
data "aws_iam_policy_document" "tf_plan_state" {
  statement {
    actions   = ["s3:ListBucket"]
    resources = ["arn:aws:s3:::seatsac-tfstate-${data.aws_caller_identity.current.account_id}"]
  }
  statement {
    actions = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = [
      "arn:aws:s3:::seatsac-tfstate-${data.aws_caller_identity.current.account_id}/staging/terraform.tfstate",
      "arn:aws:s3:::seatsac-tfstate-${data.aws_caller_identity.current.account_id}/staging/terraform.tfstate.tflock",
    ]
  }
}

data "aws_caller_identity" "current" {}

resource "aws_iam_role_policy" "tf_plan_state" {
  name   = "tfstate-access"
  role   = aws_iam_role.tf_plan.id
  policy = data.aws_iam_policy_document.tf_plan_state.json
}

output "tf_plan_role_arn" {
  value = aws_iam_role.tf_plan.arn
}
provider "aws" {
  region = var.region # 자격 증명은 환경변수 AWS_PROFILE(SSO) 또는 CI의 OIDC 역할 → 코드에 프로필·키를 두지 않는다

  # 모든 리소스에 공통 태그 → 비용 탐색기에서 Project/Env로 집계
  default_tags {
    tags = {
      Project   = "cinema"
      Env       = var.env
      ManagedBy = "terraform"
      Owner     = var.owner
    }
  }
}

# 3.1.23 CloudFront 인증서는 us-east-1(버지니아)의 ACM에만 둘 수 있다 — 사용자 도메인 인증서 전용
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = {
      Project   = "cinema"
      Env       = var.env
      ManagedBy = "terraform"
      Owner     = var.owner
    }
  }
}

# 계정 ID·팀 역할 ARN (역할 자체는 infra/terraform/global에서 만든다)
data "aws_caller_identity" "current" {}

locals {
  account_id         = data.aws_caller_identity.current.account_id
  admin_role_arn     = "arn:aws:iam::${local.account_id}:role/cinema-admin"        # 3.1.2 Access Entry
  developer_role_arn = "arn:aws:iam::${local.account_id}:role/cinema-developer"    # 3.1.2 Access Entry
  gha_ecr_push_arn   = "arn:aws:iam::${local.account_id}:role/cinema-gha-ecr-push" # 3.1.4 ECR 저장소 정책
}
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

data "aws_caller_identity" "current" {}

locals {
  account_id         = data.aws_caller_identity.current.account_id
  admin_role_arn     = "arn:aws:iam::${local.account_id}:role/cinema-admin"       # 3.1.2 Access Entry
  developer_role_arn = "arn:aws:iam::${local.account_id}:role/cinema-developer"   # 3.1.2 Access Entry
  gha_ecr_push_arn   = "arn:aws:iam::${local.account_id}:role/cinema-gha-ecr-push" # 3.1.4 ECR 저장소 정책
}
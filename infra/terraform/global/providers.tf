provider "aws" {
  region = var.region # 자격 증명: AWS_PROFILE(cinema-admin 역할). 코드에 키·프로필을 두지 않는다

  default_tags {
    tags = {
      Project   = "cinema"
      Env       = "global"
      ManagedBy = "terraform"
      Owner     = var.owner
    }
  }
}

data "aws_caller_identity" "me" {}

locals {
  account_id     = data.aws_caller_identity.me.account_id
  tfstate_bucket = "cinema-tfstate-${local.account_id}"
}

# ------------------------------------------------------------------
# bootstrap: tfstate 버킷 1회 생성 (이 디렉터리만 로컬 state를 쓴다)
#   cd infra/terraform/bootstrap && terraform init && terraform apply
#   → 출력된 bucket 이름을 envs/staging/backend.hcl 에 적는다
# ------------------------------------------------------------------
terraform {
  required_version = ">= 1.10"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = "ap-northeast-2" # 자격 증명은 환경변수 AWS_PROFILE(SSO) 또는 CI의 OIDC 역할
  default_tags {
    tags = {
      Project   = "cinema"
      Env       = "shared"
      ManagedBy = "terraform"
    }
  }
}

data "aws_caller_identity" "me" {}

resource "aws_s3_bucket" "tfstate" {
  bucket = "cinema-tfstate-${data.aws_caller_identity.me.account_id}"

  lifecycle {
    prevent_destroy = true # 실수로 destroy 하면 모든 state가 사라진다
  }
}

resource "aws_s3_bucket_versioning" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id
  versioning_configuration {
    status = "Enabled" # 잘못된 apply 후 이전 state로 되돌리기
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256" # AWS 관리 키 (KMS CMK 제외 결정)
    }
  }
}

resource "aws_s3_bucket_public_access_block" "tfstate" {
  bucket                  = aws_s3_bucket.tfstate.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

output "tfstate_bucket" {
  value = aws_s3_bucket.tfstate.bucket
}

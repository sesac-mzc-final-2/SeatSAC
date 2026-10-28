terraform {
  required_version = ">= 1.10" # S3 네이티브 잠금(use_lockfile)은 1.10부터

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    archive = {
      source  = "hashicorp/archive" # 3.1.16 Pre sign-up Lambda 코드 zip (cognito.tf)
      version = "~> 2.7"
    }
  }

  # 버킷 이름은 backend.hcl 에서: terraform init -backend-config=backend.hcl
  backend "s3" {
    key          = "staging/terraform.tfstate"
    region       = "ap-northeast-2"
    encrypt      = true
    use_lockfile = true # DynamoDB 잠금 테이블 없이 S3 잠금 파일 사용
  }
}

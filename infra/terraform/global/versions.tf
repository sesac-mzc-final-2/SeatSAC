terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # staging과 같은 state 버킷, 키만 다르다 → staging을 destroy 해도 팀 접근·CI 역할은 남는다
  backend "s3" {
    key          = "global/terraform.tfstate"
    region       = "ap-northeast-2"
    encrypt      = true
    use_lockfile = true
  }
}

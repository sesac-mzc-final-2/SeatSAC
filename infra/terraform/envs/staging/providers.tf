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

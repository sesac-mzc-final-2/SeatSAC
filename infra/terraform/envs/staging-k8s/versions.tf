# ------------------------------------------------------------------
# staging-k8s: EKS "안"에 Terraform으로 만드는 것 (3.1.2 · 3.1.12)
#   - envs/staging(AWS 리소스)과 state를 나눈다. 클러스터가 없을 때도 staging plan이
#     kubernetes·helm provider 때문에 깨지지 않게 하려는 것.
#   - 3.1.2: namespace · 팀원 port-forward RBAC · AWS Load Balancer Controller
#   - 3.1.12: DB 스키마·계정(클러스터 안 Job) · {svc}-secrets · {svc}-migrator-secrets (db_bootstrap.tf)
#   - 3.1.4·3.1.5: 엔드포인트 ConfigMap(infra_endpoints.tf) · ArgoCD(argocd.tf) · root·서비스 Application(argocd_apps.tf)
#     ⚠️ 이 state에는 DB 계정 비밀번호가 평문으로 있다 → 읽기는 관리자만
#   - 실행: 김종식(또는 백업 관리자)이 로컬에서 cinema-admin으로만. CI infra-plan 대상 아님
#     (infra-plan은 envs/staging만, plan 역할의 state 권한도 staging/* 뿐)
#   - 순서: envs/staging apply → 이 폴더 apply. 삭제는 반대(이 폴더 destroy → staging)
# ------------------------------------------------------------------
terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = ">= 2.38, < 4.0"
    }
    helm = {
      source  = "hashicorp/helm"
      version = "~> 3.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
    http = {
      source  = "hashicorp/http"
      version = "~> 3.4"
    }
  }

  # 버킷은 envs/staging과 같은 backend.hcl, 키만 다르다
  backend "s3" {
    key          = "staging-k8s/terraform.tfstate"
    region       = "ap-northeast-2"
    encrypt      = true
    use_lockfile = true
  }
}

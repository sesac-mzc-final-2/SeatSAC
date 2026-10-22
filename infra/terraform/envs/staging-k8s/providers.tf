provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "cinema"
      Env       = var.env
      Owner     = var.owner
      ManagedBy = "terraform"
    }
  }
}

# 클러스터 정보는 원격 state가 아니라 이름으로 조회한다 (envs/staging state를 읽을 필요 없음)
data "aws_eks_cluster" "this" {
  name = var.cluster_name
}

locals {
  cluster_host = data.aws_eks_cluster.this.endpoint
  cluster_ca   = base64decode(data.aws_eks_cluster.this.certificate_authority[0].data)
  vpc_id       = data.aws_eks_cluster.this.vpc_config[0].vpc_id

  # 토큰은 실행자의 AWS_PROFILE(cinema-admin)로 발급 → Access Entry가 관리자 권한을 준다
  eks_token_args = ["eks", "get-token", "--cluster-name", var.cluster_name, "--region", var.region]
}

provider "kubernetes" {
  host                   = local.cluster_host
  cluster_ca_certificate = local.cluster_ca

  exec {
    api_version = "client.authentication.k8s.io/v1beta1"
    command     = "aws"
    args        = local.eks_token_args
  }
}

provider "helm" {
  kubernetes = {
    host                   = local.cluster_host
    cluster_ca_certificate = local.cluster_ca

    exec = {
      api_version = "client.authentication.k8s.io/v1beta1"
      command     = "aws"
      args        = local.eks_token_args
    }
  }
}

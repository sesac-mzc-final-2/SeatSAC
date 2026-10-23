variable "region" {
  type    = string
  default = "ap-northeast-2"
}

variable "env" {
  type    = string
  default = "staging"
}

variable "name" {
  description = "Resource name prefix (cinema-staging-*)"
  type        = string
  default     = "cinema-staging"
}

variable "owner" {
  type    = string
  default = "SeatSAC"
}

# ---- 네트워크 (3.1.1) ------------------------------------------------
variable "vpc_cidr" {
  type    = string
  default = "10.20.0.0/16"
}

variable "azs" {
  description = "AZ:(A, C): the order matches with subnet CIDR order"
  type        = list(string)
  default     = ["ap-northeast-2a", "ap-northeast-2c"]
}

variable "public_subnets" {
  description = "ALB · NAT · IGW"
  type        = list(string)
  default     = ["10.20.0.0/24", "10.20.1.0/24"]
}

variable "app_subnets" {
  description = "EKS Node·Pod (VPC CNI: Pod also uses this range of IP: /20)"
  type        = list(string)
  default     = ["10.20.16.0/20", "10.20.32.0/20"]
}

variable "data_subnets" {
  description = "RDS · ElastiCache"
  type        = list(string)
  default     = ["10.20.48.0/24", "10.20.49.0/24"]
}

# ---- EKS (3.1.2) -----------------------------------------------------
variable "eks_version" {
  description = "Kubernetes version. 1.36 Standard Support ~2027-08-02. Increase 1 by 1"
  type        = string
  default     = "1.36"
}

variable "eks_public_access_cidrs" {
  description = "EKS API 공개 엔드포인트 허용 대역(팀 공인 IP /32). 값은 커밋하지 않는다: 로컬 eks_access.auto.tfvars, CI Secret EKS_PUBLIC_ACCESS_CIDRS"
  type        = list(string)
  sensitive   = true # ← 여기. plan·apply 출력에 (sensitive value)로 가려진다

  validation {
    condition     = length(var.eks_public_access_cidrs) > 0 && !contains(var.eks_public_access_cidrs, "0.0.0.0/0")
    error_message = "eks_public_access_cidrs에 팀 공인 IP 대역을 넣는다. 0.0.0.0/0(전체 공개)은 쓰지 않는다."
  }
}

variable "node_instance_type" {
  description = "Node instance (ADR-7: t3.large x2)"
  type        = string
  default     = "t3.large"
}

variable "developer_namespaces" {
  description = "namespace where cinema-developer can search·log·port-forward"
  type        = list(string)
  default     = ["cinema-staging", "monitoring"]
}

# ---- CloudFront·S3 (3.1.11) --------------------------------------------
variable "gateway_ingress_stack" {
  description = "gateway Ingress의 <namespace>/<이름>(gitops 3.1.5). LB Controller가 ALB에 태그 ingress.k8s.aws/stack=<이 값>을 붙인다 → CloudFront /api/* 원본을 자동으로 찾는다"
  type        = string
  default     = "cinema-staging/gateway"
}

variable "api_origin_domain_name" {
  description = "비워 두면 위 태그로 ALB를 찾는다. 태그 탐색이 안 될 때만 ALB DNS 이름을 직접 넣는다(비밀 아님)"
  type        = string
  default     = ""
}

variable "cloudfront_price_class" {
  description = "PriceClass_200 = 북미·유럽·아시아(한국 포함). PriceClass_100은 한국 엣지가 빠진다"
  type        = string
  default     = "PriceClass_200"
}

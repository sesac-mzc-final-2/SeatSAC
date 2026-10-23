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

# ---- RDS·Valkey (3.1.3) ----------------------------------------------
variable "rds_engine_version" {
  description = "MySQL 메이저 버전(ADR-3·33). 마이너는 생성 시점 AWS 기본값, 자동 업그레이드 끔"
  type        = string
  default     = "8.4"
}

variable "rds_instance_class" {
  description = "RDS 클래스. 파드당 Hikari 5 x 최대 파드 수 + Flyway Job 연결을 받아야 한다"
  type        = string
  default     = "db.t4g.small"
}

variable "rds_connection_alarm" {
  description = "RDS 연결 수 경보 기준 (t4g.small max_connections 약 170의 80%)"
  type        = number
  default     = 130
}

variable "valkey_engine_version" {
  type    = string
  default = "8.2"
}

variable "valkey_node_type" {
  description = "ADR-59: t4g.micro, 부하 테스트에서 메모리 70% 넘으면 t4g.small"
  type        = string
  default     = "cache.t4g.micro"
}

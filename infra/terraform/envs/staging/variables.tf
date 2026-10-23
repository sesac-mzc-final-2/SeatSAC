variable "region" {
  type    = string
  default = "ap-northeast-2"
}

variable "env" {
  type    = string
  default = "staging"
}

variable "name" {
  description = "리소스 이름 접두사 (cinema-staging-*)"
  type        = string
  default     = "cinema-staging"
}

variable "owner" {
  type    = string
  default = "kimjongsik"
}

# ---- 네트워크 (3.1.1) ------------------------------------------------
variable "vpc_cidr" {
  type    = string
  default = "10.20.0.0/16"
}

variable "azs" {
  description = "가용 영역 2개 (A, C). 순서가 서브넷 CIDR 순서와 같다"
  type        = list(string)
  default     = ["ap-northeast-2a", "ap-northeast-2c"]
}

variable "public_subnets" {
  description = "ALB · NAT · IGW"
  type        = list(string)
  default     = ["10.20.0.0/24", "10.20.1.0/24"]
}

variable "app_subnets" {
  description = "EKS 노드·파드 (VPC CNI라 파드도 이 대역의 IP를 쓴다 → /20)"
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
  description = "Kubernetes 버전. 1.36 표준 지원 ~2027-08-02. 올릴 때는 한 단계씩"
  type        = string
  default     = "1.36"
}

variable "eks_public_access_cidrs" {
  description = <<-EOT
    EKS API 공개 엔드포인트 허용 대역(kubectl·helm을 쓰는 곳의 공인 IP, /32 권장). 기본값 없음 — 전체 공개 금지.
    공인 IP는 커밋하지 않는다(발표 후 Public 레포):
      로컬 apply : eks_access.auto.tfvars (.gitignore의 *.auto.tfvars)
      CI plan    : GitHub Secret EKS_PUBLIC_ACCESS_CIDRS → TF_VAR_eks_public_access_cidrs (JSON 배열)
    GitHub Actions는 클러스터 API를 부르지 않는다(infra-plan은 AWS API만, cluster-schedule은 EKS 관리 API만) → Actions 대역 불필요.
    IP가 바뀌면 두 곳을 고치고 apply (런북 4-6). 엔드포인트 재생성 없이 제자리 변경.
  EOT
  type        = list(string)
  sensitive   = true

  validation {
    condition     = length(var.eks_public_access_cidrs) > 0 && !contains(var.eks_public_access_cidrs, "0.0.0.0/0")
    error_message = "eks_public_access_cidrs에 팀 공인 IP 대역을 넣는다. 0.0.0.0/0(전체 공개)은 쓰지 않는다."
  }

  validation {
    condition     = alltrue([for c in var.eks_public_access_cidrs : can(cidrhost(c, 0))])
    error_message = "CIDR 형식으로 넣는다 (예: 203.0.113.10/32)."
  }
}

variable "node_instance_type" {
  description = "노드 인스턴스 (ADR-7: t3.large 2대)"
  type        = string
  default     = "t3.large"
}

variable "developer_namespaces" {
  description = "cinema-developer가 조회·로그·port-forward 할 수 있는 namespace"
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
  description = "RDS 클래스. 파드당 Hikari 5 × 최대 파드 수 + Flyway Job 연결을 받아야 한다"
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

# ---- 3.1.12 DB 계정 생성 Job ------------------------------------------
variable "ops_namespace" {
  description = "관리자 전용 namespace(팀원 조회 범위 밖). DB 계정 생성 Job·그 입력 Secret이 여기 있다. envs/staging-k8s의 ops_namespace와 같은 값"
  type        = string
  default     = "cinema-ops"
}

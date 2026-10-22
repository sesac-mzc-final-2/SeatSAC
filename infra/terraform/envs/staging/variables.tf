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
  default = "SeatSAC"
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
  description = "API 공개 엔드포인트 허용 대역. 팀원 노트북·GitHub Actions(plan은 API 호출 안 함) → 기본 전체, 인증은 IAM"
  type        = list(string)
  default     = ["0.0.0.0/0"]
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

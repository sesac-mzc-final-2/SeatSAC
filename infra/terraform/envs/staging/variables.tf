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
  description = "API that permitted public endpoint range. Private PC·GitHub Actions(plan does not call API): Whole basic, Auth: IAM"
  type        = list(string)
  default     = ["0.0.0.0/0"]
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

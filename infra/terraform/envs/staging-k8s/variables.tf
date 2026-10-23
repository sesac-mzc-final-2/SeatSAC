variable "region" {
  type    = string
  default = "ap-northeast-2"
}

variable "env" {
  type    = string
  default = "staging"
}

variable "owner" {
  type    = string
  default = "kimjongsik"
}

variable "cluster_name" {
  description = "envs/staging 출력 eks_cluster_name"
  type        = string
  default     = "cinema-staging-eks"
}

variable "app_namespace" {
  type    = string
  default = "cinema-staging"
}

variable "namespaces" {
  description = "Terraform이 만드는 namespace. 여기 추가해도 팀원 권한은 생기지 않는다(developer_namespaces와 별개)"
  type        = list(string)
  default     = ["cinema-staging", "monitoring", "cinema-ops"]
}

variable "ops_namespace" {
  description = "관리자 전용 namespace (3.1.12 DB 계정 생성 Job·입력 Secret). developer_namespaces에 넣지 않는다. envs/staging의 ops_namespace와 같은 값"
  type        = string
  default     = "cinema-ops"
}

variable "developer_namespaces" {
  description = <<-EOT
    팀원(cinema-developers)에게 port-forward Role을 줄 namespace. namespaces 안에 있어야 한다.
    envs/staging의 developer_namespaces(Access Entry 조회 범위)와 같은 값으로 둔다 — 둘이 다르면 조회는 되는데 port-forward가 안 되거나 그 반대
  EOT
  type        = list(string)
  default     = ["cinema-staging", "monitoring"]
}

variable "lbc_chart_version" {
  description = <<-EOT
    aws-load-balancer-controller Helm 차트 버전(고정). 처음 한 번 확인해서 tfvars에 적는다:
      helm repo add eks https://aws.github.io/eks-charts && helm repo update
      helm search repo eks/aws-load-balancer-controller --versions | head -5
    CHART VERSION 열의 값(APP VERSION 아님)
  EOT
  type        = string

  validation {
    condition     = can(regex("^[0-9]+\\.[0-9]+\\.[0-9]+$", var.lbc_chart_version))
    error_message = "lbc_chart_version에 helm search로 확인한 차트 버전(예: 1.2.3)을 적는다."
  }
}

variable "lbc_replicas" {
  description = "노드 2대라 1개. 컨트롤러는 트래픽 경로가 아니라(ALB가 처리) 재시작돼도 서비스 영향 없음"
  type        = number
  default     = 1
}

# ---- 3.1.12 DB 스키마·계정 ----------------------------------------------
variable "rds_identifier" {
  description = "envs/staging RDS 식별자 (3.1.3). 엔드포인트·마스터 Secret ARN을 이 이름으로 조회한다"
  type        = string
  default     = "cinema-staging-mysql"
}

variable "mysql_client_image" {
  description = "DB 계정 생성 Job의 mysql 클라이언트 이미지 (RDS 8.4와 같은 메이저, 버전 고정)"
  type        = string
  default     = "mysql:8.4.6"
}

variable "awscli_image" {
  description = "마스터 Secret을 읽는 initContainer 이미지 (Pod Identity 지원 AWS CLI v2, 버전 고정)"
  type        = string
  default     = "public.ecr.aws/aws-cli/aws-cli:2.33.6"
}

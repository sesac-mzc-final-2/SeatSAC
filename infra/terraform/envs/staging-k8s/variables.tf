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

variable "developer_namespaces" {
  description = "envs/staging의 developer_namespaces와 같게 (Access Entry 범위 = RBAC 범위)"
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

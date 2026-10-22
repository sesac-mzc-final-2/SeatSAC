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
  description = "envs/staging output eks_cluster_name"
  type        = string
  default     = "cinema-staging-eks"
}

variable "app_namespace" {
  type    = string
  default = "cinema-staging"
}

variable "developer_namespaces" {
  description = "same with developer_namespaces of envs/staging (Access Entry range = RBAC range)"
  type        = list(string)
  default     = ["cinema-staging", "monitoring"]
}

variable "lbc_chart_version" {
  description = <<-EOT
    aws-load-balancer-controller Helm chart version(static). First confirm, then write to tfvars:
      helm repo add eks https://aws.github.io/eks-charts && helm repo update
      helm search repo eks/aws-load-balancer-controller --versions | head -5
    value of row, CHART VERSION (not an APP VERSION)
  EOT
  type        = string

  validation {
    condition     = can(regex("^[0-9]+\\.[0-9]+\\.[0-9]+$", var.lbc_chart_version))
    error_message = "write a chart version(ex: 1.2.3) checked by helm search to lbc_chart_version"
  }
}

variable "lbc_replicas" {
  description = "1 replica because of 2 nodes. Since controller is not a traffic path(ALB handles it), when restarted, service has no change"
  type        = number
  default     = 1
}

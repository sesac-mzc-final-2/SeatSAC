variable "region" {
  type    = string
  default = "ap-northeast-2"
}

variable "owner" {
  type    = string
  default = "SeatSAC"
}

# ---- 팀 접근 (3.1.10, OPS-11) -----------------------------------------
# 학원이 만든 IAM 사용자 이름 그대로. 사용자 자체는 Terraform이 만들거나 지우지 않는다.
variable "admin_users" {
  description = "cinema-admin 역할로 전환할 수 있는 IAM 사용자 (김종식 + 백업 관리자 1명)"
  type        = list(string)
}

variable "developer_users" {
  description = "cinema-developer 역할로 전환할 수 있는 IAM 사용자 (팀원 전원. 관리자도 권한 확인용으로 포함)"
  type        = list(string)
}

variable "session_hours" {
  description = "역할 전환 세션 길이(시간). 최대 12"
  type        = number
  default     = 8
}

variable "attach_assume_policy_to_users" {
  description = <<-EOT
    true면 위 사용자들에게 '역할 전환 허용' 정책을 붙인다.
    학원이 이미 AdministratorAccess 등을 줬다면 필요 없음(false 유지). 사용자 권한을 바꾸는 일이라 학원 확인 후에만 true.
  EOT
  type        = bool
  default     = false
}

# ---- GitHub OIDC (04 3장 7번) ----------------------------------------
variable "github_owner" {
  description = "GitHub 조직 또는 사용자 이름"
  type        = string
}

variable "github_repo" {
  description = "모노레포 이름 (03b)"
  type        = string
  default     = "SeatSAC"
}

variable "github_owner_id" {
  description = "GitHub owner(조직·사용자)의 숫자 ID. 조직이 OIDC sub에 변경 불가 ID를 넣는 설정이면 필수 (예: repo:owner@123/repo@456:...)"
  type        = string
  default     = "329788874"
}

variable "github_repo_id" {
  description = "GitHub 레포의 숫자 ID. github_owner_id와 함께 쓴다"
  type        = string
  default     = "1425477103"
}
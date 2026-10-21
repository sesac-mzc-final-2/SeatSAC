# 3.1.2 EKS Access Entry와 GitHub 레포 Secrets에 넣을 값

output "admin_role_arn" {
  description = "EKS Access Entry: AmazonEKSClusterAdminPolicy (cluster)"
  value       = aws_iam_role.admin.arn
}

output "developer_role_arn" {
  description = "EKS Access Entry: AmazonEKSViewPolicy (namespace cinema-staging) + kubernetes_groups cinema-developers"
  value       = aws_iam_role.developer.arn
}

output "github_secrets" {
  description = "GitHub 레포 Settings → Secrets and variables → Actions 에 등록"
  value = {
    AWS_ECR_PUSH_ROLE_ARN        = aws_iam_role.gha_ecr_push.arn
    AWS_TERRAFORM_PLAN_ROLE_ARN  = aws_iam_role.gha_tf_plan.arn
    AWS_FRONTEND_DEPLOY_ROLE_ARN = aws_iam_role.gha_frontend_deploy.arn
  }
}

output "aws_config_profiles" {
  description = "팀원 ~/.aws/config 예시 (mfa_serial은 각자 값으로)"
  value       = <<-EOT
    [profile cinema-admin]
    source_profile   = default
    role_arn         = ${aws_iam_role.admin.arn}
    mfa_serial       = arn:aws:iam::${local.account_id}:mfa/<내 MFA 기기 이름>
    region           = ${var.region}
    duration_seconds = ${var.session_hours * 3600}

    [profile cinema-dev]
    source_profile   = default
    role_arn         = ${aws_iam_role.developer.arn}
    mfa_serial       = arn:aws:iam::${local.account_id}:mfa/<내 MFA 기기 이름>
    region           = ${var.region}
    duration_seconds = ${var.session_hours * 3600}
  EOT
}

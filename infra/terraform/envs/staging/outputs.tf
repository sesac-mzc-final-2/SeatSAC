# 3.1.2(EKS)·3.1.3(RDS·Valkey)·3.1.8(Lambda)이 같은 루트 모듈 안에서 참조하지만,
# kubectl·콘솔 확인과 런북 기록용으로도 출력한다.
output "vpc_id" {
  value = module.vpc.vpc_id
}

output "public_subnet_ids" {
  value = module.vpc.public_subnets
}

output "app_subnet_ids" {
  description = "EKS node group subnet"
  value       = module.vpc.private_subnets
}

output "data_subnet_ids" {
  value = module.vpc.database_subnets
}

output "db_subnet_group_name" {
  value = module.vpc.database_subnet_group_name
}

output "nat_public_ip" {
  description = "Outbound Static IP (IP shown at LotteCinema·GitHub)"
  value       = module.vpc.nat_public_ips
}

output "sg_alb_id" {
  value = aws_security_group.alb.id
}

output "sg_rds_id" {
  value = aws_security_group.rds.id
}

output "sg_valkey_id" {
  value = aws_security_group.valkey.id
}

# ---- EKS (3.1.2) -----------------------------------------------------
output "eks_cluster_name" {
  value = module.eks.cluster_name
}

output "eks_cluster_endpoint" {
  value = module.eks.cluster_endpoint
}

output "eks_kubeconfig_command" {
  description = "members: --profile cinema-dev, admin: cinema-admin"
  value       = "aws eks update-kubeconfig --region ${var.region} --name ${module.eks.cluster_name} --profile cinema-dev"
}

output "eks_node_security_group_id" {
  description = "Node·Pod SG (Allow ALB to 8080)"
  value       = module.eks.node_security_group_id
}

output "eks_node_group_names" {
  description = "cluster-schedule workflow(3.1.14): desired 1 ↔ 0 un object changeable"
  value       = { for k, v in module.eks.eks_managed_node_groups : k => split(":", v.node_group_id)[1] } # node_group_id = "클러스터:노드그룹"
}

output "lb_controller_role_arn" {
  value = module.lb_controller_pod_identity.iam_role_arn
}

# ---- CloudFront·S3 (3.1.11) → GitHub Variables(frontend-deploy) · global(무효화 권한) ----
output "cloudfront_domain_name" {
  description = "화면·API 주소: https://<이 값>/ , https://<이 값>/api/*"
  value       = aws_cloudfront_distribution.frontend.domain_name
}

output "cloudfront_distribution_id" {
  description = "global frontend_distribution_ids(무효화 권한을 이 배포로 좁힘) · GitHub Variables CLOUDFRONT_DISTRIBUTION_ID"
  value       = aws_cloudfront_distribution.frontend.id
}

output "frontend_bucket" {
  description = "GitHub Variables FRONTEND_BUCKET (frontend-deploy가 올리는 곳)"
  value       = aws_s3_bucket.frontend.bucket
}

output "api_origin_domain_name" {
  description = "/api/* 원본(gateway ALB). 비어 있으면 Ingress(gitops 3.1.5)가 아직 없다 → ALB가 생긴 뒤 apply를 한 번 더"
  value       = local.api_origin_enabled ? local.api_origin_domain_name : ""
}

output "frontend_github_variables" {
  description = "레포 Settings → Secrets and variables → Actions → Variables. Cognito 3개(VITE_COGNITO_AUTHORITY·CLIENT_ID·DOMAIN)는 cognito.tf(3.3.x) 출력에서"
  value = {
    FRONTEND_BUCKET            = aws_s3_bucket.frontend.bucket
    CLOUDFRONT_DISTRIBUTION_ID = aws_cloudfront_distribution.frontend.id
    VITE_COGNITO_REDIRECT_URI  = "https://${aws_cloudfront_distribution.frontend.domain_name}/callback"
  }
}

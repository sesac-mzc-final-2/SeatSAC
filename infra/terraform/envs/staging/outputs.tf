# 3.1.2(EKS)·3.1.3(RDS·Valkey)·3.1.8(Lambda)이 같은 루트 모듈 안에서 참조하지만,
# kubectl·콘솔 확인과 런북 기록용으로도 출력한다.
output "vpc_id" {
  value = module.vpc.vpc_id
}

output "public_subnet_ids" {
  value = module.vpc.public_subnets
}

output "app_subnet_ids" {
  description = "EKS 노드그룹 서브넷"
  value       = module.vpc.private_subnets
}

output "data_subnet_ids" {
  value = module.vpc.database_subnets
}

output "db_subnet_group_name" {
  value = module.vpc.database_subnet_group_name
}

output "nat_public_ip" {
  description = "외부로 나갈 때의 고정 IP (롯데시네마·GitHub 등에서 보이는 IP)"
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
  description = "팀원 컨텍스트 cinema-dev. 관리자는 --profile cinema-admin --alias cinema-admin --user-alias cinema-admin (런북 4-2)"
  value       = "aws eks update-kubeconfig --region ${var.region} --name ${module.eks.cluster_name} --profile cinema-dev --alias cinema-dev --user-alias cinema-dev"
}

output "eks_node_security_group_id" {
  description = "노드·파드 SG (ALB → 8080 허용)"
  value       = module.eks.node_security_group_id
}

output "eks_node_group_names" {
  description = "cluster-schedule 워크플로(3.1.14)가 desired 1 ↔ 0 으로 바꿀 대상"
  value       = { for k, v in module.eks.eks_managed_node_groups : k => split(":", v.node_group_id)[1] } # node_group_id = "클러스터:노드그룹"
}

output "lb_controller_role_arn" {
  value = module.lb_controller_pod_identity.iam_role_arn
}

# ---- RDS·Valkey (3.1.3) → 3.1.12 ConfigMap·Secret·DB 계정 Job --------------
output "rds_endpoint" {
  description = "SPRING_DATASOURCE_URL 호스트 (jdbc:mysql://<이 값>:3306/{svc}_db?connectionTimeZone=UTC&sslMode=REQUIRED)"
  value       = aws_db_instance.mysql.address
}

output "rds_master_secret_arn" {
  description = "마스터 계정 비밀번호가 든 Secrets Manager ARN (3.1.12 DB 계정 생성 Job만 사용. 앱은 쓰지 않음)"
  value       = aws_db_instance.mysql.master_user_secret[0].secret_arn
}

output "valkey_primary_endpoint" {
  description = "SPRING_DATA_REDIS_HOST (포트 6379, SSL true)"
  value       = aws_elasticache_replication_group.valkey.primary_endpoint_address
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
  description = "레포 Settings → Secrets and variables → Actions → Variables 6개 (런북 7-3). 비밀 없음"
  value = {
    FRONTEND_BUCKET            = aws_s3_bucket.frontend.bucket
    CLOUDFRONT_DISTRIBUTION_ID = aws_cloudfront_distribution.frontend.id
    VITE_COGNITO_REDIRECT_URI  = "https://${aws_cloudfront_distribution.frontend.domain_name}/callback"
    VITE_COGNITO_AUTHORITY     = local.cognito_issuer_uri # cognito.tf (3.1.16)
    VITE_COGNITO_CLIENT_ID     = aws_cognito_user_pool_client.web.id
    VITE_COGNITO_DOMAIN        = local.cognito_domain_url
  }
}

# ---- DB 계정 생성 Job (3.1.12) ------------------------------------------
output "db_bootstrap_role_arn" {
  description = "3.1.12 DB 계정 생성 Job Pod Identity 역할 (cinema-ops/db-bootstrap, 마스터 Secret 읽기만)"
  value       = module.db_bootstrap_pod_identity.iam_role_arn
}

output "cluster_schedules" {
  description = "3.1.14 EventBridge Scheduler 일정(노드그룹 켜기·끄기) — 이름 · 상태"
  value       = { for k, s in aws_scheduler_schedule.cluster : s.name => s.state }
}

output "cluster_schedule_group" {
  description = "GitHub cluster-schedule 백업이 끄기 상태를 확인하는 일정 그룹"
  value       = aws_scheduler_schedule_group.cluster.name
}

# ---- 3.1.9 알림 큐 (sqs.tf) ----------------------------------------------
# 큐 URL은 계정 ID를 포함한다 → PR·노션에 붙일 때는 <ACCOUNT_ID>로 가린다
output "notification_queue_url" {
  description = "booking APP_SQS_NOTIFICATION_QUEUE_URL과 같은 값이어야 한다(envs/staging-k8s ConfigMap cinema-infra-endpoints의 SQS_NOTIFICATION_URL)"
  value       = aws_sqs_queue.notification.url
}

output "notification_queue_arn" {
  description = "알림 Lambda(3.1.8) 이벤트 소스"
  value       = aws_sqs_queue.notification.arn
}

output "notification_dlq_url" {
  value = aws_sqs_queue.notification_dlq.url
}

output "booking_pod_role_arn" {
  description = "cinema-staging-booking-pod (Pod Identity, ServiceAccount cinema-staging/booking)"
  value       = module.booking_pod_identity.iam_role_arn
}

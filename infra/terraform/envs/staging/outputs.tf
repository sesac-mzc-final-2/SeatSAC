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
  description = "팀원은 --profile cinema-dev, 관리자는 cinema-admin"
  value       = "aws eks update-kubeconfig --region ${var.region} --name ${module.eks.cluster_name} --profile cinema-dev"
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

output "db_bootstrap_role_arn" {
  description = "3.1.12 DB 계정 생성 Job Pod Identity 역할 (cinema-ops/db-bootstrap, 마스터 Secret 읽기만)"
  value       = module.db_bootstrap_pod_identity.iam_role_arn
}

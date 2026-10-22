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

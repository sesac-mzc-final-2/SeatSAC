output "namespaces" {
  value = [for ns in kubernetes_namespace_v1.this : ns.metadata[0].name]
}

output "lb_controller_chart_version" {
  value = helm_release.aws_load_balancer_controller.version
}

output "developer_namespaces" {
  description = "팀원 port-forward Role이 있는 namespace"
  value       = sort(keys(kubernetes_role_v1.developer_port_forward))
}

output "db_secret_names" {
  description = "3.1.12 앱 namespace Secret 이름 (값은 출력하지 않는다)"
  value = {
    app      = sort([for s in kubernetes_secret_v1.app : s.metadata[0].name])
    migrator = sort([for s in kubernetes_secret_v1.migrator : s.metadata[0].name])
  }
}

output "db_bootstrap_job" {
  description = "마지막으로 실행된 DB 계정 생성 Job (kubectl -n cinema-ops logs job/<이름>)"
  value       = kubernetes_job_v1.db_bootstrap.metadata[0].name
}

output "infra_endpoints_keys" {
  description = "ConfigMap cinema-infra-endpoints 키 (값은 kubectl -n cinema-staging get cm cinema-infra-endpoints -o yaml)"
  value       = sort(keys(kubernetes_config_map_v1.infra_endpoints.data))
}

output "argocd_service_apps" {
  description = "Terraform이 만든 서비스 ArgoCD Application (argocd_apps_enabled = false면 빈 목록)"
  value       = sort([for a in kubernetes_manifest.argocd_service_app : a.manifest.metadata.name])
}

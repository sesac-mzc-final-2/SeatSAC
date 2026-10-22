output "namespaces" {
  value = [for ns in kubernetes_namespace_v1.this : ns.metadata[0].name]
}

output "lb_controller_chart_version" {
  value = helm_release.aws_load_balancer_controller.version
}

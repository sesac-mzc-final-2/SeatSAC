# 앱 namespace는 Terraform이 만든다: 3.1.12의 K8s Secret이 ArgoCD보다 먼저 들어가야 하므로.
# monitoring은 kube-prometheus-stack(ArgoCD, CreateNamespace=true)과 겹쳐도 문제없다 —
# 팀원 port-forward RBAC(Grafana·Prometheus)를 미리 걸기 위해 만든다.
resource "kubernetes_namespace_v1" "this" {
  for_each = toset(var.developer_namespaces)

  metadata {
    name = each.value
    labels = {
      "app.kubernetes.io/part-of"    = "cinema"
      "app.kubernetes.io/managed-by" = "terraform"
    }
  }
}

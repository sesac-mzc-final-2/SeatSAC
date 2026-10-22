# ------------------------------------------------------------------
# 팀원(cinema-developer) 추가 권한
#   Access Entry의 AmazonEKSViewPolicy(namespace 범위)는 조회·로그만 준다(Secret 제외).
#   port-forward(로컬에서 서비스·Grafana 확인)만 여기서 더한다.
#   Access Entry의 kubernetes_groups = ["cinema-developers"] 와 이름이 같아야 한다.
#   exec·delete·edit는 주지 않는다 (클러스터 직접 수정 금지, GitOps만)
# ------------------------------------------------------------------
resource "kubernetes_role_v1" "developer_port_forward" {
  for_each = kubernetes_namespace_v1.this

  metadata {
    name      = "cinema-developer-port-forward"
    namespace = each.value.metadata[0].name
  }

  rule {
    api_groups = [""]
    resources  = ["pods", "services"]
    verbs      = ["get", "list"]
  }

  rule {
    api_groups = [""]
    resources  = ["pods/portforward"]
    verbs      = ["create"]
  }
}

resource "kubernetes_role_binding_v1" "developer_port_forward" {
  for_each = kubernetes_role_v1.developer_port_forward

  metadata {
    name      = "cinema-developer-port-forward"
    namespace = each.value.metadata[0].namespace
  }

  role_ref {
    api_group = "rbac.authorization.k8s.io"
    kind      = "Role"
    name      = each.value.metadata[0].name
  }

  subject {
    kind      = "Group"
    name      = "cinema-developers"
    api_group = "rbac.authorization.k8s.io"
  }
}

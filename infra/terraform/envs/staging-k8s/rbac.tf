# ------------------------------------------------------------------
# 팀원(cinema-developer) 추가 권한
#   Access Entry의 AmazonEKSViewPolicy(namespace 범위)는 조회·로그만 준다(Secret 제외).
#   port-forward(로컬에서 서비스·Grafana 확인)만 여기서 더한다.
#   Access Entry의 kubernetes_groups = ["cinema-developers"] 와 이름이 같아야 한다.
#   exec·delete·edit는 주지 않는다 (클러스터 직접 수정 금지, GitOps만)
# ------------------------------------------------------------------
resource "kubernetes_role_v1" "developer_port_forward" {
  for_each = toset(var.developer_namespaces)

  metadata {
    name      = "cinema-developer-port-forward"
    namespace = try(kubernetes_namespace_v1.this[each.key].metadata[0].name, each.key) # 없으면 아래 precondition이 막는다
  }

  lifecycle {
    precondition {
      condition     = contains(var.namespaces, each.key)
      error_message = "developer_namespaces의 ${each.key}가 namespaces에 없다. namespace를 먼저 namespaces에 추가한다."
    }
    precondition {
      condition     = each.key != var.ops_namespace
      error_message = "${var.ops_namespace}는 관리자 전용(DB 계정 비밀번호 Secret)이라 developer_namespaces에 넣지 않는다."
    }
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

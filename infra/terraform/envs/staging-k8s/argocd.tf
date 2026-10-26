# ------------------------------------------------------------------
# 3.1.5 ArgoCD 설치 (OPS-02) — Terraform Helm (ADR-69: 클러스터 안 기반 리소스는 staging-k8s)
#   - 접속: port-forward만 (Ingress·LoadBalancer 없음). TLS는 port-forward 구간이라 server.insecure
#       kubectl -n argocd port-forward svc/argocd-server 8080:80   → http://localhost:8080
#   - 관리자: admin / 초기 비밀번호는 Secret argocd-initial-admin-secret (김종식·백업 관리자만)
#   - 팀원: 로그인 없이 읽기 전용(anonymous → role:readonly). port-forward 권한은 아래 Role
#   - SeatSAC-gitops는 Public → 레포 자격 증명을 등록하지 않는다 (ADR-74)
#   - dex·notifications 끔. 노드 2대(t3.large)라 requests를 작게
#   - App of Apps 대기: Application 리소스 health를 계산하게 해서(argocd 1.8부터 기본 꺼짐)
#     root 앱의 sync-wave(플랫폼 -10 → cinema-common -5)가 앞 앱이 Healthy가 될 때까지 기다리게 한다
# ------------------------------------------------------------------
resource "kubernetes_namespace_v1" "argocd" {
  metadata {
    name = var.argocd_namespace
    labels = {
      "app.kubernetes.io/part-of"    = "cinema"
      "app.kubernetes.io/managed-by" = "terraform"
    }
  }
}

locals {
  argocd_small = {
    requests = { cpu = "50m", memory = "128Mi" }
    limits   = { memory = "256Mi" }
  }
}

resource "helm_release" "argocd" {
  name       = "argocd"
  namespace  = kubernetes_namespace_v1.argocd.metadata[0].name
  repository = "https://argoproj.github.io/argo-helm"
  chart      = "argo-cd"
  version    = var.argocd_chart_version

  wait    = true
  timeout = 600

  values = [yamlencode({
    crds = { keep = true } # 실수로 uninstall해도 Application이 지워지지 않게(차트 기본값과 같음, 명시)

    configs = {
      params = {
        "server.insecure" = true # port-forward 전용
      }
      cm = {
        "admin.enabled"           = true
        "exec.enabled"            = false
        "users.anonymous.enabled" = true # 읽기 전용(아래 policy.default). 외부 노출 없음(port-forward만)
        "statusbadge.enabled"     = false
        "timeout.reconciliation"  = "60s" # build-push 태그 커밋 → 1분 안에 감지 (webhook 없음)

        "resource.customizations.health.argoproj.io_Application" = <<-LUA
          hs = {}
          hs.status = "Progressing"
          hs.message = ""
          if obj.status ~= nil then
            if obj.status.health ~= nil then
              hs.status = obj.status.health.status
              if obj.status.health.message ~= nil then
                hs.message = obj.status.health.message
              end
            end
          end
          return hs
        LUA
      }
      rbac = {
        "policy.default" = "role:readonly"
      }
    }

    dex           = { enabled = false }
    notifications = { enabled = false }

    controller     = { replicas = 1, resources = { requests = { cpu = "100m", memory = "384Mi" }, limits = { memory = "768Mi" } } }
    repoServer     = { replicas = 1, resources = { requests = { cpu = "50m", memory = "192Mi" }, limits = { memory = "512Mi" } } }
    server         = { replicas = 1, resources = local.argocd_small }
    applicationSet = { replicas = 1, resources = local.argocd_small }
    redis          = { resources = local.argocd_small }
  })]
}

# 팀원: Application 상태 조회 + ArgoCD 화면 port-forward (읽기 전용 UI). 수정·sync 권한 없음
resource "kubernetes_role_v1" "developer_argocd_view" {
  metadata {
    name      = "cinema-developer-argocd-view"
    namespace = kubernetes_namespace_v1.argocd.metadata[0].name
  }

  rule {
    api_groups = ["argoproj.io"]
    resources  = ["applications", "appprojects"]
    verbs      = ["get", "list", "watch"]
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

resource "kubernetes_role_binding_v1" "developer_argocd_view" {
  metadata {
    name      = "cinema-developer-argocd-view"
    namespace = kubernetes_namespace_v1.argocd.metadata[0].name
  }

  role_ref {
    api_group = "rbac.authorization.k8s.io"
    kind      = "Role"
    name      = kubernetes_role_v1.developer_argocd_view.metadata[0].name
  }

  subject {
    kind      = "Group"
    name      = "cinema-developers" # envs/staging Access Entry kubernetes_groups
    api_group = "rbac.authorization.k8s.io"
  }
}

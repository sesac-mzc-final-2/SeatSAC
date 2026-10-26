# ------------------------------------------------------------------
# ArgoCD Application: root(App of Apps) + 서비스 5개 — Helm 차트 argocd-apps (3.1.5)
#   - root(cinema-root): gitops apps/staging → AppProject cinema · argo-rollouts · kube-prometheus-stack · cinema-common
#   - 서비스(cinema-{gateway,catalog,booking,user,payment}): SeatSAC-gitops가 Public이라 ECR 레지스트리(계정 ID)를
#     그 레포에 두지 않는다(ADR-74). gitops에는 이미지 이름 cinema/{svc}와 태그(overlays/staging/{svc}/image-tag)만 있고
#     여기서 kustomize.images "cinema/{svc}=<레지스트리>/cinema/{svc}"로 레지스트리를 붙인다(태그는 gitops 값)
#   - Helm으로 만드는 이유: kubernetes_manifest는 plan 때 Application CRD가 있어야 해서 ArgoCD 설치와 한 번에
#     apply할 수 없다(3.1.4의 argocd_apps_enabled 단계 제거). Helm은 설치 순서(depends_on)만 지키면 된다
#   - gateway는 cognito_enabled = true일 때만: COGNITO_ISSUER_URI·CLIENT_ID가 빈 값이면 기동 실패(런타임 계약 3장)
#   - 이미지 태그는 Terraform이 관리하지 않는다(build-push → gitops → ArgoCD). 태그가 바뀌어도 plan 변경 없음
#   - finalizer: Application을 지우면 ArgoCD가 만든 리소스(gateway Ingress → ALB 포함)도 지운다
#     삭제 순서는 런북 11장: 이 릴리스를 먼저 destroy(-target) → Application·ALB가 사라진 뒤 나머지
# ------------------------------------------------------------------
locals {
  argocd_services = toset(concat(
    var.cognito_enabled ? ["gateway"] : [],
    ["catalog", "booking", "user", "payment"],
  ))

  # 플랫폼 CRD(Rollout·PodMonitor)·cinema-infra-endpoints보다 먼저 sync되면 실패 → 재시도로 맞춘다 (최대 약 20분)
  argocd_sync_policy = {
    automated = { prune = true, selfHeal = true }
    retry = {
      limit   = 10
      backoff = { duration = "10s", factor = 2, maxDuration = "3m" }
    }
  }

  argocd_labels = {
    "app.kubernetes.io/part-of"    = "cinema"
    "app.kubernetes.io/managed-by" = "terraform"
  }

  argocd_root_app = {
    "cinema-root" = {
      namespace        = var.argocd_namespace
      additionalLabels = local.argocd_labels
      finalizers       = ["resources-finalizer.argocd.argoproj.io"]
      project          = "default"
      source = {
        repoURL        = var.gitops_repo_url
        targetRevision = "main"
        path           = "apps/staging"
      }
      destination = {
        server    = "https://kubernetes.default.svc"
        namespace = var.argocd_namespace
      }
      syncPolicy = local.argocd_sync_policy
    }
  }

  argocd_service_apps = {
    for s in local.argocd_services : "cinema-${s}" => {
      namespace        = var.argocd_namespace
      additionalLabels = local.argocd_labels
      finalizers       = ["resources-finalizer.argocd.argoproj.io"]
      project          = "cinema" # gitops apps/staging/project.yaml (root가 만든다)
      source = {
        repoURL        = var.gitops_repo_url
        targetRevision = "main"
        path           = "overlays/staging/${s}"
        kustomize = {
          images = ["cinema/${s}=${local.ecr_registry}/cinema/${s}"]
        }
      }
      destination = {
        server    = "https://kubernetes.default.svc"
        namespace = var.app_namespace
      }
      syncPolicy = local.argocd_sync_policy
    }
  }
}

resource "helm_release" "argocd_apps" {
  name       = "argocd-apps"
  namespace  = kubernetes_namespace_v1.argocd.metadata[0].name
  repository = "https://argoproj.github.io/argo-helm"
  chart      = "argocd-apps"
  version    = var.argocd_apps_chart_version

  wait    = true
  timeout = 300

  values = [yamlencode({
    applications = merge(local.argocd_root_app, local.argocd_service_apps)
  })]

  depends_on = [
    helm_release.argocd,                       # CRD·컨트롤러
    helm_release.aws_load_balancer_controller, # gateway Ingress → ALB. 삭제 때도 ALB를 먼저 정리하게
    kubernetes_config_map_v1.infra_endpoints,  # 앱 envFrom
    kubernetes_secret_v1.app,                  # {svc}-secrets
    kubernetes_secret_v1.migrator,             # Flyway Job
    kubernetes_config_map_v1.rds_ca,           # Flyway TLS
  ]
}

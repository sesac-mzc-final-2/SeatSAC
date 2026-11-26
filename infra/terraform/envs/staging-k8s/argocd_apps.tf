# ------------------------------------------------------------------
# ArgoCD Application: root(App of Apps) + 서비스 5개 — Helm 차트 argocd-apps (3.1.5) · 🆕 ops-api(6.1.7, ops_api_enabled)
#   - root(cinema-root): gitops apps/staging → AppProject cinema · argo-rollouts · kube-prometheus-stack · cinema-common
#   - 서비스(cinema-{gateway,catalog,booking,user,payment}): SeatSAC-gitops가 Public이라 ECR 레지스트리(계정 ID)를
#     그 레포에 두지 않는다(ADR-74). gitops에는 이미지 이름 cinema/{svc}와 태그(overlays/staging/{svc}/image-tag)만 있고
#     여기서 kustomize.images "cinema/{svc}=<레지스트리>/cinema/{svc}"로 레지스트리를 붙인다(태그는 gitops 값)
#   - Helm으로 만드는 이유: kubernetes_manifest는 plan 때 Application CRD가 있어야 해서 ArgoCD 설치와 한 번에
#     apply할 수 없다(3.1.4의 argocd_apps_enabled 단계 제거). Helm은 설치 순서(depends_on)만 지키면 된다
#   - gateway는 cognito_enabled = true일 때만: COGNITO_ISSUER_URI·CLIENT_ID가 빈 값이면 기동 실패(런타임 계약 3장)
#   - ⚠️AI ai(cinema-ai)는 ai_enabled = true일 때만 (3.1.18, ADR-86). 다른 Application은 그대로다.
#     ng-ai가 평소 0대라 파드는 Pending → Health는 처음 10분 Progressing, 그 뒤 Degraded(Deployment
#     progressDeadlineSeconds 기본 600초 초과)가 정상. ng-ai를 켜고 파드가 뜨면 Healthy로 돌아온다
#   - 이미지 태그는 Terraform이 관리하지 않는다(build-push → gitops → ArgoCD). 태그가 바뀌어도 plan 변경 없음
#   - finalizer: Application을 지우면 ArgoCD가 만든 리소스(gateway Ingress → ALB 포함)도 지운다
#     삭제 순서는 런북 11장: 이 릴리스를 먼저 destroy(-target) → Application·ALB가 사라진 뒤 나머지
# ------------------------------------------------------------------
locals {
  argocd_services = toset(concat(
    var.cognito_enabled ? ["gateway"] : [],
    ["catalog", "booking", "user", "payment"],
    var.ai_enabled ? ["ai"] : [],
    var.ops_api_enabled ? ["ops-api"] : [], # 🆕 6.1.7 (ADR-119)
  ))

  # 🆕 6.1.7 ops-api 정지/가동(ADR-119): ops-api가 */scale로 바꾼 replicas는 Git과 다르다(의도된 GitOps 예외).
  #   서비스 Application이 /spec/replicas를 비교하지 않고(Synced 유지) sync 때도 덮어쓰지 않게(RespectIgnoreDifferences)
  #   - HPA가 있는 서비스는 Git에 replicas가 없어 원래 차이가 없다 — 고정 replicas(gateway·payment·ai 등)에 효과가 있다
  #   - 부작용: Git의 replicas를 바꾸는 PR도 그 Deployment·Rollout에는 적용되지 않는다 → 런북 6-L 4-1)(관리자 수동 sync)
  #   - ops_api_enabled = true일 때만 붙인다(리뷰 M1) — 정지/가동이 없으면 replicas를 무시할 이유가 없고, 다른 일로
  #     staging-k8s를 apply할 때 미리 붙어 replicas 변경 PR이 조용히 빠지는 것을 막는다
  argocd_ignore_replicas = [
    { group = "apps", kind = "Deployment", jsonPointers = ["/spec/replicas"] },
    { group = "argoproj.io", kind = "Rollout", jsonPointers = ["/spec/replicas"] },
  ]
  # ops-api가 정지 전 replicas를 적는 ConfigMap — data는 ops-api만 쓴다(Git에는 빈 data)
  argocd_ignore_ops_state = [
    { group = "", kind = "ConfigMap", name = "ops-api-state", jsonPointers = ["/data"] },
  ]

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
      ignoreDifferences = var.ops_api_enabled ? concat(local.argocd_ignore_replicas, s == "ops-api" ? local.argocd_ignore_ops_state : []) : []
      syncPolicy        = var.ops_api_enabled ? merge(local.argocd_sync_policy, { syncOptions = ["RespectIgnoreDifferences=true"] }) : local.argocd_sync_policy
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
    kubernetes_secret_v1.ai,                   # ⚠️AI ai-secrets (ai_enabled일 때만 생김)
  ]
}

# ------------------------------------------------------------------
# 서비스 ArgoCD Application 5개 (cinema-{gateway,catalog,booking,user,payment})
#   SeatSAC-gitops가 Public이라 ECR 레지스트리 주소(계정 ID)를 그 레포에 두지 않는다(ADR-66).
#   gitops에는 이미지 이름 cinema/{svc}와 태그(overlays/staging/{svc}/image-tag)만 있고,
#   여기서 kustomize.images "cinema/{svc}=<레지스트리>/cinema/{svc}"로 레지스트리를 붙인다(태그는 gitops 값 유지).
#   플랫폼·cinema-common·AppProject는 그대로 gitops apps/staging(App of Apps)
#
#   kubernetes_manifest는 plan 때 Application CRD가 있어야 한다 → ArgoCD 설치(3.1.5)와 root 앱 sync 뒤
#   argocd_apps_enabled = true 로 바꿔 apply (런북 6-B). 그 전에는 아무것도 만들지 않는다
#   이미지 태그는 Terraform이 관리하지 않는다(build-push → gitops 커밋 → ArgoCD). 그래서 태그가 바뀌어도 plan 변경 없음
# ------------------------------------------------------------------
locals {
  argocd_services = toset(["gateway", "catalog", "booking", "user", "payment"])
}

resource "kubernetes_manifest" "argocd_service_app" {
  for_each = var.argocd_apps_enabled ? local.argocd_services : toset([])

  manifest = {
    apiVersion = "argoproj.io/v1alpha1"
    kind       = "Application"
    metadata = {
      name      = "cinema-${each.key}"
      namespace = var.argocd_namespace
      labels = {
        "app.kubernetes.io/part-of"    = "cinema"
        "app.kubernetes.io/managed-by" = "terraform"
      }
    }
    spec = {
      project = "cinema" # gitops apps/staging/project.yaml
      source = {
        repoURL        = var.gitops_repo_url
        targetRevision = "main"
        path           = "overlays/staging/${each.key}"
        kustomize = {
          images = ["cinema/${each.key}=${local.ecr_registry}/cinema/${each.key}"]
        }
      }
      destination = {
        server    = "https://kubernetes.default.svc"
        namespace = var.app_namespace
      }
      syncPolicy = {
        automated = { prune = true, selfHeal = true }
        # Rollout CRD(argo-rollouts)·cinema-infra-endpoints가 먼저 있어야 한다. 순서를 못 맞춘 첫 sync는 재시도로 맞춘다
        retry = {
          limit   = 5
          backoff = { duration = "10s", factor = 2, maxDuration = "3m" }
        }
      }
    }
  }

  depends_on = [kubernetes_config_map_v1.infra_endpoints]
}

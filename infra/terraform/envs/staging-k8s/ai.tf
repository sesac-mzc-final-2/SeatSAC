# ------------------------------------------------------------------
# ⚠️AI 3.1.18 Secret ai-secrets (cinema-staging) — ANTHROPIC_API_KEY (ADR-87, 런타임 계약 9-1)
#   - 값은 sensitive 변수 TF_VAR_anthropic_api_key로만 받는다. tfvars·셸 기록·문서·PR에 쓰지 않는다
#     read -s TF_VAR_anthropic_api_key; export TF_VAR_anthropic_api_key   (apply가 끝나면 unset)
#   - 키가 없으면 plan이 멈춘다: 다른 변경 때문에 apply하다가 빈 키로 Secret을 덮어쓰는 사고를 막는다
#   - 키를 바꾸면 이 폴더를 다시 apply → ArgoCD에서 cinema-ai Restart(파드는 Secret 변경을 자동으로 다시 읽지 않는다)
#   - Anthropic 월 지출 한도 $20은 Anthropic 콘솔에서(AWS Budgets 밖). 발표 후 Public 전환 전 키 폐기
# ------------------------------------------------------------------
resource "kubernetes_secret_v1" "ai" {
  count = var.ai_enabled ? 1 : 0

  metadata {
    name      = "ai-secrets"
    namespace = kubernetes_namespace_v1.this[var.app_namespace].metadata[0].name
    labels = {
      "app.kubernetes.io/name"       = "ai"
      "app.kubernetes.io/part-of"    = "cinema"
      "app.kubernetes.io/managed-by" = "terraform"
    }
  }

  data = {
    ANTHROPIC_API_KEY = var.anthropic_api_key
  }

  lifecycle {
    precondition {
      condition     = var.anthropic_api_key != null && try(length(trimspace(var.anthropic_api_key)) > 0, false)
      error_message = "ai_enabled = true면 TF_VAR_anthropic_api_key가 필요하다 (read -s TF_VAR_anthropic_api_key; export TF_VAR_anthropic_api_key). 파일에 쓰지 않는다"
    }
  }
}

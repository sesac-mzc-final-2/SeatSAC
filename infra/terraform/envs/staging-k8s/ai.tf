# ------------------------------------------------------------------
# ⚠️AI 3.1.18 Secret ai-secrets (cinema-staging) — ANTHROPIC_API_KEY (ADR-83, 런타임 계약 9-1)
#   - 값은 sensitive 변수 TF_VAR_anthropic_api_key로만 받는다. tfvars·셸 기록·문서·PR에 쓰지 않는다
#   - 키는 **처음 만들 때와 교체할 때만** 필요하다(ignore_changes = [data]). 평소 이 폴더의 plan·apply에는 키가 없어도 된다
#     → 백업 관리자도 키 없이 긴급 변경 가능, 다른 변경 때문에 apply하다가 Secret을 덮어쓰는 일도 없다
#   - 처음 만들 때 키가 없으면 plan이 멈춘다(아래 data + precondition: Secret이 아직 없을 때만 키를 요구)
#   - 교체: 새 키를 팀 Anthropic 계정에서 발급 → apply -replace='kubernetes_secret_v1.ai[0]' → ArgoCD cinema-ai Restart (런북 6-G-1)
#     ⚠ 교체 때는 Secret이 이미 있어 아래 precondition이 빈 키를 막지 못한다(HCL로는 생성·교체와 평소 plan을 구분할 수 없다).
#       런북 6-G-1의 키 확인 + apply 한 줄 명령으로만 교체하고, 끝나면 값 길이(wc -c)가 0보다 큰지 확인한다
#     키는 어디에도 보관하지 않는다(사람 사이에 전달하지 않는다). 잃어버리면 새로 발급해 교체한다
#   - Anthropic 월 지출 한도 $20은 Anthropic 콘솔에서(AWS Budgets 밖). 발표 후 Public 전환 전 키 폐기
# ------------------------------------------------------------------

# Secret이 이미 있는지 (없을 때만 키를 요구하려고). 목록 조회라 없으면 빈 목록 — 오류가 나지 않는다
data "kubernetes_resources" "ai_secret" {
  count = var.ai_enabled ? 1 : 0

  api_version    = "v1"
  kind           = "Secret"
  namespace      = var.app_namespace
  field_selector = "metadata.name=ai-secrets"
}

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
    # 만든 뒤에는 Terraform이 값을 다시 쓰지 않는다. 바꿀 때는 -replace로 명시 (런북 6-G-1 「키 교체」)
    ignore_changes = [data]

    precondition {
      condition = (
        try(length(trimspace(var.anthropic_api_key)) > 0, false)
        || length(data.kubernetes_resources.ai_secret[0].objects) > 0
      )
      error_message = "ai-secrets를 처음 만들려면 TF_VAR_anthropic_api_key가 필요하다 (read -s TF_VAR_anthropic_api_key && export TF_VAR_anthropic_api_key). 파일에 쓰지 않는다"
    }
  }
}

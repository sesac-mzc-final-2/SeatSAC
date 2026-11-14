# ------------------------------------------------------------------
# ADR-112 포트원(PortOne) V2 토스페이 결제 — Secret 키 3개 · ConfigMap 키 2개 (런타임 계약 3장)
#
#   payment-secrets  APP_PORTONE_API_SECRET      포트원 콘솔 V2 API Secret         ← TF_VAR_portone_api_secret (sensitive)
#                    APP_PORTONE_WEBHOOK_SECRET  포트원 콘솔 웹훅 시크릿(whsec_…)   ← TF_VAR_portone_webhook_secret (sensitive)
#   booking-secrets  APP_PAYMENT_MOCK_KEY        모의 결제(CARD) 허용 키 — random_password, 사람이 볼 일 없음
#                                                (k6 Job·블루/그린 분석 Job이 secretKeyRef로 받아 X-Payment-Mock-Key로 보낸다)
#   cinema-infra-endpoints  PORTONE_STORE_ID · PORTONE_CHANNEL_KEY_TOSSPAY  포트원 공개 값(결제창에 노출)
#                    ← TF_VAR_portone_store_id · TF_VAR_portone_channel_key_tosspay
#                    gitops가 Public이라 외부 계정 식별자를 그 레포에 두지 않는다 → payment-config는 ${PORTONE_STORE_ID}만
#
#   값은 **처음 넣을 때와 바꿀 때만** 변수로 준다. 변수가 비어 있으면 클러스터에 이미 있는 값을 그대로 쓴다(아래 data)
#   → 백업 관리자가 값 없이 다른 변경을 apply해도 Secret·ConfigMap이 빈 값으로 덮이지 않는다
#   값을 비우는 방법은 없다(필요하면 포트원 콘솔에서 키를 폐기). 비밀 값은 tfvars·셸 기록·문서·PR에 쓰지 않는다 (런북 6-I)
# ------------------------------------------------------------------

# 지금 클러스터에 있는 값 (목록 조회라 없으면 빈 목록 — 오류가 나지 않는다)
data "kubernetes_resources" "payment_secret" {
  api_version    = "v1"
  kind           = "Secret"
  namespace      = var.app_namespace
  field_selector = "metadata.name=payment-secrets"
}

data "kubernetes_resources" "infra_endpoints" {
  api_version    = "v1"
  kind           = "ConfigMap"
  namespace      = var.app_namespace
  field_selector = "metadata.name=cinema-infra-endpoints"
}

resource "random_password" "payment_mock_key" {
  length  = 32
  special = false
}

locals {
  payment_secret_current  = try(data.kubernetes_resources.payment_secret.objects[0].data, {}) # Secret data는 base64
  infra_endpoints_current = try(data.kubernetes_resources.infra_endpoints.objects[0].data, {})

  # 변수 → 없으면 지금 값 → 없으면 빈 값(payment는 기동되고 APP_PORTONE_ENABLED=true일 때 verify·취소가 503 PORTONE_UNAVAILABLE)
  portone_api_secret = sensitive(try(coalesce(
    var.portone_api_secret, try(base64decode(local.payment_secret_current["APP_PORTONE_API_SECRET"]), null)
  ), ""))
  portone_webhook_secret = sensitive(try(coalesce(
    var.portone_webhook_secret, try(base64decode(local.payment_secret_current["APP_PORTONE_WEBHOOK_SECRET"]), null)
  ), ""))

  # db_bootstrap.tf kubernetes_secret_v1.app에 서비스별로 더하는 키
  app_secret_extra = {
    booking = { APP_PAYMENT_MOCK_KEY = random_password.payment_mock_key.result }
    payment = {
      APP_PORTONE_API_SECRET     = local.portone_api_secret
      APP_PORTONE_WEBHOOK_SECRET = local.portone_webhook_secret
    }
  }

  # infra_endpoints.tf cinema-infra-endpoints에 더하는 키 (비밀 아님)
  portone_endpoints = {
    PORTONE_STORE_ID            = try(coalesce(var.portone_store_id, try(local.infra_endpoints_current["PORTONE_STORE_ID"], null)), "")
    PORTONE_CHANNEL_KEY_TOSSPAY = try(coalesce(var.portone_channel_key_tosspay, try(local.infra_endpoints_current["PORTONE_CHANNEL_KEY_TOSSPAY"], null)), "")
  }
}

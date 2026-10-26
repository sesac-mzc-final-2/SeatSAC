# ------------------------------------------------------------------
# ConfigMap cinema-infra-endpoints (cinema-staging) — 앱이 붙는 AWS 엔드포인트
#   SeatSAC-gitops가 Public이라 엔드포인트·계정 ID를 그 레포에 두지 않는다(ADR-66).
#   gitops의 {svc}-config는 값 대신 ${DB_HOST} 같은 자리만 두고, 파드가 이 ConfigMap을 envFrom으로 같이 받아
#   Spring이 기동 때 치환한다. Flyway 훅 Job은 env DB_HOST(configMapKeyRef) → FLYWAY_URL의 $(DB_HOST)
#   키 이름은 docs/service-runtime-contract.md 3장. 비밀 값이 아니다(비밀번호는 {svc}-secrets)
#   값이 바뀌면(RDS 재생성 등) 다음 apply에서 ConfigMap만 바뀐다 → 파드는 자동 재시작되지 않으니
#   ArgoCD에서 해당 앱 Restart(런북 6-B)
# ------------------------------------------------------------------
data "aws_caller_identity" "current" {}

data "aws_elasticache_replication_group" "valkey" {
  replication_group_id = var.valkey_replication_group_id
}

# Cognito(3.3.x)는 아직 없다 → cognito_enabled = true로 바꾸기 전까지 COGNITO_* 는 빈 값(gateway는 기동 실패가 정상)
data "aws_cognito_user_pools" "this" {
  count = var.cognito_enabled ? 1 : 0
  name  = var.cognito_user_pool_name
}

data "aws_cognito_user_pool_clients" "this" {
  count        = var.cognito_enabled ? 1 : 0
  user_pool_id = one(data.aws_cognito_user_pools.this[0].ids)
}

locals {
  account_id   = data.aws_caller_identity.current.account_id
  ecr_registry = "${local.account_id}.dkr.ecr.${var.region}.amazonaws.com"

  cognito_pool_id   = var.cognito_enabled ? one(data.aws_cognito_user_pools.this[0].ids) : ""
  cognito_client_id = var.cognito_enabled ? one(data.aws_cognito_user_pool_clients.this[0].client_ids) : ""

  infra_endpoints = {
    DB_HOST     = data.aws_db_instance.mysql.address
    VALKEY_HOST = data.aws_elasticache_replication_group.valkey.primary_endpoint_address
    # 큐 URL은 이름으로 정해진다 → 3.1.8 큐가 생기기 전에도 값을 둘 수 있다(그 전에는 booking 발행만 실패)
    SQS_NOTIFICATION_URL = "https://sqs.${var.region}.amazonaws.com/${local.account_id}/${var.notification_queue_name}"
    COGNITO_ISSUER_URI   = var.cognito_enabled ? "https://cognito-idp.${var.region}.amazonaws.com/${local.cognito_pool_id}" : ""
    COGNITO_CLIENT_ID    = local.cognito_client_id
  }
}

resource "kubernetes_config_map_v1" "infra_endpoints" {
  metadata {
    name      = "cinema-infra-endpoints"
    namespace = kubernetes_namespace_v1.this[var.app_namespace].metadata[0].name
    labels = {
      "app.kubernetes.io/part-of"    = "cinema"
      "app.kubernetes.io/managed-by" = "terraform"
    }
  }

  data = local.infra_endpoints

  lifecycle {
    precondition {
      condition     = !var.cognito_enabled || length(local.cognito_client_id) > 0
      error_message = "cognito_enabled = true인데 사용자 풀 ${var.cognito_user_pool_name}의 앱 클라이언트를 찾지 못했다 (클라이언트는 하나여야 한다)"
    }
  }
}

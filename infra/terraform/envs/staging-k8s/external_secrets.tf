# ------------------------------------------------------------------
# 6.1.4 Secrets Manager cinema/staging/* — External Secrets가 읽는 원본 (ADR-123)
#   이름(conventions 9장):
#     cinema/staging/{svc}            → K8s Secret {svc}-secrets           (앱: SPRING_DATASOURCE_PASSWORD + 서비스별 키)
#     cinema/staging/{svc}-migrator   → K8s Secret {svc}-migrator-secrets  (Flyway Job: FLYWAY_USER·FLYWAY_PASSWORD)
#   값은 키 = 값 JSON 한 덩어리 → gitops ExternalSecret이 dataFrom.extract로 그대로 푼다(키 이름이 K8s Secret과 같다)
#
#   옮기는 순서(런북 8-F) — 확장 → 전환 → 정리:
#     1) 이 파일: Terraform이 지금 만드는 K8s Secret(db_bootstrap.tf)과 **같은 값**을 Secrets Manager에도 넣는다.
#        K8s Secret은 그대로다 → 서비스에 영향 없음
#     2) gitops: ESO · ClusterSecretStore · ExternalSecret(같은 이름·같은 키) → ESO가 같은 값을 쓴다
#     3) 별도 PR: db_bootstrap.tf의 kubernetes_secret_v1을 state에서만 빼고(removed, 지우지 않음) 값의 출처를 여기로 옮긴다
#   값의 출처를 kubernetes_secret_v1.*.data로 둔 이유: 1)~2) 동안 두 곳의 값이 어긋날 수 없고,
#     db_bootstrap.tf에 서비스가 늘면(coupon 등) 여기도 같이 늘어난다
#   ai-secrets(ANTHROPIC_API_KEY)는 넣지 않았다 — ai.tf가 값을 다시 쓰지 않게(ignore_changes) 돼 있어 state의 값과
#     설정의 값이 다를 수 있다. ai DB 비밀번호 키 이름이 정해질 때(런타임 계약 9-1 ⏳) 함께 옮긴다
#   비밀 값은 이 state(staging-k8s, 관리자만 읽음)에 이미 있는 것과 같다. 값은 plan·출력에 (sensitive)로만 보여야 한다
# ------------------------------------------------------------------

locals {
  sm_prefix = "cinema/${var.env}" # cinema/staging

  # K8s Secret 이름 → Secrets Manager 이름:  booking-secrets → cinema/staging/booking · booking-migrator-secrets → cinema/staging/booking-migrator
  sm_secrets = merge(
    { for svc, s in kubernetes_secret_v1.app : svc => { k8s_secret = s.metadata[0].name, kind = "app", svc = svc } },
    { for svc, s in kubernetes_secret_v1.migrator : "${svc}-migrator" => { k8s_secret = s.metadata[0].name, kind = "migrator", svc = svc } },
  )
}

resource "aws_secretsmanager_secret" "app" {
  for_each = local.sm_secrets

  name        = "${local.sm_prefix}/${each.key}"
  description = "SeatSAC ${var.env} ${each.key} — External Secrets가 K8s Secret ${each.value.k8s_secret}으로 동기화 (Terraform 관리, 콘솔에서 고치지 않는다)"

  # 재생성 리허설(OPS-14)에서 같은 이름으로 바로 다시 만들 수 있게 삭제 대기 없음. 값은 Terraform이 다시 만든다
  recovery_window_in_days = 0

  tags = { Name = "${local.sm_prefix}/${each.key}" }
}

resource "aws_secretsmanager_secret_version" "app" {
  for_each = local.sm_secrets

  secret_id = aws_secretsmanager_secret.app[each.key].id
  secret_string = jsonencode(
    each.value.kind == "app" ? kubernetes_secret_v1.app[each.value.svc].data : kubernetes_secret_v1.migrator[each.value.svc].data
  )
}

output "secrets_manager_names" {
  description = "6.1.4 Secrets Manager 이름 (값은 출력하지 않는다). gitops ExternalSecret의 remoteRef key"
  value       = sort([for s in aws_secretsmanager_secret.app : s.name])
}

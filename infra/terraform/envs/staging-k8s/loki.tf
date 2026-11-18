# ------------------------------------------------------------------
# 3.1.24 ConfigMap loki-storage (monitoring) — Loki가 쓸 S3 버킷 이름
#   버킷 이름에 계정 ID가 들어가 Public gitops에 쓸 수 없다(ADR-74). Loki 파드가 envFrom으로 받고
#   -config.expand-env=true로 설정의 ${LOKI_S3_BUCKET}을 치환한다(gitops platform/loki/values.yaml).
#   버킷·Pod Identity는 envs/staging/loki.tf — 이름 규칙이 같아 그 state를 읽지 않고 같은 식으로 만든다
#   비밀 값이 아니다(자격 증명은 Pod Identity)
# ------------------------------------------------------------------
resource "kubernetes_config_map_v1" "loki_storage" {
  metadata {
    name      = "loki-storage"
    namespace = kubernetes_namespace_v1.this["monitoring"].metadata[0].name
    labels = {
      "app.kubernetes.io/part-of"    = "cinema"
      "app.kubernetes.io/managed-by" = "terraform"
    }
  }

  data = {
    LOKI_S3_BUCKET = "cinema-${var.env}-loki-${local.account_id}" # envs/staging aws_s3_bucket.loki (var.name = cinema-staging)
    AWS_REGION     = var.region
  }
}

# ------------------------------------------------------------------
# 6.1.9 ConfigMap report-export (monitoring) — 내보내기 CronJob이 쓸 리포트 버킷 이름 (ADR-121)
#   버킷 이름에 계정 ID가 들어가 Public gitops에 쓸 수 없다(ADR-74) → loki-storage와 같은 방식으로 Terraform이 넘긴다.
#   CronJob(gitops observability/report-export)이 envFrom으로 받는다. 버킷·Pod Identity는 envs/staging/reports.tf —
#   이름 규칙이 같아 그 state를 읽지 않고 같은 식으로 만든다. 비밀 값이 아니다(자격 증명은 Pod Identity)
#   cinema-staging의 cinema-infra-endpoints REPORTS_BUCKET(ops-api용)과 같은 값 — CronJob은 monitoring에 있어 그 ConfigMap을 읽지 못한다
# ------------------------------------------------------------------
resource "kubernetes_config_map_v1" "report_export" {
  metadata {
    name      = "report-export"
    namespace = kubernetes_namespace_v1.this["monitoring"].metadata[0].name
    labels = {
      "app.kubernetes.io/part-of"    = "cinema"
      "app.kubernetes.io/managed-by" = "terraform"
    }
  }

  data = {
    REPORTS_BUCKET = "cinema-${var.env}-reports-${local.account_id}" # envs/staging aws_s3_bucket.reports (var.name = cinema-staging)
    AWS_REGION     = var.region
  }
}

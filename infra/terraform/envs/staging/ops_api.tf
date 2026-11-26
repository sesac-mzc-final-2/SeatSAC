# ------------------------------------------------------------------
# 6.1.7 ops-api Pod Identity (ADR-119·121) — cinema-staging-ops-api-pod (conventions 9장)
#   - 리포트(OPS-22): Athena 작업 그룹 cinema-staging-reports에서 쿼리 시작·조회, Glue 테이블 읽기,
#     리포트 버킷 cinema-staging-reports-{계정ID} 읽기(errors/ 파티션 목록·결과 CSV presigned URL) +
#     Athena 쿼리 결과 쓰기(athena-results/ — Athena는 호출자 권한으로 결과를 쓴다)
#   - 버킷·Glue·작업 그룹은 6.1.8·6.1.9가 만든다. 여기는 **이름으로 정해지는 ARN**만 쓰므로 그 전에 apply해도 된다
#     (그동안 리포트 API는 503 REPORT_UNAVAILABLE — 정상). 이름을 바꾸면 이 파일과 conventions 9장을 같이 바꾼다
#   - Kubernetes 권한(scale·읽기)은 AWS가 아니라 gitops base/ops-api/rbac.yaml(Role·ClusterRole)
#   - ServiceAccount cinema-staging/ops-api (gitops base/ops-api/serviceaccount.yaml)
# ------------------------------------------------------------------
locals {
  reports_bucket_name   = "${var.name}-reports-${local.account_id}" # cinema-staging-reports-{계정ID}
  reports_workgroup     = "${var.name}-reports"                     # cinema-staging-reports
  reports_glue_database = replace("${var.name}-reports", "-", "_")  # cinema_staging_reports (ops-api OPS_REPORT_DATABASE 기본값)
}

module "ops_api_pod_identity" {
  source  = "terraform-aws-modules/eks-pod-identity/aws"
  version = "~> 2.0"

  name            = "${var.name}-ops-api-pod" # cinema-staging-ops-api-pod
  use_name_prefix = false

  attach_custom_policy      = true
  custom_policy_description = "6.1.7 ops-api reports (ADR-121): Athena workgroup, Glue table read, reports bucket"
  policy_statements = [
    {
      sid = "AthenaReportQueries"
      actions = [
        "athena:StartQueryExecution",
        "athena:GetQueryExecution",
        "athena:GetQueryResults",
        "athena:GetQueryRuntimeStatistics",
        "athena:StopQueryExecution",
        "athena:GetWorkGroup",
      ]
      resources = ["arn:aws:athena:${var.region}:${local.account_id}:workgroup/${local.reports_workgroup}"]
    },
    {
      sid     = "GlueReportTable"
      actions = ["glue:GetDatabase", "glue:GetTable", "glue:GetPartition", "glue:GetPartitions"]
      resources = [
        "arn:aws:glue:${var.region}:${local.account_id}:catalog",
        "arn:aws:glue:${var.region}:${local.account_id}:database/${local.reports_glue_database}",
        "arn:aws:glue:${var.region}:${local.account_id}:table/${local.reports_glue_database}/*",
      ]
    },
    {
      sid       = "ReportBucketLocation"
      actions   = ["s3:GetBucketLocation"]
      resources = ["arn:aws:s3:::${local.reports_bucket_name}"]
    },
    {
      # 목록은 리포트 원본(errors/)·Athena 결과(athena-results/) 아래만 — 버킷 전체 목록은 보지 않는다(리뷰 Minor 3)
      sid       = "ListReportPrefixes"
      actions   = ["s3:ListBucket"]
      resources = ["arn:aws:s3:::${local.reports_bucket_name}"]
      condition = [{
        test     = "StringLike"
        variable = "s3:prefix"
        values   = ["errors/*", "athena-results/*"]
      }]
    },
    {
      sid       = "ReadReports"
      actions   = ["s3:GetObject"]
      resources = ["arn:aws:s3:::${local.reports_bucket_name}/errors/*", "arn:aws:s3:::${local.reports_bucket_name}/athena-results/*"]
    },
    {
      sid       = "WriteAthenaResults"
      actions   = ["s3:PutObject", "s3:AbortMultipartUpload"]
      resources = ["arn:aws:s3:::${local.reports_bucket_name}/athena-results/*"]
    },
  ]

  associations = {
    this = {
      cluster_name    = module.eks.cluster_name
      namespace       = var.app_namespace
      service_account = "ops-api"
    }
  }
}

output "ops_api_pod_role_arn" {
  description = "6.1.7 cinema-staging-ops-api-pod (Pod Identity, ServiceAccount cinema-staging/ops-api)"
  value       = module.ops_api_pod_identity.iam_role_arn
}

# ------------------------------------------------------------------
# 6.1.8·6.1.9 오류·보안 리포트 (ADR-121) — 버킷 · Glue 테이블 · Athena 작업 그룹 · 내보내기 Pod Identity
#   흐름: 서비스 로그(errorCategory 있는 줄) → Loki → 내보내기 CronJob(1시간, gitops observability/report-export)
#         → S3 errors/dt=YYYY-MM-DD/HH.json.gz (KST 날짜·시) → Glue 테이블 errors(partition projection) → ops-api가 Athena로 날짜별 CSV
#   이름은 ops_api.tf locals와 같다(ops-api 정책이 이미 이 이름 ARN을 쓴다): 버킷 cinema-staging-reports-{계정ID} ·
#     작업 그룹 cinema-staging-reports · Glue DB cinema_staging_reports · 테이블 errors(ops-api OPS_REPORT_TABLE 기본값)
#   열은 ops-api reports.py의 CSV 열과 같다 — timestamp·service·level·errorcategory·status·path·traceid·message.
#     IP·토큰·userId·질문 원문은 내보내기 단계에서 버린다(이 테이블에 그 열이 없다)
#   보관: errors/ 30일(ADR-121) · athena-results/ 7일(presigned URL 5분·ops-api 캐시 1시간이면 충분)
#   비용: S3 몇 MB · Athena 스캔 1TB당 $5(하루 파일 몇 MB) — 거의 0
# ------------------------------------------------------------------
resource "aws_s3_bucket" "reports" {
  bucket        = local.reports_bucket_name # conventions 9장 cinema-staging-reports-{계정ID}
  force_destroy = true                      # 원본은 Loki 로그에서 다시 만들 수 있는 사본·쿼리 결과뿐

  tags = { Name = "${var.name}-reports" }
}

resource "aws_s3_bucket_public_access_block" "reports" {
  bucket = aws_s3_bucket.reports.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "reports" {
  bucket = aws_s3_bucket.reports.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "reports" {
  bucket = aws_s3_bucket.reports.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256" # 로그에 traceId·경로가 있다. Athena 결과도 같은 버킷이라 SSE_S3로 맞춘다(작업 그룹 encryption_configuration)
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "reports" {
  bucket = aws_s3_bucket.reports.id

  rule {
    id     = "expire-errors"
    status = "Enabled"
    filter {
      prefix = "errors/"
    }
    expiration {
      days = 30 # ADR-121 보관 30일
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }

  rule {
    id     = "expire-athena-results"
    status = "Enabled"
    filter {
      prefix = "athena-results/"
    }
    expiration {
      days = 7
    }
  }
}

# TLS 아닌 요청 거부(presigned URL도 https)
resource "aws_s3_bucket_policy" "reports" {
  bucket = aws_s3_bucket.reports.id
  policy = data.aws_iam_policy_document.reports_bucket.json

  depends_on = [aws_s3_bucket_public_access_block.reports]
}

data "aws_iam_policy_document" "reports_bucket" {
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.reports.arn,
      "${aws_s3_bucket.reports.arn}/*",
    ]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

# ---- Glue 테이블 (partition projection — 크롤러·MSCK 없이 dt 파티션을 바로 읽는다) ----
resource "aws_glue_catalog_database" "reports" {
  name        = local.reports_glue_database # cinema_staging_reports
  description = "6.1.8 SeatSAC error/security reports (ADR-121)"
}

resource "aws_glue_catalog_table" "errors" {
  name          = "errors" # ops-api OPS_REPORT_TABLE 기본값
  database_name = aws_glue_catalog_database.reports.name
  table_type    = "EXTERNAL_TABLE"
  description   = "errorCategory log lines exported hourly from Loki (gitops observability/report-export)"

  parameters = {
    EXTERNAL                      = "TRUE"
    classification                = "json"
    "projection.enabled"          = "true"
    "projection.dt.type"          = "date"
    "projection.dt.format"        = "yyyy-MM-dd"
    "projection.dt.range"         = "2026-11-01,NOW"
    "projection.dt.interval"      = "1"
    "projection.dt.interval.unit" = "DAYS"
    "storage.location.template"   = "s3://${aws_s3_bucket.reports.bucket}/errors/dt=$${dt}/"
  }

  partition_keys {
    name = "dt"
    type = "string" # ops-api가 WHERE dt = 'YYYY-MM-DD'(문자열 매개변수)로 부른다
  }

  storage_descriptor {
    location      = "s3://${aws_s3_bucket.reports.bucket}/errors/"
    input_format  = "org.apache.hadoop.mapred.TextInputFormat" # .gz는 확장자로 풀린다
    output_format = "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat"

    ser_de_info {
      serialization_library = "org.openx.data.jsonserde.JsonSerDe"
      parameters = {
        "ignore.malformed.json" = "true" # 깨진 줄 하나로 쿼리 전체가 실패하지 않게
        "case.insensitive"      = "true" # 내보내기 JSON의 errorCategory·traceId → 열 errorcategory·traceid
      }
    }

    # 모두 string — 서비스마다 status가 숫자·문자로 섞여도 읽기가 실패하지 않는다(CSV에는 그대로 나간다)
    columns {
      name = "timestamp"
      type = "string"
    }
    columns {
      name = "service"
      type = "string"
    }
    columns {
      name = "level"
      type = "string"
    }
    columns {
      name = "errorcategory"
      type = "string"
    }
    columns {
      name = "status"
      type = "string"
    }
    columns {
      name = "path"
      type = "string"
    }
    columns {
      name = "traceid"
      type = "string"
    }
    columns {
      name = "message"
      type = "string"
    }
  }
}

# ---- Athena 작업 그룹 ----
resource "aws_athena_workgroup" "reports" {
  name          = local.reports_workgroup # cinema-staging-reports
  description   = "6.1.8 SeatSAC reports (ops-api, ADR-121)"
  force_destroy = true # 쿼리 기록만 담는다

  configuration {
    enforce_workgroup_configuration    = true # 결과 위치·암호화를 호출자가 바꾸지 못하게
    publish_cloudwatch_metrics_enabled = true
    bytes_scanned_cutoff_per_query     = 1073741824 # 1GB — 하루 파일은 몇 MB. 잘못된 쿼리의 비용 상한

    result_configuration {
      output_location = "s3://${aws_s3_bucket.reports.bucket}/athena-results/" # ops-api 정책 WriteAthenaResults와 같은 접두사
      encryption_configuration {
        encryption_option = "SSE_S3"
      }
    }
  }

  tags = { Name = local.reports_workgroup }
}

# ---- 6.1.9 내보내기 CronJob Pod Identity — cinema-staging-report-export-pod (conventions 9장) ----
#   monitoring/report-export(gitops observability/report-export)가 Loki를 읽어 errors/에 쓴다. 쓰기는 errors/ 아래만
module "report_export_pod_identity" {
  source  = "terraform-aws-modules/eks-pod-identity/aws"
  version = "~> 2.0"

  name            = "${var.name}-report-export-pod" # cinema-staging-report-export-pod
  use_name_prefix = false

  attach_custom_policy      = true
  custom_policy_description = "6.1.9 report export (ADR-121): write errors/ in the reports bucket only"
  policy_statements = [
    {
      sid       = "WriteErrorExports"
      actions   = ["s3:PutObject"]
      resources = ["${aws_s3_bucket.reports.arn}/errors/*"]
    }
  ]

  associations = {
    this = {
      cluster_name    = module.eks.cluster_name
      namespace       = "monitoring"
      service_account = "report-export" # gitops observability/report-export/serviceaccount.yaml
    }
  }
}

# ------------------------------------------------------------------
# 3.1.24 로그 저장소 Loki — S3 버킷 + Pod Identity (ADR-113 가번호, ADR-10 보완)
#   - Loki(SeatSAC-gitops platform/loki, monitoring namespace)가 청크·인덱스를 이 버킷에 쓴다.
#     노드가 0대가 되거나(밤·주말, ADR-34) Loki 파드가 옮겨져도 로그가 남는다 (EBS CSI 없음 → PVC 대신 S3)
#   - 버킷 이름에 계정 ID가 들어가 Public gitops에 쓸 수 없다 → staging-k8s/loki.tf의 ConfigMap loki-storage로 넘기고
#     Loki가 기동할 때 -config.expand-env로 ${LOKI_S3_BUCKET}을 치환한다(cinema-infra-endpoints와 같은 방식)
#   - 보존: Loki compactor가 7일 뒤 지운다(gitops values retention_period 168h).
#     청크(fake/) 수명 주기 14일은 compactor가 멈췄거나 파드 교체로 삭제 marker(emptyDir)를 잃었을 때의 안전망 — 로그만 담으니 버킷을 지울 때 비우지 않아도 되게 force_destroy
#   - 역할 cinema-staging-loki-pod: 이 버킷 목록·읽기·쓰기·삭제만. 연결 ServiceAccount monitoring/loki (gitops values serviceAccount.name)
#     연결을 만든 뒤에 뜬 Loki 파드만 자격 증명을 받는다 → 적용 순서는 이 apply → staging-k8s apply → gitops 머지 (런북 6-J)
# ------------------------------------------------------------------
resource "aws_s3_bucket" "loki" {
  bucket        = "${var.name}-loki-${local.account_id}" # conventions 9장 S3 버킷 규칙
  force_destroy = true                                   # 로그만 담는다(재생성 리허설·destroy 때 비우지 않고 지운다)

  tags = { Name = "${var.name}-loki" }
}

resource "aws_s3_bucket_public_access_block" "loki" {
  bucket = aws_s3_bucket.loki.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "loki" {
  bucket = aws_s3_bucket.loki.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "loki" {
  bucket = aws_s3_bucket.loki.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256" # 로그에 userId·traceId가 있다(clientIp는 Alloy가 가린다). KMS 키 비용 없이 저장 암호화
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "loki" {
  bucket = aws_s3_bucket.loki.id

  rule {
    id     = "expire-chunks"
    status = "Enabled"
    filter {
      prefix = "fake/" # 청크만(auth_enabled false → 테넌트 fake). index/(compactor·삭제 요청 상태)는 compactor 보존에 맡긴다
    }

    expiration {
      days = 14 # compactor 보존(7일)이 실패해도 2주 뒤에는 사라진다
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

module "loki_pod_identity" {
  source  = "terraform-aws-modules/eks-pod-identity/aws"
  version = "~> 2.0"

  name            = "${var.name}-loki-pod" # cinema-staging-loki-pod
  use_name_prefix = false

  attach_custom_policy      = true
  custom_policy_description = "3.1.24 Loki log storage: this bucket only"
  policy_statements = [
    {
      sid       = "ListLokiBucket"
      actions   = ["s3:ListBucket"]
      resources = [aws_s3_bucket.loki.arn]
    },
    {
      sid       = "ReadWriteLokiObjects"
      actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
      resources = ["${aws_s3_bucket.loki.arn}/*"]
    }
  ]

  associations = {
    this = {
      cluster_name    = module.eks.cluster_name
      namespace       = "monitoring"
      service_account = "loki" # gitops platform/loki/values.yaml serviceAccount.name
    }
  }
}

# ------------------------------------------------------------------
# 3.1.12 DB 계정 생성 Job 권한 (Pod Identity)
#   - RDS는 VPC 밖(노트북)에서 닿지 않으므로 스키마·계정은 클러스터 안 Job(envs/staging-k8s/db_bootstrap.tf)이 만든다
#   - Job은 RDS 관리 마스터 Secret(Secrets Manager, 3.1.3)을 읽어 접속한다 → 마스터 비밀번호는 tfstate에 들어가지 않는다
#   - 권한은 그 Secret 하나의 GetSecretValue뿐. namespace cinema-ops(관리자 전용) · ServiceAccount db-bootstrap에만 연결
#   - 마스터 Secret은 AWS 관리 키(aws/secretsmanager)로 암호화 → kms 권한은 따로 필요 없다
# ------------------------------------------------------------------
module "db_bootstrap_pod_identity" {
  source  = "terraform-aws-modules/eks-pod-identity/aws"
  version = "~> 2.0"

  name = "${var.name}-db-bootstrap-pod" # cinema-staging-db-bootstrap-pod

  attach_custom_policy      = true
  custom_policy_description = "3.1.12 DB bootstrap Job: read RDS managed master secret only"
  policy_statements = [
    {
      sid       = "ReadRdsMasterSecret"
      actions   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
      resources = [aws_db_instance.mysql.master_user_secret[0].secret_arn]
    }
  ]

  associations = {
    this = {
      cluster_name    = module.eks.cluster_name
      namespace       = var.ops_namespace
      service_account = "db-bootstrap"
    }
  }
}

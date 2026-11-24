# ------------------------------------------------------------------
# 6.1.4 External Secrets Operator 권한 (ADR-123)
#   - ESO(ArgoCD가 gitops platform/external-secrets로 설치)가 Secrets Manager cinema/staging/* 를 읽어
#     같은 이름의 K8s Secret({svc}-secrets·{svc}-migrator-secrets)을 만든다
#   - 권한은 그 경로의 GetSecretValue·DescribeSecret뿐 — RDS 관리 마스터 Secret(rds!…)이나 다른 Secret은 읽지 못한다
#   - Pod Identity cinema-staging-external-secrets-pod (conventions 9장) → namespace external-secrets · ServiceAccount external-secrets
#     (차트 기본 SA 이름. gitops values에서 바꾸면 여기도 같이 바꾼다)
#   - Secret 값은 envs/staging-k8s/external_secrets.tf가 넣는다(비밀번호가 그 state에만 있다)
#   - 값은 AWS 관리 키(aws/secretsmanager)로 암호화 → kms 권한은 따로 필요 없다
# ------------------------------------------------------------------
module "external_secrets_pod_identity" {
  source  = "terraform-aws-modules/eks-pod-identity/aws"
  version = "~> 2.0"

  name            = "${var.name}-external-secrets-pod" # cinema-staging-external-secrets-pod
  use_name_prefix = false

  attach_custom_policy      = true
  custom_policy_description = "6.1.4 External Secrets Operator: read cinema/staging/* secrets only"
  policy_statements = [
    {
      sid       = "ReadAppSecrets"
      actions   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
      resources = ["arn:aws:secretsmanager:${var.region}:${local.account_id}:secret:cinema/${var.env}/*"]
    }
  ]

  associations = {
    this = {
      cluster_name    = module.eks.cluster_name
      namespace       = "external-secrets"
      service_account = "external-secrets"
    }
  }
}

output "external_secrets_pod_role_arn" {
  description = "6.1.4 cinema-staging-external-secrets-pod (Pod Identity, ServiceAccount external-secrets/external-secrets)"
  value       = module.external_secrets_pod_identity.iam_role_arn
}

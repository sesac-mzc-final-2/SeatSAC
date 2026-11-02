# ------------------------------------------------------------------
# 3.1.7 Grafana → CloudWatch 읽기 (Pod Identity) — 신호등 대시보드의 Valkey 메모리·Evictions, SQS DLQ 패널
#   - 역할 cinema-staging-grafana-pod (런타임 계약·conventions 9장 규칙 cinema-staging-{대상}-pod)
#   - 연결 대상: namespace monitoring, ServiceAccount kube-prometheus-stack-grafana (kube-prometheus-stack 차트가 만든다)
#   - 읽기 전용 지표 조회 3개만. CloudWatch 조회 API는 리소스 단위 제한을 지원하지 않아 resources = "*"
#   - ServiceAccount는 파드를 만들 때 정해지고 자격 증명 환경변수도 그때 주입된다 → **연결을 만든 뒤에 뜬 Grafana 파드만** 쓴다
#     (이미 떠 있던 파드는 다시 시작해야 한다, 런북 6-F)
#   - 데이터소스 정의(uid cloudwatch, authType default)는 SeatSAC-gitops platform/kube-prometheus-stack/values.yaml
# ------------------------------------------------------------------
module "grafana_pod_identity" {
  source  = "terraform-aws-modules/eks-pod-identity/aws"
  version = "~> 2.0"

  name            = "${var.name}-grafana-pod" # cinema-staging-grafana-pod
  use_name_prefix = false                     # 모듈 기본값(true)은 이름 뒤에 난수를 붙인다 → 계약의 정확한 이름으로

  attach_custom_policy      = true
  custom_policy_description = "3.1.7 Grafana CloudWatch datasource: read metrics only"
  policy_statements = [
    {
      sid       = "ReadCloudWatchMetrics"
      actions   = ["cloudwatch:GetMetricData", "cloudwatch:GetMetricStatistics", "cloudwatch:ListMetrics"]
      resources = ["*"]
    }
  ]

  associations = {
    this = {
      cluster_name    = module.eks.cluster_name
      namespace       = "monitoring"
      service_account = "kube-prometheus-stack-grafana" # 차트 릴리스 이름(Application kube-prometheus-stack) + "-grafana"
    }
  }
}

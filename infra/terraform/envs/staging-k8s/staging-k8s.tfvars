# 비밀 값 없음 → 커밋한다
region       = "ap-northeast-2"
env          = "staging"
cluster_name = "cinema-staging-eks"

app_namespace        = "cinema-staging"
ops_namespace        = "cinema-ops"
namespaces           = ["cinema-staging", "monitoring", "cinema-ops"] # Terraform이 만드는 namespace (cinema-ops = 관리자 전용, 3.1.12)
developer_namespaces = ["cinema-staging", "monitoring"]               # 팀원 port-forward 범위 (envs/staging 값과 같게)

# ⬇ apply 전에 helm search 결과로 바꾼다 (variables.tf 설명 참고). 형식이 틀리면 plan이 멈춘다
lbc_chart_version = "3.6.1"
lbc_replicas      = 1

# ⬇ 3.1.5 ArgoCD 차트 버전 — apply 전에 helm search로 실제 있는 버전인지 확인 (variables.tf 설명)
argocd_chart_version      = "10.11.1"
argocd_apps_chart_version = "2.0.7"

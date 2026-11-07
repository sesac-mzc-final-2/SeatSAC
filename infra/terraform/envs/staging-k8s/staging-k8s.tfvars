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

# ⬇ 3.1.16 Cognito: envs/staging apply(cognito.tf)로 사용자 풀이 생긴 뒤 이 폴더 apply (런북 7-6)
#   → ConfigMap COGNITO_ISSUER_URI·COGNITO_CLIENT_ID 채움 + gateway ArgoCD Application 생성
cognito_enabled = true

# ⬇ ⚠️AI 3.1.18: envs/staging apply(ai_valkey.tf·ECR cinema/ai) 뒤 이 폴더 apply (런북 6-G-1)
#   → Secret ai-secrets + cinema-infra-endpoints AI_VALKEY_HOST·PUBLIC_API_BASE_URL + Application cinema-ai
#   TF_VAR_anthropic_api_key는 ai-secrets를 처음 만들 때·교체할 때만 필요하다(ai.tf, 평소 plan·apply에는 불필요)
ai_enabled                 = true
cloudfront_distribution_id = "E316HLOWJ7VZO1" # global.tfvars frontend_distribution_ids와 같은 값 (비밀 아님)

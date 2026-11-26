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
cloudfront_distribution_id = "E1HUYMSCO5ZSAF" # global.tfvars frontend_distribution_ids와 같은 값 (비밀 아님)

# ⬇ 6.1.1 서비스별 RDS(ADR-115): envs/staging apply로 cinema-staging-{svc}-db 6개가 available이 된 뒤 이 폴더 apply (런북 8-E)
#   → 인스턴스별 db-bootstrap Job · DB_HOST_{SVC} · coupon Secret 2개 · ai-migrator-secrets
service_db_services = ["booking", "payment", "user", "catalog", "coupon", "ai"]

# ⬇ 6.1.5 coupon(ADR-118): gitops base/coupon 머지 → envs/staging apply(ECR cinema/coupon) → 저장소 변수 COUPON_BUILD_PUSH_ENABLED=true
#   → build-push 수동(services=coupon)으로 이미지·태그 커밋 → 이 값을 true로 바꾸는 한 줄 PR → 이 폴더 apply (런북 8-G)
#   → Application cinema-coupon. 그 전에 true면 이미지가 없어 ImagePullBackOff(Degraded)
coupon_enabled = false

# ⬇ 6.1.6 Karpenter(ADR-122): envs/staging apply(karpenter_enabled — 권한·노드 역할·큐) 뒤 이 폴더 apply → 컨트롤러 Helm (런북 6-K 2))
#   그다음 gitops platform/karpenter(NodePool·EC2NodeClass) PR 머지. 차트 버전은 apply 전 helm show chart로 확인
karpenter_enabled       = true
karpenter_chart_version = "1.15.3"

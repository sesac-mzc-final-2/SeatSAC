variable "region" {
  type    = string
  default = "ap-northeast-2"
}

variable "env" {
  type    = string
  default = "staging"
}

variable "owner" {
  type    = string
  default = "kimjongsik"
}

variable "cluster_name" {
  description = "envs/staging 출력 eks_cluster_name"
  type        = string
  default     = "cinema-staging-eks"
}

variable "app_namespace" {
  type    = string
  default = "cinema-staging"
}

variable "namespaces" {
  description = "Terraform이 만드는 namespace. 여기 추가해도 팀원 권한은 생기지 않는다(developer_namespaces와 별개)"
  type        = list(string)
  default     = ["cinema-staging", "monitoring", "cinema-ops"]
}

variable "ops_namespace" {
  description = "관리자 전용 namespace (3.1.12 DB 계정 생성 Job·입력 Secret). developer_namespaces에 넣지 않는다. envs/staging의 ops_namespace와 같은 값"
  type        = string
  default     = "cinema-ops"
}

variable "developer_namespaces" {
  description = <<-EOT
    팀원(cinema-developers)에게 port-forward Role을 줄 namespace. namespaces 안에 있어야 한다.
    envs/staging의 developer_namespaces(Access Entry 조회 범위)와 같은 값으로 둔다 — 둘이 다르면 조회는 되는데 port-forward가 안 되거나 그 반대
  EOT
  type        = list(string)
  default     = ["cinema-staging", "monitoring"]
}

variable "lbc_chart_version" {
  description = <<-EOT
    aws-load-balancer-controller Helm 차트 버전(고정). 처음 한 번 확인해서 tfvars에 적는다:
      helm repo add eks https://aws.github.io/eks-charts && helm repo update
      helm search repo eks/aws-load-balancer-controller --versions | head -5
    CHART VERSION 열의 값(APP VERSION 아님)
  EOT
  type        = string

  validation {
    condition     = can(regex("^[0-9]+\\.[0-9]+\\.[0-9]+$", var.lbc_chart_version))
    error_message = "lbc_chart_version에 helm search로 확인한 차트 버전(예: 1.2.3)을 적는다."
  }
}

variable "lbc_replicas" {
  description = "노드 2대라 1개. 컨트롤러는 트래픽 경로가 아니라(ALB가 처리) 재시작돼도 서비스 영향 없음"
  type        = number
  default     = 1
}

# ---- 6.1.6 Karpenter (karpenter.tf) ---------------------------------------
variable "karpenter_enabled" {
  description = "envs/staging karpenter_enabled apply(권한·노드 역할·큐) 뒤 true → Karpenter 컨트롤러 Helm 설치 (런북 6-K 2))"
  type        = bool
  default     = false
}

variable "karpenter_chart_version" {
  description = <<-EOT
    karpenter Helm 차트 버전(고정, OCI). Kubernetes 1.36은 1.13 이상. apply 전에 실제로 있는 버전인지 확인한다:
      helm show chart oci://public.ecr.aws/karpenter/karpenter --version <버전>
  EOT
  type        = string
  default     = "1.15.3"

  validation {
    condition     = can(regex("^1\\.(1[3-9]|[2-9][0-9])\\.[0-9]+$", var.karpenter_chart_version))
    error_message = "karpenter_chart_version은 1.13 이상(Kubernetes 1.36 호환) 차트 버전(예: 1.15.3)."
  }
}

variable "karpenter_replicas" {
  description = "Karpenter 컨트롤러 수. 관리형 노드 2대(AZ a·c)에 하나씩 — AZ 하나가 죽어도 노드를 새로 만들 수 있게"
  type        = number
  default     = 2
}

# ---- 3.1.12 DB 스키마·계정 ----------------------------------------------
variable "rds_identifier" {
  description = "envs/staging RDS 식별자 (3.1.3). 엔드포인트·마스터 Secret ARN을 이 이름으로 조회한다"
  type        = string
  default     = "cinema-staging-mysql"
}

variable "service_db_services" {
  description = <<-EOT
    6.1.1 자기 RDS 인스턴스(cinema-staging-{svc}-db)가 있는 서비스(ADR-115). envs/staging의 service_databases 키와 같게 둔다.
    여기 넣은 서비스마다 인스턴스를 조회해 db-bootstrap Job·DB_HOST_{SVC}를 만든다 → **envs/staging apply로 인스턴스가 생긴 뒤** 채운다
    (없는 인스턴스를 넣으면 plan이 DBInstanceNotFound로 멈춘다)
  EOT
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for s in var.service_db_services : contains(["booking", "payment", "user", "catalog", "coupon", "ai"], s)]) && length(var.service_db_services) == length(distinct(var.service_db_services))
    error_message = "service_db_services는 booking·payment·user·catalog·coupon·ai 중에서 중복 없이 쓴다."
  }
}

variable "service_db_identifier_prefix" {
  description = "서비스별 RDS 식별자 접두사 → {접두사}-{svc}-db (envs/staging var.name과 같은 값)"
  type        = string
  default     = "cinema-staging"
}

variable "mysql_client_image" {
  description = "DB 계정 생성 Job의 mysql 클라이언트 이미지 (RDS 8.4와 같은 메이저, 버전 고정)"
  type        = string
  default     = "mysql:8.4.6"
}

variable "awscli_image" {
  description = "마스터 Secret을 읽는 initContainer 이미지 (Pod Identity 지원 AWS CLI v2, 버전 고정)"
  type        = string
  default     = "public.ecr.aws/aws-cli/aws-cli:2.33.6"
}

# ---- 앱 엔드포인트 ConfigMap · 서비스 ArgoCD Application (gitops Public, ADR-74) ----
variable "valkey_replication_group_id" {
  description = "envs/staging Valkey 복제 그룹 ID (3.1.3). primary 엔드포인트를 이 이름으로 조회한다"
  type        = string
  default     = "cinema-staging-valkey"
}

variable "notification_queue_name" {
  description = "알림 SQS 큐 이름 (docs/conventions.md 5장, 3.1.8). URL은 계정·리전·이름으로 만든다"
  type        = string
  default     = "cinema-staging-notification-events"
}

variable "messaging_name_prefix" {
  description = "6.1.3 메시징 토픽·큐 이름 접두사 → {접두사}-booking-events 등 (envs/staging var.name과 같은 값, docs/conventions.md 5장)"
  type        = string
  default     = "cinema-staging"
}

variable "cognito_enabled" {
  description = "Cognito 사용자 풀(3.3.x)을 만든 뒤 true. false면 COGNITO_ISSUER_URI·COGNITO_CLIENT_ID가 빈 값"
  type        = bool
  default     = false
}

variable "cognito_user_pool_name" {
  description = "Cognito 사용자 풀 이름 (docs/conventions.md 9장)"
  type        = string
  default     = "cinema-staging-users"
}

# ---- 🆕 coupon 서비스 (6.1.5, ADR-118) -----------------------------------
variable "coupon_enabled" {
  description = "gitops base/coupon 머지 + ECR cinema/coupon 이미지가 생긴 뒤 true → Application cinema-coupon (런북 8-G). Secret·DB_HOST_COUPON은 6.1.1에서 이미 생긴다"
  type        = bool
  default     = false
}

# ---- ⚠️AI ai 서비스 (3.1.18, ADR-85·86) ---------------------------------
variable "ai_enabled" {
  description = "envs/staging에 ai Valkey·ECR cinema/ai가 생긴 뒤 true → Secret ai-secrets · cinema-infra-endpoints AI_* · Application cinema-ai (런북 6-G-1)"
  type        = bool
  default     = false
}

variable "ops_api_enabled" {
  description = "6.1.7 ops-api ArgoCD Application(cinema-ops-api)을 만든다. ECR cinema/ops-api·gitops base/ops-api·overlays/staging/ops-api가 생긴 뒤 true (런북 6-L)"
  type        = bool
  default     = false
}

variable "ai_valkey_replication_group_id" {
  description = "ai Valkey 이름 (envs/staging ai_valkey.tf). 이름으로 조회해 AI_VALKEY_HOST를 채운다"
  type        = string
  default     = "cinema-staging-ai-valkey"
}

variable "cloudfront_distribution_id" {
  description = "PUBLIC_API_BASE_URL(https://{배포 도메인}/api)을 만들 CloudFront 배포 ID (envs/staging output cloudfront_distribution_id, global.tfvars와 같은 값). 비밀 아님"
  type        = string
  default     = null
}

variable "anthropic_api_key" {
  description = "ai-secrets ANTHROPIC_API_KEY (staging 키, ADR-83). Secret을 처음 만들 때·교체(-replace)할 때만 필요(ai.tf). 파일에 쓰지 않는다 — `read -s TF_VAR_anthropic_api_key && export TF_VAR_anthropic_api_key`"
  type        = string
  default     = null
  sensitive   = true
}

variable "portone_api_secret" {
  description = "payment-secrets APP_PORTONE_API_SECRET (포트원 콘솔 V2 API Secret, ADR-112). 넣을 때·바꿀 때만 — 비어 있으면 지금 값 유지(portone.tf). 파일에 쓰지 않는다 — `read -s TF_VAR_portone_api_secret && export TF_VAR_portone_api_secret`"
  type        = string
  default     = null
  sensitive   = true
}

variable "portone_webhook_secret" {
  description = "payment-secrets APP_PORTONE_WEBHOOK_SECRET (포트원 콘솔 웹훅 시크릿 whsec_…, ADR-112). 넣을 때·바꿀 때만 — 비어 있으면 지금 값 유지. 파일에 쓰지 않는다"
  type        = string
  default     = null
  sensitive   = true
}

variable "portone_store_id" {
  description = "cinema-infra-endpoints PORTONE_STORE_ID (포트원 Store ID, 공개 값 — 결제창에 노출, ADR-112). 넣을 때·바꿀 때만 — 비어 있으면 지금 값 유지. 레포가 발표 후 Public이라 tfvars에 커밋하지 않고 TF_VAR로"
  type        = string
  default     = null
}

variable "portone_channel_key_tosspay" {
  description = "cinema-infra-endpoints PORTONE_CHANNEL_KEY_TOSSPAY (포트원 토스페이 채널 키, 공개 값, ADR-112). portone_store_id와 같은 방식"
  type        = string
  default     = null
}

variable "argocd_namespace" {
  type    = string
  default = "argocd"
}

variable "gitops_repo_url" {
  description = "SeatSAC-gitops (Public → HTTPS, ArgoCD 레포 자격 증명 없음)"
  type        = string
  default     = "https://github.com/sesac-mzc-final-2/SeatSAC-gitops.git"
}

# ---- 3.1.5 ArgoCD ----
variable "argocd_chart_version" {
  description = <<-EOT
    argo-cd Helm 차트 버전(고정). apply 전에 실제로 있는 버전인지 확인한다(3.1.4 tomcat 10.1.58 교훈):
      helm repo add argo https://argoproj.github.io/argo-helm && helm repo update
      helm search repo argo/argo-cd --versions | head -5
    CHART VERSION 열의 값(APP VERSION 아님)
  EOT
  type        = string

  validation {
    condition     = can(regex("^[0-9]+\\.[0-9]+\\.[0-9]+$", var.argocd_chart_version))
    error_message = "argocd_chart_version에 helm search로 확인한 차트 버전(예: 1.2.3)을 적는다."
  }
}

variable "argocd_apps_chart_version" {
  description = "argocd-apps Helm 차트 버전(고정, 2.x 이상 — applications가 map 형식). helm search repo argo/argocd-apps --versions"
  type        = string

  validation {
    condition     = can(regex("^[2-9][0-9]*\\.[0-9]+\\.[0-9]+$", var.argocd_apps_chart_version))
    error_message = "argocd_apps_chart_version은 2.x 이상 차트 버전(예: 2.0.7)."
  }
}

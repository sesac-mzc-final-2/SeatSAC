variable "region" {
  type    = string
  default = "ap-northeast-2"
}

variable "env" {
  type    = string
  default = "staging"
}

variable "name" {
  description = "리소스 이름 접두사 (cinema-staging-*)"
  type        = string
  default     = "cinema-staging"
}

variable "owner" {
  type    = string
  default = "kimjongsik"
}

# ---- 네트워크 (3.1.1) ------------------------------------------------
variable "vpc_cidr" {
  type    = string
  default = "10.20.0.0/16"
}

variable "azs" {
  description = "가용 영역 2개 (A, C). 순서가 서브넷 CIDR 순서와 같다"
  type        = list(string)
  default     = ["ap-northeast-2a", "ap-northeast-2c"]
}

variable "public_subnets" {
  description = "ALB · NAT · IGW"
  type        = list(string)
  default     = ["10.20.0.0/24", "10.20.1.0/24"]
}

variable "app_subnets" {
  description = "EKS 노드·파드 (VPC CNI라 파드도 이 대역의 IP를 쓴다 → /20)"
  type        = list(string)
  default     = ["10.20.16.0/20", "10.20.32.0/20"]
}

variable "data_subnets" {
  description = "RDS · ElastiCache"
  type        = list(string)
  default     = ["10.20.48.0/24", "10.20.49.0/24"]
}

# ---- EKS (3.1.2) -----------------------------------------------------
variable "eks_version" {
  description = "Kubernetes 버전. 1.36 표준 지원 ~2027-08-02. 올릴 때는 한 단계씩"
  type        = string
  default     = "1.36"
}

variable "eks_public_access_cidrs" {
  description = <<-EOT
    EKS API 공개 엔드포인트 허용 대역(kubectl·helm을 쓰는 곳의 공인 IP, /32 권장). 기본값 없음 — 전체 공개 금지.
    공인 IP는 커밋하지 않는다(발표 후 Public 레포):
      로컬 apply : eks_access.auto.tfvars (.gitignore의 *.auto.tfvars)
      CI plan    : GitHub Secret EKS_PUBLIC_ACCESS_CIDRS → TF_VAR_eks_public_access_cidrs (JSON 배열)
    GitHub Actions는 클러스터 API를 부르지 않는다(infra-plan은 AWS API만, cluster-schedule은 EKS 관리 API만) → Actions 대역 불필요.
    IP가 바뀌면 두 곳을 고치고 apply (런북 4-6). 엔드포인트 재생성 없이 제자리 변경.
  EOT
  type        = list(string)
  sensitive   = true

  validation {
    condition     = length(var.eks_public_access_cidrs) > 0 && !contains(var.eks_public_access_cidrs, "0.0.0.0/0")
    error_message = "eks_public_access_cidrs에 팀 공인 IP 대역을 넣는다. 0.0.0.0/0(전체 공개)은 쓰지 않는다."
  }

  validation {
    condition     = alltrue([for c in var.eks_public_access_cidrs : can(cidrhost(c, 0))])
    error_message = "CIDR 형식으로 넣는다 (예: 203.0.113.10/32)."
  }
}

variable "node_instance_type" {
  description = "기본 노드그룹 ng-a·ng-c 인스턴스 (ADR-7: 2대. 11/14 t3.large → t3.xlarge — 4.2.1 선점 p95가 파드 CPU에 묶임)"
  type        = string
  default     = "t3.xlarge"
}

variable "ai_node_instance_type" {
  description = "⚠️AI ng-ai 인스턴스 (ADR-86: ai 전용 1대). 기본 노드그룹과 따로 둔다 — 기본 노드 크기를 바꿔도 ng-ai는 교체되지 않는다"
  type        = string
  default     = "t3.large"
}

variable "developer_namespaces" {
  description = "cinema-developer가 조회·로그·port-forward 할 수 있는 namespace"
  type        = list(string)
  default     = ["cinema-staging", "monitoring"]

  # 관리자 전용 namespace(DB 계정 생성 Job·계정 비밀번호 Secret)를 팀원 조회 범위에 넣지 않는다 (Terraform ≥ 1.9 교차 변수 검증)
  validation {
    condition     = !contains(var.developer_namespaces, var.ops_namespace)
    error_message = "developer_namespaces에 ops_namespace(cinema-ops)를 넣지 않는다. 관리자 전용이다."
  }
}

# ---- RDS·Valkey (3.1.3) ----------------------------------------------
variable "rds_engine_version" {
  description = "MySQL 메이저 버전(ADR-3·33). 마이너는 생성 시점 AWS 기본값, 자동 업그레이드 끔"
  type        = string
  default     = "8.4"
}

variable "rds_instance_class" {
  description = "RDS 클래스. 파드당 Hikari 5 x 최대 파드 수 + Flyway Job 연결을 받아야 한다"
  type        = string
  default     = "db.t4g.small"
}

variable "rds_connection_alarm" {
  description = "RDS 연결 수 경보 기준 (t4g.small max_connections 약 170의 80%)"
  type        = number
  default     = 130
}

variable "service_databases" {
  description = <<-EOT
    6.1.1 서비스별 RDS 인스턴스(ADR-115). 키 = 서비스 이름 → cinema-staging-{키}-db, 스키마 {키}_db.
    값은 docs/data-model.md 7-1 표와 같게 둔다. connection_alarm은 그 클래스 max_connections의 약 75%
    (db.t4g.small 약 170 → 130, db.t4g.micro 약 60 → 45). 비우면 인스턴스를 만들지 않는다
  EOT
  type = map(object({
    instance_class   = string
    multi_az         = bool
    connection_alarm = number
  }))
  default = {}

  validation {
    condition     = alltrue([for k in keys(var.service_databases) : contains(["booking", "payment", "user", "catalog", "coupon", "ai"], k)])
    error_message = "service_databases 키는 booking·payment·user·catalog·coupon·ai 중에서만 쓴다 (docs/conventions.md 3장 스키마 표)."
  }
}

variable "valkey_engine_version" {
  type    = string
  default = "8.2"
}

variable "valkey_node_type" {
  description = "ADR-59: t4g.micro, 부하 테스트에서 메모리 70% 넘으면 t4g.small"
  type        = string
  default     = "cache.t4g.micro"
}

variable "ai_valkey_node_type" {
  description = "⚠️AI ai Valkey (ADR-85): t4g.micro, 메모리 80% 경보가 계속 울리면 t4g.small"
  type        = string
  default     = "cache.t4g.micro"
}

# ---- CloudFront·S3 (3.1.11) --------------------------------------------
variable "gateway_ingress_stack" {
  description = "gateway Ingress의 <namespace>/<이름>(gitops 3.1.5). LB Controller가 ALB에 태그 ingress.k8s.aws/stack=<이 값>을 붙인다 → CloudFront /api/* 원본을 자동으로 찾는다"
  type        = string
  default     = "cinema-staging/gateway"
}

variable "api_origin_domain_name" {
  description = "비워 두면 위 태그로 ALB를 찾는다. 태그 탐색이 안 될 때만 ALB DNS 이름을 직접 넣는다(비밀 아님)"
  type        = string
  default     = ""
}

variable "api_origin_https" {
  description = <<-EOT
    3.1.23 후속 CloudFront → ALB 구간 HTTPS (origin_https.tf, 런북 7-8). custom_domain이 있어야 한다.
      off     (기본) 아무것도 만들지 않는다. CloudFront → ALB는 HTTP(80)
      prepare 인증서(ap-northeast-2)·DNS origin.<custom_domain>·ALB SG 규칙을 80~443으로. CloudFront는 아직 HTTP
              → 이 뒤에 gitops PR로 Ingress에 443 리스너를 더한다
      on      CloudFront 원본을 origin.<custom_domain> · https-only 로 바꾼다(ALB 443 리스너가 있어야 plan 통과)
  EOT
  type        = string
  default     = "off"

  validation {
    condition     = contains(["off", "prepare", "on"], var.api_origin_https)
    error_message = "api_origin_https는 off · prepare · on 중 하나."
  }

  validation {
    condition     = var.api_origin_https == "off" || var.custom_domain != ""
    error_message = "api_origin_https를 켜려면 custom_domain이 있어야 한다(원본 이름 origin.<custom_domain>의 인증서·DNS)."
  }
}

# ---- 6.1.8 WAF (ADR-124, waf.tf) ------------------------------------------
variable "waf_mode" {
  description = "CloudFront 웹 ACL cinema-staging-waf: off(없음) · count(세기만 — 오탐 확인) · block(차단). off → count → block 순서로 (런북 6-M)"
  type        = string
  default     = "off"

  validation {
    condition     = contains(["off", "count", "block"], var.waf_mode)
    error_message = "waf_mode는 off·count·block 중 하나"
  }
}

variable "waf_rate_limit_per_5min" {
  description = "IP당 5분 요청 상한(WAF rate-based, 100~). 넘은 IP는 count면 세기만, block이면 그 IP가 기준 아래로 내려올 때까지 403"
  type        = number
  default     = 2000

  validation {
    condition     = var.waf_rate_limit_per_5min >= 100
    error_message = "WAF rate-based 규칙 최소값은 100"
  }
}

variable "cloudfront_price_class" {
  description = "PriceClass_200 = 북미·유럽·아시아(한국 포함). PriceClass_100은 한국 엣지가 빠진다"
  type        = string
  default     = "PriceClass_200"
}

# ---- 3.1.9 알림 큐 · booking Pod Identity --------------------------------
variable "app_namespace" {
  description = "앱 namespace. booking ServiceAccount(Pod Identity 연결)가 여기 있다. envs/staging-k8s의 app_namespace와 같은 값"
  type        = string
  default     = "cinema-staging"
}

# ---- 6.1.2 가용성 (ADR-122) --------------------------------------------
variable "nat_gateway_per_az" {
  description = "true면 NAT Gateway를 AZ마다 1개(a·c) 둔다. false(기본)는 AZ a에 1개. 바꿀 때는 런북 5-6 순서로 apply"
  type        = bool
  default     = false
}

variable "valkey_replicas" {
  description = "좌석 선점 Valkey 복제본 수(0 또는 1). 1이면 AZ c에 복제본을 두고 자동 장애 조치·Multi-AZ를 켠다 (ADR-122)"
  type        = number
  default     = 0

  validation {
    condition     = contains([0, 1], var.valkey_replicas)
    error_message = "valkey_replicas는 0 또는 1 (AZ가 2개다)."
  }
}

variable "ai_valkey_replicas" {
  description = "⚠️AI ai Valkey 복제본 수(0 또는 1). 1이면 AZ c에 복제본 + 자동 장애 조치 (ADR-122 「Valkey 2개」)"
  type        = number
  default     = 0

  validation {
    condition     = contains([0, 1], var.ai_valkey_replicas)
    error_message = "ai_valkey_replicas는 0 또는 1 (AZ가 2개다)."
  }
}

# ---- 3.1.12 DB 계정 생성 Job ------------------------------------------
variable "ops_namespace" {
  description = "관리자 전용 namespace(팀원 조회 범위 밖). DB 계정 생성 Job·그 입력 Secret이 여기 있다. envs/staging-k8s의 ops_namespace와 같은 값"
  type        = string
  default     = "cinema-ops"
}

# ---- 6.1.11 Cognito 그룹 지정 -------------------------------------------
variable "cognito_group_members" {
  description = <<-EOT
    시연 계정의 Cognito 그룹 지정: 그룹 이름(VIP·ADMIN) → 사용자 sub(UUID) 목록.
    이메일은 넣지 않는다(레포가 공개된다) — sub는 런북 7-6-5의 명령으로 얻는다. 없는 sub를 넣으면 apply가 UserNotFoundException으로 멈춘다
  EOT
  type        = map(list(string))
  default     = {}

  validation {
    condition     = alltrue([for g in keys(var.cognito_group_members) : contains(["VIP", "ADMIN"], g)])
    error_message = "cognito_group_members의 키는 VIP·ADMIN만 쓴다 (docs/conventions.md 9장 Cognito 그룹)."
  }
  validation {
    condition = alltrue(flatten([
      for subs in values(var.cognito_group_members) : [for s in subs : can(regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", s))]
    ]))
    error_message = "cognito_group_members의 값은 사용자 sub(소문자 UUID)만 넣는다. 이메일 주소를 커밋하지 않는다."
  }
  validation {
    condition     = alltrue([for subs in values(var.cognito_group_members) : length(subs) == length(distinct(subs))])
    error_message = "같은 그룹에 같은 sub를 두 번 넣지 않는다."
  }
}

# 6.1.6 Karpenter (karpenter.tf, ADR-122)
variable "karpenter_enabled" {
  description = "true면 Karpenter 컨트롤러 권한(Pod Identity)·노드 역할·중단 알림 큐·밤 정리 Lambda를 만든다. 끄면 전부 지운다(먼저 런북 6-K 6) 되돌리기)"
  type        = bool
  default     = false
}

# 3.1.14 가동 정책 — EventBridge Scheduler (cluster_schedule.tf)
variable "cluster_keep_on" {
  description = "true면 예약 끄기(EventBridge·GitHub 백업 둘 다)를 멈춘다. 밤에도 켜 둘 때만 잠깐 true → apply, 끝나면 false → apply"
  type        = bool
  default     = false
}

variable "cluster_schedule_demo_start" {
  description = "데모 주간 시작일(KST). 이날 0시부터 예약 끄기가 멈춘다(24시간 가동, ADR-34). 켜기는 계속"
  type        = string
  default     = "2026-11-16"
  validation {
    condition     = can(regex("^\\d{4}-\\d{2}-\\d{2}$", var.cluster_schedule_demo_start))
    error_message = "YYYY-MM-DD 형식"
  }
}

# 3.1.16 Cognito (cognito.tf)
variable "cognito_domain_prefix" {
  description = "Hosted UI 도메인 접두사 → https://<접두사>.auth.<리전>.amazoncognito.com (런타임 계약 3장 VITE_COGNITO_DOMAIN). 리전 안에서 전역 고유"
  type        = string
  default     = "cinema-staging"
}

variable "custom_domain" {
  description = <<-EOT
    3.1.23 사용자 도메인(예: seatsac.example). 비우면(기본) 아무것도 만들지 않고 CloudFront 기본 도메인만 쓴다.
    도메인은 Route 53에서 먼저 등록해 둔다(등록 = 구매라 Terraform 밖, 런북 7-7). 등록 때 자동으로 생긴
    퍼블릭 호스팅 영역을 이름으로 찾아 쓴다(값은 등록한 도메인 그대로 — 서브도메인을 쓰려면 custom_domain_zone_id에 등록 도메인 영역 ID).
    apex 하나만 받는다(www 없음)
  EOT
  type        = string
  default     = ""

  validation {
    condition     = var.custom_domain == "" || can(regex("^([a-z0-9-]+\\.)+[a-z0-9-]+$", var.custom_domain))
    error_message = "custom_domain은 스킴·경로·끝 점이 없는 소문자 도메인 이름만(예: seatsac.example). 비우면 사용자 도메인을 만들지 않는다."
  }
}

variable "custom_domain_hosted_zone" {
  description = <<-EOT
    ADR-125 다른 AWS 계정으로 옮길 때만 — 이 계정에 Terraform이 만들 퍼블릭 호스팅 영역 이름(등록 도메인 apex, 예: seatsac.example).
    도메인 등록 이전은 영역을 옮기지 않아 새 계정에는 영역이 없다. 비우면(기본) 만들지 않는다 — 등록 때 자동으로 생긴 영역을 data로 쓴다.
    ⚠ 등록 도메인의 네임서버를 이 영역으로 바꿀 **새 계정에서만** 넣는다. 지금 영역이 이미 위임받은 계정에 넣으면 같은 이름의 두 번째
    영역이 생기고 검증·별칭 레코드가 위임받지 않은 새 영역으로 옮겨져 인증서 검증이 끝나지 않는다.
    custom_domain은 이 영역의 이름이거나 그 아래 이름이어야 이 영역을 쓴다. 순서: 이 값만 apply → 출력 custom_domain_name_servers로
    등록 도메인 네임서버 교체 → 퍼진 뒤 custom_domain.
    사용자 도메인 중단: 옛 계정 CloudFront에서 별칭을 뗀 순간부터(같은 별칭을 두 배포에 붙일 수 없다) 새 계정 custom_domain apply로
    인증서·별칭이 생길 때까지 사용자 도메인은 답이 없다 — 새 영역의 인증서 검증이 네임서버 교체 뒤에야 끝나 순서로는 줄일 수 없는,
    계획된 중단이다(기본 도메인 *.cloudfront.net은 계속 동작, 시연·측정이 없는 시간에). Terraform 밖 레코드(MX·TXT 등)는 네임서버를
    바꾸기 전에 custom_domain.tf에 aws_route53_record로 더해 이 영역에 먼저 넣는다(지금은 없다).
    절차: docs/runbooks/rebuild-new-account.md 7장(#225)
  EOT
  type        = string
  default     = ""

  validation {
    condition     = var.custom_domain_hosted_zone == "" || can(regex("^([a-z0-9-]+\\.)+[a-z0-9-]+$", var.custom_domain_hosted_zone))
    error_message = "custom_domain_hosted_zone은 스킴·경로·끝 점이 없는 소문자 도메인 이름만(예: seatsac.example)."
  }
}

variable "custom_domain_zone_id" {
  description = "3.1.23 같은 이름의 퍼블릭 호스팅 영역이 둘 이상일 때만 — 등록 도메인의 네임서버와 맞는 영역 ID. 비우면 이름으로 찾는다"
  type        = string
  default     = ""
}

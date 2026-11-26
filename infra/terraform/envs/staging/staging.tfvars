# 비밀 값 없음 → 커밋한다. 비밀(DB 비밀번호 등)은 Terraform이 random_password로 만들어 K8s Secret에 넣는다 (ADR-10)
region = "ap-northeast-2"
env    = "staging"
name   = "cinema-staging"
owner  = "kimjongsik"

vpc_cidr       = "10.20.0.0/16"
azs            = ["ap-northeast-2a", "ap-northeast-2c"]
public_subnets = ["10.20.0.0/24", "10.20.1.0/24"]
app_subnets    = ["10.20.16.0/20", "10.20.32.0/20"]
data_subnets   = ["10.20.48.0/24", "10.20.49.0/24"]

# EKS (3.1.2)
eks_version           = "1.36"
node_instance_type    = "t3.xlarge" # ng-a·ng-c (11/14 t3.large → t3.xlarge, 4.2.1 후속). 부족하면 한 단계씩(t3.2xlarge)
ai_node_instance_type = "t3.large"  # ng-ai — 기본 노드와 따로
developer_namespaces  = ["cinema-staging", "monitoring"]

# 6.1.2 가용성(ADR-122): NAT AZ별 · Valkey 2개 복제본 + 자동 장애 조치. NAT는 런북 5-6 순서로 apply
nat_gateway_per_az = true
valkey_replicas    = 1
ai_valkey_replicas = 1

# RDS·Valkey (3.1.3)
rds_engine_version    = "8.4"
rds_instance_class    = "db.t4g.small"
valkey_engine_version = "8.2"
valkey_node_type      = "cache.t4g.micro"

# 6.1.1 서비스별 RDS 인스턴스 (ADR-115, docs/data-model.md 7-1). 기존 cinema-staging-mysql은 다 옮길 때까지 둔다(런북 8-E)
service_databases = {
  booking = { instance_class = "db.t4g.small", multi_az = true, connection_alarm = 130 }
  payment = { instance_class = "db.t4g.micro", multi_az = true, connection_alarm = 45 }
  user    = { instance_class = "db.t4g.micro", multi_az = true, connection_alarm = 45 }
  catalog = { instance_class = "db.t4g.micro", multi_az = true, connection_alarm = 45 }
  coupon  = { instance_class = "db.t4g.micro", multi_az = true, connection_alarm = 45 }
  ai      = { instance_class = "db.t4g.micro", multi_az = false, connection_alarm = 45 }
}

# 3.1.23 사용자 도메인(선택). Route 53에서 등록한 뒤에만 채운다(런북 7-7). 비우면 CloudFront 기본 도메인만
custom_domain = "seatsac.com"

# 3.1.23 후속 CloudFront → ALB HTTPS(런북 7-8). off → prepare → (gitops 443 리스너) → on 순서로 한 단계씩 PR
api_origin_https = "on"

# 6.1.11 Cognito 그룹 지정(ADR-118·119): 시연 계정의 sub(UUID)만 넣는다 — 이메일 금지. sub 얻기·확인은 런북 7-6-5
#   VIP = 쿠폰 대상(X-User-Grade: VIP) · ADMIN = 관리자 /ops(서비스 정지/가동 가능 — 시연 계정만)
cognito_group_members = {
  VIP   = ["d4084d7c-60d1-70e8-7ae2-dc2cbeb06968"] # 시연 계정 1 (김종식) — 쿠폰 확인·시연
  ADMIN = ["d4084d7c-60d1-70e8-7ae2-dc2cbeb06968"] # 같은 계정 — 관리자 /ops 시연
}

# 6.1.6 Karpenter(ADR-122): 컨트롤러 권한·노드 역할·중단 알림 큐·밤 정리 Lambda. 다음은 envs/staging-k8s karpenter_enabled → gitops platform/karpenter (런북 6-K)
karpenter_enabled = true

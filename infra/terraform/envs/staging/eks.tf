# ------------------------------------------------------------------
# 3.1.2 EKS 클러스터 · 노드그룹 · 애드온 · 팀 접근(Access Entry) · ALB 준비
#   - Kubernetes 1.36 (표준 지원 ~2027-08), 인증 모드 API(Access Entry만, aws-auth ConfigMap 없음)
#   - 노드: On-Demand t3.large × 2 = ng-a(AZ a) 1대 + ng-c(AZ c) 1대 (ADR-7·34)
#     밤·주말 0대는 cluster-schedule 워크플로가 desired_size만 바꾼다(3.1.14). 모듈이 desired_size 변경을 무시하므로 drift 없음
#   - 애드온: vpc-cni(NetworkPolicy 켬) · coredns · kube-proxy · eks-pod-identity-agent · metrics-server(HPA)
#   - 파드 권한은 Pod Identity만 (IRSA 안 씀)
#   - ALB 자체는 gateway Ingress가 만들 때 AWS Load Balancer Controller가 생성한다.
#     여기서는 컨트롤러 IAM(Pod Identity)과 노드 SG 규칙만. 컨트롤러 설치는 envs/staging-k8s
# ------------------------------------------------------------------

module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 21.0"

  name               = "${var.name}-eks" # cinema-staging-eks
  kubernetes_version = var.eks_version

  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnets # 컨트롤 플레인 ENI·노드 모두 app 서브넷

  # KMS 키 관리자를 실행 주체가 아니라 cinema-admin으로 고정 (CI plan 때 관리자가 tf-plan 역할로 바뀌는 차이 방지)
  kms_key_administrators = [local.admin_role_arn]

  # API 엔드포인트: Bastion 없이 팀원 노트북에서 kubectl → 공개 엔드포인트 + IAM 인증.
  # 노드는 private 엔드포인트로 붙는다
  endpoint_public_access       = true
  endpoint_private_access      = true
  endpoint_public_access_cidrs = var.eks_public_access_cidrs

  # Access Entry만 사용. 클러스터를 만든 주체(apply 실행자)에게 자동 관리자 권한을 주지 않고
  # 아래 access_entries로 명시한다 → apply는 반드시 cinema-admin 역할로
  authentication_mode                      = "API"
  enable_cluster_creator_admin_permissions = false

  # 컨트롤 플레인 로그: 감사·인증만, 7일 (비용)
  enabled_log_types                      = ["audit", "authenticator"]
  cloudwatch_log_group_retention_in_days = 7

  # 파드 권한은 Pod Identity만 → 클러스터 IAM OIDC 공급자(IRSA)를 만들지 않는다
  enable_irsa = false

  # K8s Secret(3.1.12 DB 비밀번호)은 모듈 기본값대로 KMS 키로 봉투 암호화 (키 월 $1)

  addons = {
    vpc-cni = {
      before_compute = true # 노드보다 먼저 (없으면 노드가 NotReady)
      most_recent    = false
      addon_version  = "v1.23.2-eksbuild.3"
      configuration_values = jsonencode({
        enableNetworkPolicy = "true" # NetworkPolicy(3.1.13) — VPC CNI 네트워크 정책 에이전트
      })
    }
    eks-pod-identity-agent = { before_compute = true, most_recent = false, addon_version = "v1.4.0-eksbuild.4" }
    kube-proxy             = { most_recent = false, addon_version = "v1.36.0-eksbuild.48" }
    coredns                = { most_recent = false, addon_version = "v1.14.7-eksbuild.11" }
    metrics-server         = { most_recent = false, addon_version = "v0.9.0-eksbuild.13" }
  }

  eks_managed_node_groups = {
    for az, idx in { a = 0, c = 1 } : "ng-${az}" => {
      name             = "${var.name}-ng-${az}"
      ami_type         = "AL2023_x86_64_STANDARD"
      instance_types   = [var.node_instance_type]
      capacity_type    = "ON_DEMAND"

      subnet_ids = [module.vpc.private_subnets[idx]] # AZ마다 노드그룹 하나 → AZ별로 켜고 끄기

      min_size     = 0 # 밤·주말 0대
      max_size     = 2 # 부하 테스트 때 수동 증설(OPS-06·10)
      desired_size = 1

      update_config = { max_unavailable = 1 }

      labels = {
        "cinema/az" = az
      }

      # 노드 SSH 대신 SSM 세션(디버깅용). 키 페어 없음
      iam_role_additional_policies = {
        ssm = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
      }
    }
  }

  access_entries = {
    # 김종식 + 백업 관리자(ADR-47): 클러스터 전체 관리자
    admin = {
      principal_arn = local.admin_role_arn
      policy_associations = {
        cluster_admin = {
          policy_arn   = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy"
          access_scope = { type = "cluster" }
        }
      }
    }
    # 팀원 전원: 앱·모니터링 namespace 조회·로그만 (Secret 조회·변경·삭제 불가).
    # port-forward는 쿠버네티스 그룹 cinema-developers에 Role을 따로 준다 (envs/staging-k8s/rbac.tf)
    developer = {
      principal_arn     = local.developer_role_arn
      kubernetes_groups = ["cinema-developers"]
      policy_associations = {
        view = {
          policy_arn = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSViewPolicy"
          access_scope = {
            type       = "namespace"
            namespaces = var.developer_namespaces
          }
        }
      }
    }
  }

  tags = {
    Name = "${var.name}-eks"
  }
}

# ---- ALB → 파드 (target-type ip) --------------------------------------
# gateway 파드(8080)만 ALB에서 받는다. 파드 IP는 노드 ENI의 보조 IP라 노드 SG가 적용된다.
# Ingress에는 alb.ingress.kubernetes.io/security-groups = sg_alb_id,
# manage-backend-security-group-rules = "false" 로 둔다(규칙은 여기서 관리)
resource "aws_vpc_security_group_ingress_rule" "node_from_alb_8080" {
  security_group_id            = module.eks.node_security_group_id
  description                  = "ALB to gateway pods 8080 (target-type ip)"
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
  referenced_security_group_id = aws_security_group.alb.id
}

# ---- AWS Load Balancer Controller 권한 (Pod Identity) -----------------
module "lb_controller_pod_identity" {
  source  = "terraform-aws-modules/eks-pod-identity/aws"
  version = "~> 2.0"

  name = "${var.name}-lb-controller-pod" # cinema-staging-lb-controller-pod (03a 5️⃣)

  attach_aws_lb_controller_policy = true

  associations = {
    this = {
      cluster_name    = module.eks.cluster_name
      namespace       = "kube-system"
      service_account = "aws-load-balancer-controller"
    }
  }
}

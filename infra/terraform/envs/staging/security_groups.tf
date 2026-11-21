# ------------------------------------------------------------------
# 3.1.1 보안그룹
#   alb     : CloudFront 관리형 접두사 목록에서만 80 (+ api_origin_https 켜면 443) (ALB 직접 접근 차단)
#   rds     : app 서브넷 대역 + Lambda SG 에서 3306
#   valkey  : app 서브넷 대역에서 6379
#   lambda  : 일별 집계 Lambda(VPC 연결) → RDS 3306 만 나감
#   EKS 노드·파드 SG는 EKS 모듈이 만든다 (3.1.2). 파드는 app 서브넷 IP를 쓰므로
#   데이터 계층은 CIDR 기준으로 열고, 파드 간 제어는 NetworkPolicy(3.1.13)로 한다.
#   ※ Terraform이 만든 SG는 기본 egress(allow all)를 제거한다 → 필요한 egress만 명시
# ------------------------------------------------------------------

# CloudFront → 원본(ALB) 방향 IP 목록 (AWS 관리, 자동 갱신)
data "aws_ec2_managed_prefix_list" "cloudfront_origin_facing" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

# ---- ALB -------------------------------------------------------------
resource "aws_security_group" "alb" {
  name        = "${var.name}-alb"
  description = "ALB: CloudFront origin-facing prefix list only"
  vpc_id      = module.vpc.vpc_id
  tags        = { Name = "${var.name}-alb" }
}

# 3.1.23 후속(origin_https.tf): HTTPS 원본을 준비하면 이 규칙 하나를 80~443으로 넓힌다(제자리 변경 — 80은 끊기지 않는다).
# 443 규칙을 따로 만들지 않는 이유: CloudFront 접두사 목록은 규칙 하나가 55개로 계산되고 SG 규칙 할당량은 60이라
# 규칙 2개(110)는 만들 수 없다. ALB 리스너는 80·443뿐이라 사이 포트는 열려 있어도 받는 것이 없다. 80 정리는 런북 7-8 4번
resource "aws_vpc_security_group_ingress_rule" "alb_from_cloudfront_80" {
  security_group_id = aws_security_group.alb.id
  description       = local.api_origin_https_prepare ? "CloudFront to ALB /api/* origin (HTTP 80, HTTPS 443)" : "CloudFront to ALB /api/* origin (HTTP)"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = local.api_origin_https_prepare ? 443 : 80
  prefix_list_id    = data.aws_ec2_managed_prefix_list.cloudfront_origin_facing.id
}

resource "aws_vpc_security_group_egress_rule" "alb_to_vpc" {
  security_group_id = aws_security_group.alb.id
  description       = "ALB to pods (target-type ip)"
  ip_protocol       = "-1"
  cidr_ipv4         = var.vpc_cidr
}

# ---- RDS MySQL -------------------------------------------------------
resource "aws_security_group" "rds" {
  name        = "${var.name}-rds"
  description = "RDS MySQL 3306 from app subnets and stats Lambda"
  vpc_id      = module.vpc.vpc_id
  tags        = { Name = "${var.name}-rds" }
}

resource "aws_vpc_security_group_ingress_rule" "rds_from_app" {
  for_each          = toset(var.app_subnets)
  security_group_id = aws_security_group.rds.id
  description       = "EKS pods ${each.value} to 3306"
  ip_protocol       = "tcp"
  from_port         = 3306
  to_port           = 3306
  cidr_ipv4         = each.value
}

# ---- ElastiCache Valkey ------------------------------------------------
resource "aws_security_group" "valkey" {
  name        = "${var.name}-valkey"
  description = "ElastiCache Valkey 6379 (TLS) from app subnets"
  vpc_id      = module.vpc.vpc_id
  tags        = { Name = "${var.name}-valkey" }
}

resource "aws_vpc_security_group_ingress_rule" "valkey_from_app" {
  for_each          = toset(var.app_subnets)
  security_group_id = aws_security_group.valkey.id
  description       = "EKS pods ${each.value} to 6379"
  ip_protocol       = "tcp"
  from_port         = 6379
  to_port           = 6379
  cidr_ipv4         = each.value
}

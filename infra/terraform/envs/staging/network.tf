# ------------------------------------------------------------------
# 3.1.1 VPC · 서브넷 · NAT · IGW · 라우팅 · S3 Gateway Endpoint
#   - VPC 10.20.0.0/16, AZ 2개(a, c)
#   - public (ALB·NAT) / private app (EKS) / private data (RDS·Valkey)
#   - NAT Gateway 1개 (결정 26: NAT B 제외, -$46)
#   - 공식 VPC 모듈: 라우트 테이블·연결·EIP·IGW를 알아서 만든다
# ------------------------------------------------------------------
module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 6.0"

  name = var.name
  cidr = var.vpc_cidr
  azs  = var.azs

  public_subnets   = var.public_subnets # 모듈 이름: public
  private_subnets  = var.app_subnets    # 모듈 이름: private  (= private app)
  database_subnets = var.data_subnets   # 모듈 이름: database (= private data)

  # NAT: 1개를 첫 AZ(a)에 두고 두 AZ의 private 라우트가 함께 쓴다
  enable_nat_gateway     = true
  single_nat_gateway     = true
  one_nat_gateway_per_az = false

  # EKS·RDS가 요구
  enable_dns_hostnames = true
  enable_dns_support   = true

  # RDS 서브넷 그룹을 미리 만든다 (3.1.3에서 참조). data 서브넷은 NAT 라우트 없음(외부 통신 불필요)
  create_database_subnet_group       = true
  database_subnet_group_name         = "${var.name}-db"
  create_database_subnet_route_table = true

  map_public_ip_on_launch = false

  # AWS Load Balancer Controller가 서브넷을 자동으로 찾는 태그
  public_subnet_tags = {
    "kubernetes.io/role/elb" = "1"
  }
  private_subnet_tags = {
    "kubernetes.io/role/internal-elb" = "1"
  }

  public_subnet_names   = ["${var.name}-public-a", "${var.name}-public-c"]
  private_subnet_names  = ["${var.name}-app-a", "${var.name}-app-c"]
  database_subnet_names = ["${var.name}-data-a", "${var.name}-data-c"]
}

# S3 Gateway Endpoint (무료): ECR 이미지 레이어·S3 접근이 NAT를 타지 않게
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = module.vpc.vpc_id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids = concat(
    module.vpc.private_route_table_ids,
    module.vpc.database_route_table_ids,
  )

  tags = { Name = "${var.name}-s3-gw" }
}

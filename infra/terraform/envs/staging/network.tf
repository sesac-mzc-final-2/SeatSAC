# ------------------------------------------------------------------
# 3.1.1 VPC · 서브넷 · NAT · IGW · 라우팅 · S3 Gateway Endpoint
#   - VPC 10.20.0.0/16, AZ 2개(a, c)
#   - public (ALB·NAT) / private app (EKS) / private data (RDS·Valkey)
#   - NAT Gateway: 기본 1개(AZ a, 결정 26). 6.1.2(ADR-122) var.nat_gateway_per_az = true면 AZ마다 1개(a·c)
#     → AZ a가 죽어도 AZ c 파드의 바깥 호출(SQS·SNS·Secrets Manager·ECR·Cognito·Claude·EC2 API)이 유지된다
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

  # NAT: false면 1개를 첫 AZ(a)에 두고 두 AZ의 private 라우트가 함께 쓴다. true면 AZ마다 NAT·private 라우트 테이블이 따로다
  #   false → true로 바꿀 때는 런북 5-6 순서(새 NAT·라우트를 먼저 만들고 서브넷 연결을 옮김)로 apply한다 —
  #   한 번에 apply하면 app-c 서브넷이 NAT 라우트가 아직 없는 새 라우트 테이블로 먼저 옮겨져 몇 분 동안 바깥 호출이 끊길 수 있다
  enable_nat_gateway     = true # 매일 아침에 켜고, 밤에 끈다 (노드가 살아있으면 끄면 안됨)
  single_nat_gateway     = !var.nat_gateway_per_az
  one_nat_gateway_per_az = var.nat_gateway_per_az

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

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
eks_version          = "1.36"
node_instance_type   = "t3.large"
developer_namespaces = ["cinema-staging", "monitoring"]

# RDS·Valkey (3.1.3)
rds_engine_version    = "8.4"
rds_instance_class    = "db.t4g.small"
valkey_engine_version = "8.2"
valkey_node_type      = "cache.t4g.micro"
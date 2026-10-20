# 비밀 값 없음 → 커밋한다. 비밀(DB 비밀번호 등)은 Terraform이 random_password로 만들어 K8s Secret에 넣는다 (ADR-10)
region = "ap-northeast-2"
env    = "staging"
name   = "seatsac-staging"
owner  = "kimjongsik"

vpc_cidr       = "10.20.0.0/16"
azs            = ["ap-northeast-2a", "ap-northeast-2c"]
public_subnets = ["10.20.0.0/24", "10.20.1.0/24"]
app_subnets    = ["10.20.16.0/20", "10.20.32.0/20"]
data_subnets   = ["10.20.48.0/24", "10.20.49.0/24"]

# ------------------------------------------------------------------
# 3.1.3 RDS MySQL 8.4 (ADR-3·33·61)
#   - 1대, Single-AZ, data 서브넷(외부 라우트 없음), 퍼블릭 접근 없음
#   - 파라미터: time_zone=UTC, utf8mb4 / utf8mb4_0900_ai_ci, TLS 강제 (JDBC sslMode=REQUIRED)
#   - 마스터 비밀번호는 RDS가 Secrets Manager에 만들고 관리(manage_master_user_password)
#     → tfstate·tfvars·코드에 비밀번호가 없다. 앱·Flyway Job은 마스터를 쓰지 않는다
#     ({svc}_app·{svc}_migrator 계정·스키마는 3.1.12에서 클러스터 안 Job으로 생성 — 로컬에서는 RDS에 닿지 않음)
#   - 접근: SG rds (app 서브넷 → 3306, 3.1.1)
# ------------------------------------------------------------------

resource "aws_db_parameter_group" "mysql" {
  name        = "${var.name}-mysql84"
  family      = "mysql8.4"
  description = "cinema staging MySQL 8.4: UTC, utf8mb4, TLS required"

  parameter {
    name  = "time_zone"
    value = "UTC"
  }
  parameter {
    name  = "character_set_server"
    value = "utf8mb4"
  }
  parameter {
    name  = "collation_server"
    value = "utf8mb4_0900_ai_ci"
  }
  parameter {
    name  = "require_secure_transport"
    value = "1"
  }
  # 느린 쿼리 0.5초 이상 → CloudWatch Logs (선점 p95 300ms 목표 조사용)
  parameter {
    name  = "slow_query_log"
    value = "1"
  }
  parameter {
    name  = "long_query_time"
    value = "0.5"
  }

  lifecycle {
    create_before_destroy = true
  }
}

# RDS가 로그 그룹을 자동으로 만들면 보존 기간이 "무기한" → 미리 7일로 만든다
resource "aws_cloudwatch_log_group" "mysql" {
  for_each          = toset(["error", "slowquery"])
  name              = "/aws/rds/instance/${var.name}-mysql/${each.key}"
  retention_in_days = 7
}

resource "aws_db_instance" "mysql" {
  identifier     = "${var.name}-mysql" # cinema-staging-mysql
  engine         = "mysql"
  engine_version = var.rds_engine_version # "8.4" → AWS 기본 8.4.x. 마이너 자동 업그레이드는 끔(데모 안정)
  instance_class = var.rds_instance_class

  allocated_storage     = 20
  max_allocated_storage = 50 # 저장소 자동 확장 한도
  storage_type          = "gp3"
  storage_encrypted     = true

  db_name                     = null # 스키마 4개는 3.1.12에서 생성
  username                    = "cinema_master"
  manage_master_user_password = true # Secrets Manager (값은 tfstate에 없음)

  db_subnet_group_name   = module.vpc.database_subnet_group_name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false
  multi_az               = false # ADR-61 Single-AZ
  availability_zone      = var.azs[0]
  parameter_group_name   = aws_db_parameter_group.mysql.name

  backup_retention_period  = 7
  backup_window            = "18:00-18:30"         # UTC = 03:00~03:30 KST
  maintenance_window       = "sun:18:30-sun:19:30" # UTC = 월 03:30~04:30 KST
  delete_automated_backups = false                 # 인스턴스를 지워도 자동 백업은 보존 기간 동안 남김

  auto_minor_version_upgrade = false
  apply_immediately          = true # staging: 파라미터·클래스 변경을 다음 점검 시간까지 미루지 않음

  # 재생성 리허설(OPS-14)에서 destroy 가능해야 한다. 데이터는 시드로 다시 만들 수 있고, 자동 백업은 위에서 보존
  deletion_protection = false
  skip_final_snapshot = true

  enabled_cloudwatch_logs_exports = ["error", "slowquery"]
  performance_insights_enabled    = false
  copy_tags_to_snapshot           = true

  tags = { Name = "${var.name}-mysql" }

  depends_on = [aws_cloudwatch_log_group.mysql]
}

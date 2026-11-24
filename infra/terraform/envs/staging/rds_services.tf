# ------------------------------------------------------------------
# 6.1.1 서비스별 RDS 인스턴스 (ADR-115 — ADR-3·33·61 대체)
#   - cinema-staging-{svc}-db : 서비스마다 인스턴스 하나, 스키마 {svc}_db 하나(스키마·계정 이름은 그대로)
#   - 클래스·Multi-AZ·연결 수 경보는 var.service_databases (docs/data-model.md 7-1 표와 같은 값)
#   - 파라미터 그룹(UTC·utf8mb4·TLS 강제)·보안그룹(app 서브넷 → 3306)·서브넷 그룹은 기존 것을 같이 쓴다
#   - 마스터 비밀번호는 인스턴스마다 RDS 관리 Secrets Manager → tfstate·코드에 없다
#   - 스키마·계정은 인스턴스별 db-bootstrap Job(envs/staging-k8s/db_bootstrap.tf)이 만든다
#   - 기존 cinema-staging-mysql(rds.tf)은 그대로 둔다 — 서비스를 하나씩 옮긴 뒤 별도 PR로 지운다(런북 8-E)
#     확장(이 파일) → 전환(덤프·복원 + gitops DB_HOST_{SVC}) → 정리(rds.tf 삭제)
# ------------------------------------------------------------------

locals {
  service_db_log_groups = {
    for p in setproduct(keys(var.service_databases), ["error", "slowquery"]) : "${p[0]}/${p[1]}" => { svc = p[0], log = p[1] }
  }
}

# RDS가 로그 그룹을 자동으로 만들면 보존 기간이 "무기한" → 미리 7일로 만든다 (rds.tf와 같은 이유)
resource "aws_cloudwatch_log_group" "service_db" {
  for_each          = local.service_db_log_groups
  name              = "/aws/rds/instance/${var.name}-${each.value.svc}-db/${each.value.log}"
  retention_in_days = 7
}

resource "aws_db_instance" "service" {
  for_each = var.service_databases

  identifier     = "${var.name}-${each.key}-db" # cinema-staging-booking-db
  engine         = "mysql"
  engine_version = var.rds_engine_version
  instance_class = each.value.instance_class

  allocated_storage     = 20
  max_allocated_storage = 50
  storage_type          = "gp3"
  storage_encrypted     = true

  db_name                     = null # 스키마는 db-bootstrap Job이 만든다
  username                    = "cinema_master"
  manage_master_user_password = true

  db_subnet_group_name   = module.vpc.database_subnet_group_name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false
  multi_az               = each.value.multi_az
  availability_zone      = each.value.multi_az ? null : var.azs[0] # Multi-AZ는 AZ를 지정할 수 없다
  parameter_group_name   = aws_db_parameter_group.mysql.name

  backup_retention_period  = 7
  backup_window            = "18:00-18:30"         # UTC = 03:00~03:30 KST
  maintenance_window       = "sun:18:30-sun:19:30" # UTC = 월 03:30~04:30 KST
  delete_automated_backups = false

  auto_minor_version_upgrade = false
  apply_immediately          = true

  deletion_protection = false # 재생성 리허설(OPS-14)·계정 이전(ADR-125)에서 destroy 가능해야 한다
  skip_final_snapshot = true

  enabled_cloudwatch_logs_exports = ["error", "slowquery"]
  performance_insights_enabled    = false
  copy_tags_to_snapshot           = true

  tags = { Name = "${var.name}-${each.key}-db" }

  depends_on = [aws_cloudwatch_log_group.service_db]
}

# ---- 경보 (인스턴스마다 CPU·연결 수·남은 저장소) → SNS cinema-alerts ----------
locals {
  service_db_alarms = merge([
    for svc, db in var.service_databases : {
      "${svc}-db-cpu-80" = {
        svc         = svc
        metric      = "CPUUtilization"
        statistic   = "Average"
        threshold   = 80
        periods     = 3
        comparison  = "GreaterThanThreshold"
        description = "${svc} RDS CPU 80% 초과 (15분)"
      }
      "${svc}-db-connections" = {
        svc         = svc
        metric      = "DatabaseConnections"
        statistic   = "Maximum"
        threshold   = db.connection_alarm
        periods     = 2
        comparison  = "GreaterThanThreshold"
        description = "${svc} RDS 연결 수가 한도에 가까움 — Hikari 풀 × 파드 수 확인 (data-model 7-1)"
      }
      "${svc}-db-free-storage-2gb" = {
        svc         = svc
        metric      = "FreeStorageSpace"
        statistic   = "Minimum"
        threshold   = 2 * 1024 * 1024 * 1024
        periods     = 1
        comparison  = "LessThanThreshold"
        description = "${svc} RDS 남은 저장소 2GB 미만 (자동 확장 한도 50GB)"
      }
    }
  ]...)
}

resource "aws_cloudwatch_metric_alarm" "service_db" {
  for_each = local.service_db_alarms

  alarm_name          = "${var.name}-${each.key}"
  alarm_description   = each.value.description
  namespace           = "AWS/RDS"
  metric_name         = each.value.metric
  dimensions          = { DBInstanceIdentifier = aws_db_instance.service[each.value.svc].identifier }
  statistic           = each.value.statistic
  period              = 300
  evaluation_periods  = each.value.periods
  threshold           = each.value.threshold
  comparison_operator = each.value.comparison
  treat_missing_data  = "notBreaching"
  alarm_actions       = [data.aws_sns_topic.alerts.arn]
  ok_actions          = [data.aws_sns_topic.alerts.arn]
}

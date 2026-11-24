# ------------------------------------------------------------------
# 3.1.3 데이터 계층 경보 → SNS cinema-alerts(global) → 메일
#   - Valkey 메모리 80%·Evictions > 0 (ADR-59), RDS CPU 80%·남은 저장소 2GB 미만·연결 수
#   - SNS 주제·메일 구독은 global(alerts.tf)에 둔다: 메일 주소가 staging plan(공개 PR 코멘트)에 나오지 않고,
#     CI plan에 메일 변수가 없어 생기는 구독 삭제 차이도 없다
#   - 노드를 꺼 둔 밤에도 RDS·Valkey는 켜져 있으므로 경보는 계속 동작
# ------------------------------------------------------------------

data "aws_sns_topic" "alerts" {
  name = "cinema-alerts" # infra/terraform/global/alerts.tf
}

locals {
  # 노드 ID는 {복제 그룹}-001, -002 … 로 정해진다(member_clusters는 apply 뒤에야 알 수 있어 for_each 키로 쓸 수 없다)
  valkey_cluster_id = "${aws_elasticache_replication_group.valkey.replication_group_id}-001"

  data_alarm_defs = {
    valkey-memory-80 = {
      namespace   = "AWS/ElastiCache"
      metric      = "DatabaseMemoryUsagePercentage"
      dimensions  = { CacheClusterId = local.valkey_cluster_id }
      statistic   = "Maximum"
      threshold   = 80
      periods     = 2
      description = "Valkey 메모리 80% 초과 — noeviction이라 가득 차면 선점 쓰기가 실패한다. t4g.small 검토 (ADR-59)"
    }
    valkey-evictions = {
      namespace   = "AWS/ElastiCache"
      metric      = "Evictions"
      dimensions  = { CacheClusterId = local.valkey_cluster_id }
      statistic   = "Sum"
      threshold   = 0
      periods     = 1
      description = "Valkey Evictions 발생 — noeviction인데 0이 아니면 파라미터 그룹이 적용되지 않은 것"
    }
    rds-cpu-80 = {
      namespace   = "AWS/RDS"
      metric      = "CPUUtilization"
      dimensions  = { DBInstanceIdentifier = aws_db_instance.mysql.identifier }
      statistic   = "Average"
      threshold   = 80
      periods     = 3
      description = "RDS CPU 80% 초과 (15분)"
    }
    rds-connections = {
      namespace   = "AWS/RDS"
      metric      = "DatabaseConnections"
      dimensions  = { DBInstanceIdentifier = aws_db_instance.mysql.identifier }
      statistic   = "Maximum"
      threshold   = var.rds_connection_alarm
      periods     = 2
      description = "RDS 연결 수가 한도에 가까움 — Hikari 풀(파드당 5) × 파드 수 확인"
    }
  }

  # 6.1.2: 복제본(002)은 장애 조치 뒤 primary가 될 수 있어 Valkey 경보를 -002로 하나씩 더 둔다(001은 이전과 같은 이름)
  data_alarms = merge(
    local.data_alarm_defs,
    var.valkey_replicas > 0 ? {
      for k, v in local.data_alarm_defs : "${k}-002" => merge(v, {
        dimensions = { CacheClusterId = "${aws_elasticache_replication_group.valkey.replication_group_id}-002" }
      }) if v.namespace == "AWS/ElastiCache"
    } : {},
  )
}

resource "aws_cloudwatch_metric_alarm" "data" {
  for_each = local.data_alarms

  alarm_name          = "${var.name}-${each.key}"
  alarm_description   = each.value.description
  namespace           = each.value.namespace
  metric_name         = each.value.metric
  dimensions          = each.value.dimensions
  statistic           = each.value.statistic
  period              = 300
  evaluation_periods  = each.value.periods
  threshold           = each.value.threshold
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [data.aws_sns_topic.alerts.arn]
  ok_actions          = [data.aws_sns_topic.alerts.arn]
}

resource "aws_cloudwatch_metric_alarm" "rds_storage" {
  alarm_name          = "${var.name}-rds-free-storage-2gb"
  alarm_description   = "RDS 남은 저장소 2GB 미만 (자동 확장 한도 50GB)"
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.mysql.identifier }
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 2 * 1024 * 1024 * 1024
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [data.aws_sns_topic.alerts.arn]
  ok_actions          = [data.aws_sns_topic.alerts.arn]
}

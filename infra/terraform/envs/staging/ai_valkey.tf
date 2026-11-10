# ------------------------------------------------------------------
# ⚠️AI 3.1.18 ai 전용 ElastiCache Valkey (ADR-85, 런타임 계약 9장)
#   - 기존 Valkey(valkey.tf, 좌석 선점)와 다른 클러스터: AI 부하·메모리가 좌석 데모에 영향을 주지 않게
#   - 용도: 정확 일치 캐시 · 의미 캐시(벡터 검색) · 세션 · 사용 한도. 원본 데이터 없음(휘발성) → 스냅샷·복제본 없음
#   - noeviction + 모든 키 TTL(앱 책임): allkeys-lru면 메모리가 찰 때 한도 키(ai:quota:*·ai:inflight:*)도 지워져 한도를 우회할 수 있다
#   - reserved-memory-percent 50: t4g.micro에서 벡터 검색(인덱스) 메모리 여유
#   - 접근: 노드 SG에서만 6379 (ai 파드는 ng-ai 노드 — 같은 노드 SG). 기존 valkey SG(app 서브넷 대역)와 분리
#   - 경보: 메모리 80%·Evictions > 0 → SNS cinema-alerts (alerts.tf와 같은 기준)
#   - 비용: 약 $12/월 (24시간, 노드처럼 끌 수 없다)
# ------------------------------------------------------------------

resource "aws_security_group" "ai_valkey" {
  name        = "${var.name}-ai-valkey"
  description = "ai ElastiCache Valkey 6379 (TLS) from EKS node SG"
  vpc_id      = module.vpc.vpc_id
  tags        = { Name = "${var.name}-ai-valkey" }
}

resource "aws_vpc_security_group_ingress_rule" "ai_valkey_from_nodes" {
  security_group_id            = aws_security_group.ai_valkey.id
  description                  = "EKS nodes (ai pods on ng-ai) to 6379"
  ip_protocol                  = "tcp"
  from_port                    = 6379
  to_port                      = 6379
  referenced_security_group_id = module.eks.node_security_group_id
}

resource "aws_elasticache_parameter_group" "ai_valkey" {
  name        = "${var.name}-ai-valkey8"
  family      = "valkey8"
  description = "cinema staging ai Valkey 8: noeviction, reserved memory 50 (vector search)"

  parameter {
    name  = "maxmemory-policy"
    value = "noeviction"
  }
  parameter {
    name  = "reserved-memory-percent"
    value = "50"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_elasticache_replication_group" "ai_valkey" {
  replication_group_id = "${var.name}-ai-valkey" # cinema-staging-ai-valkey (docs/conventions.md 9장)
  description          = "cinema staging ai Valkey (cache, semantic cache, session, quota)"

  engine               = "valkey"
  engine_version       = var.valkey_engine_version # 8.2 — 벡터 검색 지원
  node_type            = var.ai_valkey_node_type
  num_cache_clusters   = 1
  parameter_group_name = aws_elasticache_parameter_group.ai_valkey.name
  port                 = 6379

  subnet_group_name           = aws_elasticache_subnet_group.valkey.name # data 서브넷(기존과 같은 그룹)
  security_group_ids          = [aws_security_group.ai_valkey.id]
  preferred_cache_cluster_azs = [var.azs[0]]

  automatic_failover_enabled = false
  multi_az_enabled           = false

  transit_encryption_enabled = true # 앱: APP_AI_VALKEY_TLS=true
  transit_encryption_mode    = "required"
  at_rest_encryption_enabled = true

  snapshot_retention_limit   = 0
  maintenance_window         = "sun:19:30-sun:20:30" # UTC = 월 04:30~05:30 KST
  auto_minor_version_upgrade = false
  apply_immediately          = true

  tags = { Name = "${var.name}-ai-valkey" }
}

locals {
  ai_valkey_cluster_id = tolist(aws_elasticache_replication_group.ai_valkey.member_clusters)[0]

  ai_valkey_alarms = {
    ai-valkey-memory-80 = {
      metric      = "DatabaseMemoryUsagePercentage"
      statistic   = "Maximum"
      threshold   = 80
      periods     = 2
      description = "ai Valkey 메모리 80% 초과 — noeviction이라 가득 차면 캐시·한도 쓰기가 실패한다. TTL 없는 키·캐시 TTL 확인, 필요하면 t4g.small (ADR-85)"
    }
    ai-valkey-evictions = {
      metric      = "Evictions"
      statistic   = "Sum"
      threshold   = 0
      periods     = 1
      description = "ai Valkey Evictions 발생 — noeviction인데 0이 아니면 파라미터 그룹이 적용되지 않은 것"
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "ai_valkey" {
  for_each = local.ai_valkey_alarms

  alarm_name          = "${var.name}-${each.key}"
  alarm_description   = each.value.description
  namespace           = "AWS/ElastiCache"
  metric_name         = each.value.metric
  dimensions          = { CacheClusterId = local.ai_valkey_cluster_id }
  statistic           = each.value.statistic
  period              = 300
  evaluation_periods  = each.value.periods
  threshold           = each.value.threshold
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [data.aws_sns_topic.alerts.arn]
  ok_actions          = [data.aws_sns_topic.alerts.arn]
}

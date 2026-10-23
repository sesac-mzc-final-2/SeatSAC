# ------------------------------------------------------------------
# 3.1.3 ElastiCache Valkey 8.2 (ADR-33·44·59)
#   - cache.t4g.micro 1노드(복제본 없음), data 서브넷, 전송 중·저장 암호화
#   - noeviction: 선점 키(seat:*·user-hold:*)가 메모리 부족으로 밀려나면 이중 선점 위험 → 차라리 쓰기 오류
#   - TTL 없는 키(conventions 4장): hold-expiry(sorted set, 앱이 ZREM으로 정리)·seat-seq:{회차}(회차별 일련번호, 계속 남음).
#     noeviction이라 지워지지 않는다(의도). 회차 수 × 수십 바이트라 데모 기간 메모리 영향은 작지만,
#     hold-expiry가 ZREM 누락으로 계속 커지면 메모리 80% 경보로 드러난다 → ZCARD hold-expiry 확인
#   - notify-keyspace-events Ex: 선점 만료(expired) 이벤트 → booking 만료 처리 (ADR-44)
#   - 경보: 메모리 80%·Evictions > 0 (alerts.tf). 부하 테스트에서 70% 넘으면 t4g.small (ADR-59)
#   - 접근: SG valkey (app 서브넷 → 6379, 3.1.1). AUTH 토큰은 두지 않음(VPC·SG·TLS로 제한, 앱 설정에 비밀번호 없음)
# ------------------------------------------------------------------

resource "aws_elasticache_subnet_group" "valkey" {
  name       = "${var.name}-valkey"
  subnet_ids = module.vpc.database_subnets
}

resource "aws_elasticache_parameter_group" "valkey" {
  name        = "${var.name}-valkey8"
  family      = "valkey8"
  description = "cinema staging Valkey 8: noeviction, keyspace expired events"

  parameter {
    name  = "maxmemory-policy"
    value = "noeviction"
  }
  parameter {
    name  = "notify-keyspace-events"
    value = "Ex" # E(keyevent 채널) + x(expired). K·E 중 하나가 없으면 알림이 조용히 꺼진다
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_elasticache_replication_group" "valkey" {
  replication_group_id = "${var.name}-valkey" # cinema-staging-valkey
  description          = "cinema staging Valkey (seat hold, cache, pub/sub)"

  engine               = "valkey"
  engine_version       = var.valkey_engine_version
  node_type            = var.valkey_node_type
  num_cache_clusters   = 1 # 복제본 없음 (Single-AZ, ADR-61과 같은 기준)
  parameter_group_name = aws_elasticache_parameter_group.valkey.name
  port                 = 6379

  subnet_group_name           = aws_elasticache_subnet_group.valkey.name
  security_group_ids          = [aws_security_group.valkey.id]
  preferred_cache_cluster_azs = [var.azs[0]] # RDS와 같은 AZ(a)

  automatic_failover_enabled = false
  multi_az_enabled           = false

  transit_encryption_enabled = true # 앱: SPRING_DATA_REDIS_SSL_ENABLED=true
  transit_encryption_mode    = "required"
  at_rest_encryption_enabled = true

  snapshot_retention_limit   = 0                     # 선점·캐시는 휘발성 데이터, 원본은 RDS
  maintenance_window         = "sun:19:30-sun:20:30" # UTC = 월 04:30~05:30 KST
  auto_minor_version_upgrade = false
  apply_immediately          = true

  tags = { Name = "${var.name}-valkey" }
}

# ------------------------------------------------------------------
# 3.1.12 DB 스키마·계정·K8s Secret
#   1) 비밀번호 8개(서비스 4 × app·migrator)를 random_password로 만든다 → 이 state(staging-k8s)에만 평문.
#      tfstate 읽기는 관리자만(developer는 DenyTfstateRead, CI plan 역할은 staging/*만)
#   2) 앱 namespace(cinema-staging)에 서비스가 읽는 Secret
#        {svc}-secrets          SPRING_DATASOURCE_PASSWORD           (앱 파드 envFrom)
#        {svc}-migrator-secrets FLYWAY_USER · FLYWAY_PASSWORD        (Flyway PreSync Job·시드 적재 Job만)
#      팀원 View 권한은 Secret 내용을 볼 수 없다(이름만)
#   3) 관리자 namespace(cinema-ops)의 Job이 RDS에 스키마 4개·계정 8개·GRANT를 만든다
#      - 마스터 비밀번호: Job이 Pod Identity로 Secrets Manager에서 직접 읽음 → tfstate에 없음
#      - 계정 비밀번호: cinema-ops Secret db-bootstrap-accounts(팀원 접근 불가)
#      - SQL은 멱등(IF NOT EXISTS + ALTER USER) → 다시 돌려도 안전, 비밀번호 교체에도 같은 Job을 쓴다
#      - Job 이름에 입력값 해시를 붙여 SQL·비밀번호가 바뀌면 새 Job으로 다시 실행된다
#   ⚠️ apply 때 노드가 켜져 있어야 한다(Job 실행). 노드 0대면 10분 뒤 timeout
#
# 6.1.1 서비스별 인스턴스(ADR-115): Job이 **인스턴스마다 하나**다
#   - legacy  : 기존 cinema-staging-mysql (스키마 4개) — Job 이름·입력은 이전과 같아 다시 돌지 않는다
#   - {svc}   : cinema-staging-{svc}-db (스키마 {svc}_db 하나) — var.service_db_services에 넣은 서비스만
#   - 계정 비밀번호는 서비스별로 하나를 두 인스턴스가 같이 쓴다 → 옮길 때 {svc}-secrets는 그대로, 호스트만 DB_HOST → DB_HOST_{SVC}
#   - Job은 자기 서비스의 비밀번호 Secret(db-bootstrap-accounts-{svc})과 자기 인스턴스의 마스터 Secret만 받는다
#   - coupon: 다른 서비스와 같은 Secret 2개. ai: ai_app·ai_migrator·ai_query(NL2SQL 실행 전용, data-model 6-4 — 계정만 만들고
#     테이블 SELECT는 ai 마이그레이션이 준다. 그래서 ai_migrator만 ai_db 범위의 GRANT OPTION을 갖는다, query-account.sh) 계정과
#     ai-migrator-secrets까지 — ai 앱이 읽는 DB 비밀번호 전달은 6.1.4(External Secrets)에서 한다(ai-secrets는 ai.tf가 값을 다시 쓰지 않는다)
# ------------------------------------------------------------------

locals {
  legacy_db_services = toset(["catalog", "booking", "user", "payment"]) # 기존 인스턴스의 스키마 4개 (gateway는 DB 없음)
  service_dbs        = toset(var.service_db_services)                   # 자기 인스턴스가 있는 서비스 (6.1.1)
  all_db_services    = setunion(local.legacy_db_services, local.service_dbs)

  # {svc}-secrets · {svc}-migrator-secrets를 Terraform이 만드는 서비스. ai-secrets는 ai.tf(ANTHROPIC_API_KEY)가 따로 관리한다
  db_services          = setsubtract(local.all_db_services, ["ai"])
  db_migrator_services = local.all_db_services

  db_accounts = merge(
    { for p in setproduct(local.all_db_services, ["app", "migrator"]) : "${p[0]}_${p[1]}" => { svc = p[0], kind = p[1] } },
    contains(local.service_dbs, "ai") ? { ai_query = { svc = "ai", kind = "query" } } : {},
  )

  # Job 대상: 인스턴스 하나에 Job 하나
  db_bootstrap_targets = merge(
    {
      legacy = {
        job_prefix = "db-bootstrap"
        secret     = "db-bootstrap-accounts"
        host       = data.aws_db_instance.mysql.address
        master_arn = data.aws_db_instance.mysql.master_user_secret[0].secret_arn
        services   = sort(tolist(local.legacy_db_services))
        accounts   = sort([for k, a in local.db_accounts : k if contains(local.legacy_db_services, a.svc) && a.kind != "query"])
        query      = false
      }
    },
    {
      for svc in local.service_dbs : svc => {
        job_prefix = "db-bootstrap-${svc}"
        secret     = "db-bootstrap-accounts-${svc}"
        host       = data.aws_db_instance.service[svc].address
        master_arn = data.aws_db_instance.service[svc].master_user_secret[0].secret_arn
        services   = [svc]
        accounts   = sort([for k, a in local.db_accounts : k if a.svc == svc])
        query      = svc == "ai"
      }
    },
  )
}

# 6.1.1 서비스별 인스턴스 (envs/staging rds_services.tf). 식별자는 이름 규칙으로 정해진다
data "aws_db_instance" "service" {
  for_each               = local.service_dbs
  db_instance_identifier = "${var.service_db_identifier_prefix}-${each.key}-db"
}

# MySQL 계정명은 {svc}_app / {svc}_migrator (docs/conventions.md 3장)
data "aws_db_instance" "mysql" {
  db_instance_identifier = var.rds_identifier
}

resource "random_password" "db" {
  for_each = local.db_accounts

  length  = 32
  special = false # SQL 리터럴·JDBC·my.cnf 어디서도 이스케이프가 필요 없게 영숫자만 (32자 ≈ 190비트)
}

# ---- 앱 namespace: 서비스가 읽는 Secret ---------------------------------
resource "kubernetes_secret_v1" "app" {
  for_each = local.db_services

  metadata {
    name      = "${each.key}-secrets"
    namespace = kubernetes_namespace_v1.this[var.app_namespace].metadata[0].name
    labels = {
      "app.kubernetes.io/name"       = each.key
      "app.kubernetes.io/part-of"    = "cinema"
      "app.kubernetes.io/managed-by" = "terraform"
    }
  }

  # 서비스별 추가 키(포트원·모의 결제 키, ADR-112)는 portone.tf local.app_secret_extra
  data = merge({
    SPRING_DATASOURCE_PASSWORD = random_password.db["${each.key}_app"].result
  }, lookup(local.app_secret_extra, each.key, {}))
}

resource "kubernetes_secret_v1" "migrator" {
  for_each = local.db_migrator_services

  metadata {
    name      = "${each.key}-migrator-secrets"
    namespace = kubernetes_namespace_v1.this[var.app_namespace].metadata[0].name
    labels = {
      "app.kubernetes.io/name"       = "${each.key}-flyway"
      "app.kubernetes.io/part-of"    = "cinema"
      "app.kubernetes.io/managed-by" = "terraform"
    }
  }

  data = {
    FLYWAY_USER     = "${each.key}_migrator"
    FLYWAY_PASSWORD = random_password.db["${each.key}_migrator"].result
  }
}

# ---- 관리자 namespace: 계정 생성 Job ----------------------------------
resource "kubernetes_service_account_v1" "db_bootstrap" {
  metadata {
    name      = "db-bootstrap" # envs/staging db_bootstrap.tf Pod Identity 연결과 같은 이름
    namespace = kubernetes_namespace_v1.this[var.ops_namespace].metadata[0].name
  }
  automount_service_account_token = false # K8s API는 안 씀 (Pod Identity 토큰은 별도로 주입됨)
}

# Job 입력: 그 인스턴스에 만들 계정의 비밀번호만 (키 = CATALOG_APP_PASSWORD …)
resource "kubernetes_secret_v1" "db_bootstrap_accounts" {
  for_each = local.db_bootstrap_targets

  metadata {
    name      = each.value.secret
    namespace = kubernetes_namespace_v1.this[var.ops_namespace].metadata[0].name
  }

  data = { for k in each.value.accounts : "${upper(k)}_PASSWORD" => random_password.db[k].result }
}

# 기존 리소스 주소 → legacy (다시 만들지 않는다)
moved {
  from = kubernetes_secret_v1.db_bootstrap_accounts
  to   = kubernetes_secret_v1.db_bootstrap_accounts["legacy"]
}

moved {
  from = kubernetes_job_v1.db_bootstrap
  to   = kubernetes_job_v1.db_bootstrap["legacy"]
}

resource "kubernetes_config_map_v1" "db_bootstrap" {
  metadata {
    name      = "db-bootstrap-scripts"
    namespace = kubernetes_namespace_v1.this[var.ops_namespace].metadata[0].name
  }

  data = {
    "fetch-master.sh"  = file("${path.module}/scripts/db-bootstrap/fetch-master.sh")
    "bootstrap.sh"     = file("${path.module}/scripts/db-bootstrap/bootstrap.sh")
    "query-account.sh" = file("${path.module}/scripts/db-bootstrap/query-account.sh") # ai_query 계정·ai_migrator GRANT OPTION (ai 인스턴스만)
  }
}

locals {
  # SQL 스크립트·비밀번호·대상이 바뀌면 이름이 바뀌어 Job이 다시 돈다 (해시만 노출, 값은 노출 안 됨)
  # 비밀번호가 섞인 해시라 Terraform이 sensitive로 표시한다 → 해시는 되돌릴 수 없으므로 nonsensitive로 이름에 쓴다
  # 대상마다 따로 계산한다 → 한 서비스의 비밀번호를 바꿔도 다른 인스턴스의 Job은 다시 돌지 않는다.
  # legacy는 6.1.1 이전과 같은 입력·순서라 해시가 그대로다(query-account.sh는 ai 대상에만 넣는다)
  db_bootstrap_hash = {
    for name, t in local.db_bootstrap_targets : name => nonsensitive(substr(sha256(join("|", concat(
      [kubernetes_config_map_v1.db_bootstrap.data["bootstrap.sh"], kubernetes_config_map_v1.db_bootstrap.data["fetch-master.sh"]],
      t.query ? [kubernetes_config_map_v1.db_bootstrap.data["query-account.sh"]] : [],
      [sha256(data.http.rds_ca.response_body)],
      [for k in t.accounts : random_password.db[k].result],
      [t.host, var.mysql_client_image, var.awscli_image],
    ))), 0, 8))
  }
}

resource "kubernetes_job_v1" "db_bootstrap" {
  for_each = local.db_bootstrap_targets

  metadata {
    name      = "${each.value.job_prefix}-${local.db_bootstrap_hash[each.key]}"
    namespace = kubernetes_namespace_v1.this[var.ops_namespace].metadata[0].name
    labels = {
      "app.kubernetes.io/name"       = "db-bootstrap"
      "app.kubernetes.io/part-of"    = "cinema"
      "app.kubernetes.io/managed-by" = "terraform"
    }
  }

  spec {
    backoff_limit           = 1
    active_deadline_seconds = 600
    # ttl_seconds_after_finished 없음: 끝난 Job이 지워지면 Terraform이 다시 만들려 한다

    template {
      metadata {
        labels = { "app.kubernetes.io/name" = "db-bootstrap" }
      }

      spec {
        service_account_name            = kubernetes_service_account_v1.db_bootstrap.metadata[0].name
        automount_service_account_token = false
        restart_policy                  = "Never"

        security_context {
          run_as_non_root = true
          run_as_user     = 999 # mysql 이미지의 mysql 사용자
          run_as_group    = 999
          fs_group        = 999
        }

        # 1) 마스터 접속 정보 → /work/master.cnf (600). Pod Identity로 Secrets Manager 읽기
        init_container {
          name    = "fetch-master"
          image   = var.awscli_image
          command = ["/bin/bash", "/scripts/fetch-master.sh"]

          env {
            name  = "MASTER_SECRET_ARN"
            value = each.value.master_arn
          }
          env {
            name  = "AWS_REGION"
            value = var.region
          }
          env {
            name  = "HOME"
            value = "/tmp"
          }

          volume_mount {
            name       = "scripts"
            mount_path = "/scripts"
            read_only  = true
          }
          volume_mount {
            name       = "work"
            mount_path = "/work"
          }
          volume_mount {
            name       = "tmp"
            mount_path = "/tmp"
          }

          security_context {
            allow_privilege_escalation = false
            read_only_root_filesystem  = true
            capabilities { drop = ["ALL"] }
          }
          resources {
            requests = { cpu = "50m", memory = "128Mi" }
            limits   = { memory = "256Mi" }
          }
        }

        # 2) 스키마·계정·GRANT (멱등) + 확인 출력 (비밀번호는 출력하지 않는다)
        container {
          name  = "bootstrap"
          image = var.mysql_client_image
          # ai 인스턴스만 ai_query 계정까지(테이블 권한은 ai 마이그레이션). 나머지는 이전과 같은 명령(legacy Job이 바뀌지 않게)
          command = each.value.query ? ["/bin/bash", "-c", "/bin/bash /scripts/bootstrap.sh && /bin/bash /scripts/query-account.sh"] : ["/bin/bash", "/scripts/bootstrap.sh"]

          env {
            name  = "DB_HOST"
            value = each.value.host
          }
          env {
            name  = "SSL_CA" # 서버 인증서·호스트 이름 검증 (rds_ca.tf)
            value = "/etc/rds-ca/rds-ca.pem"
          }
          env {
            name  = "DB_SERVICES"
            value = join(" ", each.value.services)
          }
          env {
            name  = "HOME"
            value = "/tmp"
          }

          env_from {
            secret_ref {
              name = kubernetes_secret_v1.db_bootstrap_accounts[each.key].metadata[0].name
            }
          }

          volume_mount {
            name       = "scripts"
            mount_path = "/scripts"
            read_only  = true
          }
          volume_mount {
            name       = "work"
            mount_path = "/work"
          }
          volume_mount {
            name       = "tmp"
            mount_path = "/tmp"
          }
          volume_mount {
            name       = "rds-ca"
            mount_path = "/etc/rds-ca"
            read_only  = true
          }

          security_context {
            allow_privilege_escalation = false
            read_only_root_filesystem  = true
            capabilities { drop = ["ALL"] }
          }
          resources {
            requests = { cpu = "50m", memory = "128Mi" }
            limits   = { memory = "256Mi" }
          }
        }

        volume {
          name = "rds-ca"
          config_map {
            name = kubernetes_config_map_v1.rds_ca[var.ops_namespace].metadata[0].name
          }
        }
        volume {
          name = "scripts"
          config_map {
            name         = kubernetes_config_map_v1.db_bootstrap.metadata[0].name
            default_mode = "0555"
          }
        }
        volume {
          name = "work" # master.cnf — 메모리에만, Pod가 끝나면 사라짐
          empty_dir {
            medium = "Memory"
          }
        }
        volume {
          name = "tmp"
          empty_dir {}
        }
      }
    }
  }

  wait_for_completion = true
  timeouts {
    create = "10m"
    update = "10m"
  }
}

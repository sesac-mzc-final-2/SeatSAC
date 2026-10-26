#!/usr/bin/env bash
# 3.1.12 db-bootstrap — 스키마·계정·GRANT를 멱등으로 맞춘다 (envs/staging-k8s/db_bootstrap.tf).
#   스키마 {svc}_db  · 계정 {svc}_app(DML) · {svc}_migrator(해당 스키마 ALL) · TLS 필수
#   이미 있으면 비밀번호·권한만 다시 맞춘다(ALTER USER) → 비밀번호 교체에도 같은 Job
#   비밀번호는 SQL 파일(메모리 emptyDir, 600)로만 넘기고 출력하지 않는다. set -x 금지
set -euo pipefail
umask 077
WORK_DIR="${WORK_DIR:-/work}"
: "${DB_HOST:?}" "${DB_SERVICES:?}"
CNF="$WORK_DIR/master.cnf"
SQL="$WORK_DIR/bootstrap.sql"
trap 'rm -f "$SQL"' EXIT
[[ -f $CNF ]] || { echo "FAIL: $CNF 없음 (fetch-master 실패)"; exit 1; }

# 서버 인증서·호스트 이름까지 검증(SSL_CA = RDS CA 번들, rds_ca.tf). 없으면 암호화만(로컬 리허설용)
if [[ -n "${SSL_CA:-}" ]]; then
  [[ -s "$SSL_CA" ]] || { echo "FAIL: SSL_CA=$SSL_CA 없음 (ConfigMap rds-ca-bundle)"; exit 1; }
  TLS_OPTS=(--ssl-mode=VERIFY_IDENTITY --ssl-ca="$SSL_CA")
else
  TLS_OPTS=(--ssl-mode=REQUIRED)
fi
my() { mysql --defaults-extra-file="$CNF" -h "$DB_HOST" -P "${DB_PORT:-3306}" "${TLS_OPTS[@]}" --default-character-set=utf8mb4 "$@"; }

echo "== 접속 확인 (${TLS_OPTS[0]})"
my -N -B -e "SELECT CONCAT('server ', VERSION(), ' · time_zone=', @@global.time_zone, ' · require_secure_transport=', @@global.require_secure_transport)"

pw() {  # CATALOG_APP_PASSWORD 형식 환경변수 → 값 (영숫자만 허용)
  local v="${!1:-}"
  [[ "$v" =~ ^[A-Za-z0-9]{16,}$ ]] || { echo "FAIL: $1 없음 또는 형식 오류" >&2; exit 1; }
  printf '%s' "$v"
}

{
  for s in $DB_SERVICES; do
    S="$(echo "$s" | tr '[:lower:]' '[:upper:]')"
    app_pw="$(pw "${S}_APP_PASSWORD")"
    mig_pw="$(pw "${S}_MIGRATOR_PASSWORD")"
    cat <<SQL
CREATE DATABASE IF NOT EXISTS \`${s}_db\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS '${s}_app'@'%' IDENTIFIED BY '${app_pw}' REQUIRE SSL;
ALTER USER '${s}_app'@'%' IDENTIFIED BY '${app_pw}' REQUIRE SSL;
CREATE USER IF NOT EXISTS '${s}_migrator'@'%' IDENTIFIED BY '${mig_pw}' REQUIRE SSL;
ALTER USER '${s}_migrator'@'%' IDENTIFIED BY '${mig_pw}' REQUIRE SSL;
GRANT SELECT, INSERT, UPDATE, DELETE ON \`${s}_db\`.* TO '${s}_app'@'%';
GRANT ALL PRIVILEGES ON \`${s}_db\`.* TO '${s}_migrator'@'%';
SQL
  done
} > "$SQL"
chmod 600 "$SQL"

echo "== 스키마·계정·권한 적용 ($DB_SERVICES)"
my < "$SQL"
rm -f "$SQL"

echo "== 확인 (비밀번호 제외)"
my -t -e "
SELECT schema_name AS \`schema\`, default_character_set_name AS charset, default_collation_name AS collation
  FROM information_schema.schemata WHERE schema_name LIKE '%\\_db' ORDER BY 1;
SELECT user, host, ssl_type FROM mysql.user WHERE user REGEXP '_(app|migrator)\$' ORDER BY user;"

fail=0
for s in $DB_SERVICES; do
  g_app="$(my -N -B -e "SHOW GRANTS FOR '${s}_app'@'%'")"
  g_mig="$(my -N -B -e "SHOW GRANTS FOR '${s}_migrator'@'%'")"
  [[ "$g_app" == *"SELECT, INSERT, UPDATE, DELETE ON \`${s}_db\`.*"* ]] || { echo "FAIL: ${s}_app 권한: $g_app"; fail=1; }
  [[ "$g_mig" == *"ALL PRIVILEGES ON \`${s}_db\`.*"* ]] || { echo "FAIL: ${s}_migrator 권한: $g_mig"; fail=1; }
  # 다른 스키마 권한이 붙어 있으면 실패 (서비스별 격리)
  # 스키마 이름은 백틱 안 아무 문자나(숫자·대문자·$ 포함, 예: archive2_db), 루틴 권한(ON PROCEDURE·FUNCTION)도 본다
  others="$(printf '%s\n%s\n' "$g_app" "$g_mig" | grep -oE 'ON (PROCEDURE |FUNCTION )?`[^`]+`' | grep -vx "ON \`${s}_db\`" || true)"
  [[ -z "$others" ]] || { echo "FAIL: ${s} 계정에 다른 스키마 권한: $others"; fail=1; }
  # 전역 권한(ON *.*) 금지 — 기본으로 붙는 GRANT USAGE ON *.* 만 허용.
  # CREATE USER IF NOT EXISTS·ALTER USER는 기존 권한을 지우지 않으므로, 누가 손으로 준 전역 권한이 남아 있으면 여기서 잡는다
  globals="$(printf '%s\n%s\n' "$g_app" "$g_mig" | grep -E '^GRANT .* ON \*\.\* TO' | grep -vE '^GRANT USAGE ON \*\.\* TO' || true)"
  [[ -z "$globals" ]] || { echo "FAIL: ${s} 계정에 전역 권한: $globals"; fail=1; }
done
[[ $fail == 0 ]] || exit 1
n=$(wc -w <<<"$DB_SERVICES")
echo "PASS: 스키마 ${n} · 계정 $((n * 2)) · 권한 확인 완료 (${TLS_OPTS[0]})"

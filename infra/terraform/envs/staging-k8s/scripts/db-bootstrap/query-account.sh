#!/usr/bin/env bash
# 6.1.1 db-bootstrap — ai 인스턴스의 NL2SQL 실행 전용 계정 ai_query (docs/data-model.md 6-4, ADR-120).
#   bootstrap.sh(스키마·ai_app·ai_migrator) 뒤에 같은 컨테이너에서 실행한다.
#   권한은 QUERY_TABLES의 SELECT뿐(테이블 단위). 테이블은 Flyway가 나중에 만든다 — MySQL은 없는 테이블에도 GRANT를 받는다
#   실행 상한 5초(MAX_EXECUTION_TIME)는 계정 속성이 아니라 ai 앱이 세션에서 건다
#   비밀번호는 SQL 파일(메모리 emptyDir, 600)로만 넘기고 출력하지 않는다. set -x 금지
set -euo pipefail
umask 077
WORK_DIR="${WORK_DIR:-/work}"
QUERY_USER="${QUERY_USER:-ai_query}"
QUERY_SCHEMA="${QUERY_SCHEMA:-ai_db}"
QUERY_TABLES="${QUERY_TABLES:-movie theater screening}"
: "${DB_HOST:?}"
CNF="$WORK_DIR/master.cnf"
SQL="$WORK_DIR/query-account.sql"
trap 'rm -f "$SQL"' EXIT
[[ -f $CNF ]] || { echo "FAIL: $CNF 없음 (fetch-master 실패)"; exit 1; }
[[ "$QUERY_USER" =~ ^[a-z_]+$ && "$QUERY_SCHEMA" =~ ^[a-z_]+$ ]] || { echo "FAIL: QUERY_USER·QUERY_SCHEMA 형식 오류"; exit 1; }

if [[ -n "${SSL_CA:-}" ]]; then
  [[ -s "$SSL_CA" ]] || { echo "FAIL: SSL_CA=$SSL_CA 없음 (ConfigMap rds-ca-bundle)"; exit 1; }
  TLS_OPTS=(--ssl-mode=VERIFY_IDENTITY --ssl-ca="$SSL_CA")
else
  TLS_OPTS=(--ssl-mode=REQUIRED)
fi
my() { mysql --defaults-extra-file="$CNF" -h "$DB_HOST" -P "${DB_PORT:-3306}" "${TLS_OPTS[@]}" --default-character-set=utf8mb4 "$@"; }

var="$(echo "$QUERY_USER" | tr '[:lower:]' '[:upper:]')_PASSWORD" # AI_QUERY_PASSWORD
pw="${!var:-}"
[[ "$pw" =~ ^[A-Za-z0-9]{16,}$ ]] || { echo "FAIL: $var 없음 또는 형식 오류" >&2; exit 1; }

{
  cat <<SQL
CREATE USER IF NOT EXISTS '${QUERY_USER}'@'%' IDENTIFIED BY '${pw}' REQUIRE SSL;
ALTER USER '${QUERY_USER}'@'%' IDENTIFIED BY '${pw}' REQUIRE SSL;
SQL
  for t in $QUERY_TABLES; do
    [[ "$t" =~ ^[a-z_]+$ ]] || { echo "FAIL: 테이블 이름 형식 오류: $t" >&2; exit 1; }
    echo "GRANT SELECT ON \`${QUERY_SCHEMA}\`.\`${t}\` TO '${QUERY_USER}'@'%';"
  done
} > "$SQL"
chmod 600 "$SQL"
unset pw

echo "== ${QUERY_USER} 계정·권한 적용 (${QUERY_SCHEMA}: ${QUERY_TABLES})"
my < "$SQL"
rm -f "$SQL"

# 확인: 허용한 테이블의 SELECT와 기본 USAGE 말고는 아무 권한도 없어야 한다
grants="$(my -N -B -e "SHOW GRANTS FOR '${QUERY_USER}'@'%'")"
fail=0
for t in $QUERY_TABLES; do
  [[ "$grants" == *"GRANT SELECT ON \`${QUERY_SCHEMA}\`.\`${t}\` TO"* ]] || { echo "FAIL: ${QUERY_USER} 권한 없음: ${QUERY_SCHEMA}.${t}"; fail=1; }
done
allowed="$(for t in $QUERY_TABLES; do printf 'GRANT SELECT ON `%s`.`%s` TO\n' "$QUERY_SCHEMA" "$t"; done)"
extra="$(printf '%s\n' "$grants" | grep -vE '^GRANT USAGE ON \*\.\* TO' | grep -vF -f <(printf '%s\n' "$allowed") || true)"
[[ -z "$extra" ]] || { echo "FAIL: ${QUERY_USER}에 허용 밖 권한: $extra"; fail=1; }
[[ $fail == 0 ]] || exit 1
echo "PASS: ${QUERY_USER} 읽기 전용 권한 확인 완료 (${TLS_OPTS[0]})"

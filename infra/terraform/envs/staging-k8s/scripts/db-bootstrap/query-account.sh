#!/usr/bin/env bash
# 6.1.1 db-bootstrap — ai 인스턴스의 NL2SQL 실행 전용 계정 ai_query (docs/data-model.md 6-4, ADR-120).
#   bootstrap.sh(스키마·ai_app·ai_migrator) 뒤에 같은 컨테이너에서 실행한다.
#
#   여기서 하는 일은 두 가지뿐이다 — 테이블 권한은 주지 않는다:
#     1) 계정 ai_query 만들기(TLS 필수, 권한 없음)
#     2) ai_migrator에 ai_db 범위의 GRANT OPTION — ai 마이그레이션이 테이블을 만든 뒤
#        GRANT SELECT ON ai_db.{movie,theater,screening} TO 'ai_query'@'%' 를 실행할 수 있게
#   이유: 이 Job이 돌 때는 테이블이 아직 없다(Flyway가 나중에 만든다). MySQL은 없는 테이블에 SELECT만 줄 수 없다
#         (없는 테이블에 주는 권한에는 CREATE가 들어 있어야 한다 → Table doesn't exist 오류).
#   다시 돌 때(비밀번호 교체 등): 마이그레이션이 준 SELECT는 그대로 둔다. QUERY_TABLES 밖의 권한이 붙어 있으면 실패한다
#   실행 상한 5초(MAX_EXECUTION_TIME)는 계정 속성이 아니라 ai 앱이 세션에서 건다
#   비밀번호는 SQL 파일(메모리 emptyDir, 600)로만 넘기고 출력하지 않는다. set -x 금지
set -euo pipefail
umask 077
WORK_DIR="${WORK_DIR:-/work}"
QUERY_USER="${QUERY_USER:-ai_query}"
QUERY_SCHEMA="${QUERY_SCHEMA:-ai_db}"
QUERY_TABLES="${QUERY_TABLES:-movie theater screening}"
MIGRATOR_USER="${MIGRATOR_USER:-ai_migrator}"
: "${DB_HOST:?}"
CNF="$WORK_DIR/master.cnf"
SQL="$WORK_DIR/query-account.sql"
trap 'rm -f "$SQL"' EXIT
[[ -f $CNF ]] || { echo "FAIL: $CNF 없음 (fetch-master 실패)"; exit 1; }
for v in "$QUERY_USER" "$QUERY_SCHEMA" "$MIGRATOR_USER"; do
  [[ "$v" =~ ^[a-z_]+$ ]] || { echo "FAIL: 계정·스키마 이름 형식 오류: $v"; exit 1; }
done
for t in $QUERY_TABLES; do
  [[ "$t" =~ ^[a-z_]+$ ]] || { echo "FAIL: 테이블 이름 형식 오류: $t"; exit 1; }
done

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

cat > "$SQL" <<SQL
CREATE USER IF NOT EXISTS '${QUERY_USER}'@'%' IDENTIFIED BY '${pw}' REQUIRE SSL;
ALTER USER '${QUERY_USER}'@'%' IDENTIFIED BY '${pw}' REQUIRE SSL;
GRANT ALL PRIVILEGES ON \`${QUERY_SCHEMA}\`.* TO '${MIGRATOR_USER}'@'%' WITH GRANT OPTION;
SQL
chmod 600 "$SQL"
unset pw

echo "== ${QUERY_USER} 계정 · ${MIGRATOR_USER} GRANT OPTION(${QUERY_SCHEMA}) 적용"
my < "$SQL"
rm -f "$SQL"

# 확인 1) ai_query: 기본 USAGE와 QUERY_TABLES의 SELECT(마이그레이션이 준 것) 말고는 아무 권한도 없어야 한다
grants="$(my -N -B -e "SHOW GRANTS FOR '${QUERY_USER}'@'%'")"
allowed="$(for t in $QUERY_TABLES; do printf 'GRANT SELECT ON `%s`.`%s` TO\n' "$QUERY_SCHEMA" "$t"; done)"
extra="$(printf '%s\n' "$grants" | grep -vE '^GRANT USAGE ON \*\.\* TO' | grep -vF -f <(printf '%s\n' "$allowed") || true)"
fail=0
[[ -z "$extra" ]] || { echo "FAIL: ${QUERY_USER}에 허용 밖 권한: $extra"; fail=1; }
granted=0
for t in $QUERY_TABLES; do
  [[ "$grants" == *"GRANT SELECT ON \`${QUERY_SCHEMA}\`.\`${t}\` TO"* ]] && granted=$((granted + 1))
done

# 확인 2) ai_migrator: GRANT OPTION이 ai_db 범위에만 있어야 한다(전역·다른 스키마 금지 — bootstrap.sh가 이미 본다)
g_mig="$(my -N -B -e "SHOW GRANTS FOR '${MIGRATOR_USER}'@'%'")"
[[ "$g_mig" == *"ALL PRIVILEGES ON \`${QUERY_SCHEMA}\`.* TO"*"WITH GRANT OPTION"* ]] || { echo "FAIL: ${MIGRATOR_USER} GRANT OPTION 없음: $g_mig"; fail=1; }
other_opt="$(printf '%s\n' "$g_mig" | grep 'WITH GRANT OPTION' | grep -vF "ON \`${QUERY_SCHEMA}\`.* TO" || true)"
[[ -z "$other_opt" ]] || { echo "FAIL: ${MIGRATOR_USER}의 GRANT OPTION이 ${QUERY_SCHEMA} 밖에 있음: $other_opt"; fail=1; }
[[ $fail == 0 ]] || exit 1

n=$(wc -w <<<"$QUERY_TABLES")
if [[ $granted == 0 ]]; then
  echo "PASS: ${QUERY_USER} 계정 준비(권한 없음 — 테이블 SELECT는 ai 마이그레이션이 준다) · ${MIGRATOR_USER} GRANT OPTION ${QUERY_SCHEMA} (${TLS_OPTS[0]})"
else
  echo "PASS: ${QUERY_USER} 계정 확인(SELECT ${granted}/${n} 테이블 — 마이그레이션이 준 것) · ${MIGRATOR_USER} GRANT OPTION ${QUERY_SCHEMA} (${TLS_OPTS[0]})"
fi

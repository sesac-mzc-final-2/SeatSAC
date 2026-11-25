#!/usr/bin/env bash
# 6.1.5 데이터 이전 — Job 안에서 돈다(infra/scripts/db-migrate.sh가 ConfigMap으로 넣는다). 런북 8-E 3)
#   기존 cinema-staging-mysql의 {svc}_db → 자기 인스턴스 cinema-staging-{svc}-db의 {svc}_db (ADR-115)
#   MODE=counts : 두 인스턴스의 테이블별 행 수만 출력(읽기만)
#   MODE=copy   : 대상이 비어 있을 때만 mysqldump | mysql 로 바로 넘긴다(파이프 — 덤프 파일을 남기지 않는다, user_db에 이메일·이름이 있다)
#                 → 테이블별 행 수 대조. 다르면 실패
#   계정은 {svc}_migrator(두 인스턴스에 같은 비밀번호, 해당 스키마 ALL — db-bootstrap). 비밀번호는 /tmp(메모리)의 cnf로만 넘기고 출력하지 않는다
#   TLS는 서버 인증서·호스트 이름까지 검증(rds-ca-bundle). set -x 금지
set -euo pipefail
umask 077
: "${SVC:?}" "${MODE:?}" "${SRC_HOST:?}" "${DST_HOST:?}" "${FLYWAY_USER:?}" "${FLYWAY_PASSWORD:?}"
DB="${SVC}_db"
SSL_CA="${SSL_CA:-/etc/rds-ca/rds-ca.pem}"
CNF=/tmp/migrator.cnf
trap 'rm -f "$CNF"' EXIT

fail() { echo "FAIL: $*"; exit 1; }
[[ "$SVC" =~ ^(catalog|booking|user|payment)$ ]] || fail "SVC=$SVC — 옮길 데이터가 있는 서비스는 catalog·booking·user·payment뿐(coupon·ai는 새 스키마)"
[[ "$MODE" =~ ^(counts|copy)$ ]] || fail "MODE=$MODE — counts 또는 copy"
[[ "$SRC_HOST" != "$DST_HOST" ]] || fail "원본과 대상 호스트가 같다 — DB_HOST_${SVC^^}가 아직 기존 인스턴스를 가리킨다"
[[ "$FLYWAY_USER" == "${SVC}_migrator" ]] || fail "FLYWAY_USER가 ${SVC}_migrator가 아니다 (Secret ${SVC}-migrator-secrets 확인)"
[[ "$FLYWAY_PASSWORD" =~ ^[A-Za-z0-9]{16,}$ ]] || fail "FLYWAY_PASSWORD 형식 오류(영숫자 16자 이상)"
[[ -s "$SSL_CA" ]] || fail "SSL_CA=$SSL_CA 없음 (ConfigMap rds-ca-bundle)"

printf '[client]\nuser=%s\npassword=%s\n' "$FLYWAY_USER" "$FLYWAY_PASSWORD" > "$CNF"
TLS=(--ssl-mode=VERIFY_IDENTITY --ssl-ca="$SSL_CA")
my()  { local h="$1"; shift; mysql --defaults-extra-file="$CNF" -h "$h" -P 3306 "${TLS[@]}" --default-character-set=utf8mb4 "$@"; }
q()   { my "$1" -N -B -e "$2"; }

tables() {   # 기본 테이블 이름(정렬)
  q "$1" "SELECT table_name FROM information_schema.tables WHERE table_schema='${DB}' AND table_type='BASE TABLE' ORDER BY table_name"
}
counts() {   # "테이블<TAB>행 수" (정확한 COUNT(*) — information_schema.table_rows는 추정치라 쓰지 않는다)
  local h="$1" t
  for t in $(tables "$h"); do
    printf '%s\t%s\n' "$t" "$(q "$h" "SELECT COUNT(*) FROM \`${DB}\`.\`${t}\`")"
  done
}
extra() {    # 시드 재적재로 빠질 수 있는 값(catalog movie의 TMDB 보충 컬럼, 런북 8-E 3)
  [[ "$SVC" == catalog ]] || return 0
  local n
  n=$(q "$1" "SELECT COUNT(*) FROM \`${DB}\`.movie WHERE tmdb_id IS NOT NULL" 2>/dev/null) || return 0
  printf 'movie.tmdb_id NOT NULL\t%s\n' "$n"
}

echo "== 접속 확인 (원본 기존 인스턴스 · 대상 cinema-staging-${SVC}-db, ${TLS[0]})"
q "$SRC_HOST" "SELECT CONCAT('src ', VERSION(), ' · time_zone=', @@global.time_zone)"
q "$DST_HOST" "SELECT CONCAT('dst ', VERSION(), ' · time_zone=', @@global.time_zone)"
q "$DST_HOST" "SELECT 1 FROM information_schema.schemata WHERE schema_name='${DB}'" | grep -q 1 \
  || fail "대상에 ${DB}가 없다 — 인스턴스별 db-bootstrap Job(런북 8-E 2)을 먼저"

if [[ "$MODE" == copy ]]; then
  n_dst=$(q "$DST_HOST" "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${DB}'")
  [[ "$n_dst" == 0 ]] || fail "대상 ${DB}에 테이블 ${n_dst}개 — 이미 옮겼거나 Flyway Job이 새 인스턴스에 먼저 돌았다. 덮어쓰지 않는다(런북 8-E 3 표)"
  q "$SRC_HOST" "SELECT 1 FROM information_schema.tables WHERE table_schema='${DB}' AND table_name='flyway_schema_history'" | grep -q 1 \
    || fail "원본 ${DB}에 flyway_schema_history가 없다 — 원본이 맞는지 확인"

  echo "== 덤프 → 복원 (파이프, 파일 없음): ${DB}"
  # --single-transaction: 한 시점 스냅샷(InnoDB). --no-tablespaces: PROCESS 권한 없이. --set-gtid-purged=OFF: 대상 GTID를 건드리지 않음
  # flyway_schema_history도 같이 옮긴다 → 새 인스턴스의 Flyway Job은 이력을 보고 이미 적용된 V를 건너뛴다
  mysqldump --defaults-extra-file="$CNF" -h "$SRC_HOST" -P 3306 "${TLS[@]}" --default-character-set=utf8mb4 \
      --single-transaction --quick --no-tablespaces --set-gtid-purged=OFF --hex-blob --triggers --skip-lock-tables \
      "$DB" \
    | my "$DST_HOST" "$DB"
  echo "복원 끝"
fi

echo "== 행 수 (테이블 · 원본 · 대상)"
SRC_COUNTS=$( { counts "$SRC_HOST"; extra "$SRC_HOST"; } )
DST_COUNTS=$( { counts "$DST_HOST"; extra "$DST_HOST"; } )
# mysql 이미지(oraclelinux slim)에는 column·awk가 없을 수 있어 bash만으로 나란히 찍는다
declare -A DST_OF=()
while IFS=$'\t' read -r t n; do [[ -n "$t" ]] && DST_OF["$t"]="$n"; done <<<"$DST_COUNTS"
declare -A SEEN=()
while IFS=$'\t' read -r t n; do
  [[ -n "$t" ]] || continue
  SEEN["$t"]=1
  printf '%-40s %12s %12s %s\n' "$t" "$n" "${DST_OF[$t]:--}" "$([[ "${DST_OF[$t]:-}" == "$n" ]] && echo ok || echo DIFF)"
done <<<"$SRC_COUNTS"
while IFS=$'\t' read -r t n; do
  [[ -n "$t" && -z "${SEEN[$t]:-}" ]] && printf '%-40s %12s %12s %s\n' "$t" "-" "$n" "DIFF"
done <<<"$DST_COUNTS"

if [[ "$MODE" == copy ]]; then
  [[ "$SRC_COUNTS" == "$DST_COUNTS" ]] || fail "행 수가 다르다 — 덤프 중 쓰기가 있었거나 복원이 중간에 끊겼다. 대상 스키마를 비우고(관리자) 쓰기를 멈춘 뒤 다시"
  echo "PASS: ${DB} 테이블 $(tables "$SRC_HOST" | grep -c .)개 · 행 수 일치 → gitops 전환 PR(DB_HOST → DB_HOST_${SVC^^})"
else
  echo "PASS: counts (읽기만)"
fi

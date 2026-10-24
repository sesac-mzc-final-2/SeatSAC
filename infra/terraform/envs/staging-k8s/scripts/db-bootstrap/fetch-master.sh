#!/usr/bin/env bash
# 3.1.12 db-bootstrap initContainer — RDS 관리 마스터 Secret(Secrets Manager)을 읽어 /work/master.cnf(600)를 만든다.
# 비밀번호는 화면·로그·환경변수에 남기지 않는다. 자격 증명은 Pod Identity(cinema-staging-db-bootstrap-pod).
set -euo pipefail
umask 077
WORK_DIR="${WORK_DIR:-/work}"
: "${MASTER_SECRET_ARN:?MASTER_SECRET_ARN 필요}"

json="$(aws secretsmanager get-secret-value --secret-id "$MASTER_SECRET_ARN" --query SecretString --output text)"

# SecretString = {"username":"...","password":"..."} (RDS 관리 형식). jq가 없는 이미지라 sed로 꺼낸다.
# RDS for MySQL 마스터 비밀번호에는 / " @ 공백이 들어갈 수 없다 → JSON에서 생길 수 있는 이스케이프는 \\(백슬래시) 뿐.
# 이스케이프 쌍을 보존해 그대로 option file의 password="..."에 넣으면 MySQL 파서가 \\ → \ 로 해석한다.
user="$(printf '%s' "$json" | sed -nE 's/.*"username"[[:space:]]*:[[:space:]]*"(([^"\\]|\\.)*)".*/\1/p')"
pass="$(printf '%s' "$json" | sed -nE 's/.*"password"[[:space:]]*:[[:space:]]*"(([^"\\]|\\.)*)".*/\1/p')"
unset json

if [[ -z "$user" || -z "$pass" ]]; then
  echo "FAIL: 마스터 Secret 형식을 읽지 못함. 런북 8-D '마스터 Secret 형식' 참고" >&2
  exit 1
fi
# \\ 외의 이스케이프(\" \/ \uXXXX 등)는 RDS 규칙상 나올 수 없고 option file이 같은 뜻으로 해석하지 않는다 → 멈춘다
if printf '%s' "$user$pass" | sed -E 's/\\\\//g' | grep -q '\\'; then
  echo "FAIL: 마스터 Secret에 지원하지 않는 JSON 이스케이프가 있음. 런북 8-D '마스터 Secret 형식' 참고" >&2
  exit 1
fi

printf '[client]\nuser=%s\npassword="%s"\n' "$user" "$pass" > "$WORK_DIR/master.cnf"
chmod 600 "$WORK_DIR/master.cnf"
echo "OK: 마스터 접속 정보 준비 (user=${user})"

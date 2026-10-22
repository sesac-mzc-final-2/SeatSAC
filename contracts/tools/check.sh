#!/usr/bin/env bash
# 계약 CI를 로컬에서 그대로 실행 (2.2.7). PR 올리기 전에 돌려 본다.
#   사용: contracts/tools/check.sh [비교 기준 브랜치, 기본 origin/main]
#   필요: node(npx), python3 + pip install check-jsonschema==0.38.2, oasdiff 1.11.7
#         (macOS: brew install oasdiff  /  그 외: GitHub Releases의 oasdiff_1.11.7_*.tar.gz)
set -euo pipefail

BASE_REF="${1:-origin/main}"
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "▶ 1/4 OpenAPI lint"
npx -y @redocly/cli@1.34.5 lint --config contracts/redocly.yaml \
  contracts/openapi/*.yaml contracts/openapi/internal/*.yaml

echo "▶ 2/4 JSON Schema 자체 검증 + 예시 메시지 검증"
check-jsonschema --check-metaschema contracts/events/*.json contracts/schemas/*.json
for dir in contracts/events/examples/* contracts/schemas/examples/*; do
  [ -d "$dir" ] || continue
  name="$(basename "$dir")"
  schema=""
  for cand in contracts/events/"$name".json contracts/schemas/"$name".json; do
    [ -f "$cand" ] && schema="$cand"
  done
  [ -n "$schema" ] || { echo "스키마 없음: $name"; exit 1; }
  check-jsonschema --schemafile "$schema" "$dir"/*.json
done

echo "▶ 3/4 기준($BASE_REF)과 비교할 contracts 꺼내기"
git fetch -q origin || true
if git cat-file -e "$BASE_REF:contracts" 2>/dev/null; then
  git archive "$BASE_REF" contracts | tar -x -C "$TMP"
else
  echo "기준 브랜치에 contracts가 없습니다. 비교를 건너뜁니다."
  mkdir -p "$TMP/contracts"
fi

echo "▶ 4/4 하위 호환 검사 (OpenAPI · 이벤트 스키마)"
status=0
for f in contracts/openapi/*.yaml contracts/openapi/internal/*.yaml; do
  if [ -f "$TMP/$f" ]; then
    oasdiff breaking "$TMP/$f" "$f" --fail-on ERR || status=1
  else
    echo "새 명세: $f"
  fi
done
for f in $(cd "$TMP" && ls contracts/openapi/*.yaml contracts/openapi/internal/*.yaml 2>/dev/null || true); do
  [ -f "$f" ] || { echo "ERROR 명세 파일 삭제: $f"; status=1; }
done
python3 contracts/tools/check_schema_compat.py "$TMP/contracts" contracts || status=1

[ "$status" -eq 0 ] && echo "✅ 계약 검사 통과" || echo "❌ 하위 호환이 깨지는 변경이 있습니다"
exit "$status"

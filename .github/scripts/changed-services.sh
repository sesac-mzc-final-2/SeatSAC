#!/usr/bin/env bash
# 이미지를 만들 서비스 고르기 — pr-check(image)와 build-push가 같이 쓴다 (3.1.4)
#
# 입력 (환경변수)
#   MODE           pr | push | dispatch
#   BASE · HEAD    비교할 커밋 (pr: PR base·head SHA, push: github.event.before·GITHUB_SHA)
#   INPUT_SERVICES dispatch일 때: "all" 또는 쉼표·공백 목록
#   EXCLUDE        (선택) 공백 목록. 이번 실행에서 뺄 서비스 — build-push가 배포 준비 전인 ai를 뺄 때 쓴다
# 출력
#   $GITHUB_OUTPUT 에 services=["booking",...] (JSON 배열, 없으면 [])
#
# 규칙
#   - services/{svc}/** 가 바뀐 서비스만
#   - pr 모드만: Gradle 빌드 공통(build-logic/·gradle/·settings.gradle.kts·build.gradle.kts)이 바뀌면 Java 5개 전부,
#     이미지 스크립트·워크플로가 바뀌면 ai까지 6개 전부 (이미지가 깨지는지 미리 본다)
#   - ai(Python, ADR-82)는 Gradle이 없다 → 워크플로가 services/{svc}/build.gradle.kts 유무로 bootJar를 건너뛴다
#   - services/{svc}/Dockerfile 이 아직 없는 서비스는 고르지 않는다(ai 골격 3.4.8 머지 전에 스크립트만 바뀐 PR 등)
#   - push 모드는 services/** 만 → 공통 모듈만 바뀐 머지는 배포하지 않는다 (docs/repo-workflow.md 4장)
#   - libs/ 는 이미지 빌드와 무관(앱 테스트는 java job) → pr 모드에서도 전체 빌드 대상 아님
set -euo pipefail

JAVA_SERVICES="gateway catalog booking user payment" # Gradle bootJar → docker build
PYTHON_SERVICES="ai"                                  # docker build만 (uv, services/ai/Dockerfile)
ALL_SERVICES="$JAVA_SERVICES $PYTHON_SERVICES"
ZERO=0000000000000000000000000000000000000000
out="${GITHUB_OUTPUT:-/dev/stdout}"

emit() {
  local picked=("$@")
  echo "services=$(jq -cn '$ARGS.positional' --args "${picked[@]}")" >> "$out"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    echo "이미지 대상: ${picked[*]:-없음}" >> "$GITHUB_STEP_SUMMARY"
  fi
  echo "이미지 대상: ${picked[*]:-없음}" >&2
}

case "${MODE:?MODE=pr|push|dispatch}" in
  dispatch)
    req=$(echo "${INPUT_SERVICES:-}" | tr ',' ' ' | xargs)
    [ "$req" = "all" ] && req="$ALL_SERVICES"
    ;;
  pr|push)
    if [ -z "${BASE:-}" ] || [ "$BASE" = "$ZERO" ] || ! git cat-file -e "${BASE}^{commit}" 2>/dev/null; then
      echo "::warning::비교할 이전 커밋이 없어 전체를 대상으로 한다" >&2
      req="$ALL_SERVICES"
    else
      if [ "$MODE" = pr ]; then
        range="$BASE...${HEAD:?}" # PR: base와 갈라진 지점부터 (main이 앞서 나가도 PR 변경만)
      else
        range="$BASE..${HEAD:?}"
      fi
      files=$(git diff --name-only "$range")
      req=$(printf '%s\n' "$files" | sed -n 's#^services/\([^/]*\)/.*#\1#p' | sort -u | xargs)
      if [ "$MODE" = pr ]; then
        if printf '%s\n' "$files" | grep -qE '^(\.github/scripts/|\.github/workflows/(pr-check|build-push)\.yml$)'; then
          echo "이미지 스크립트·워크플로 변경 → 6개 전부" >&2
          req="$ALL_SERVICES"
        elif printf '%s\n' "$files" | grep -qE '^(build-logic/|gradle/|settings\.gradle\.kts$|build\.gradle\.kts$)'; then
          echo "Gradle 빌드 공통 변경 → Java 5개 전부 + 바뀐 서비스" >&2
          req="$JAVA_SERVICES $req"
        fi
      fi
    fi
    ;;
  *) echo "::error::MODE는 pr·push·dispatch" >&2; exit 1 ;;
esac

picked=()
for s in $(printf '%s\n' $req | awk '!seen[$0]++'); do
  if [[ " $ALL_SERVICES " != *" $s "* ]]; then
    if [ "$MODE" = dispatch ]; then
      echo "::error::알 수 없는 서비스: $s (가능: $ALL_SERVICES)" >&2; exit 1
    fi
    continue # pr·push: services/ 아래 다른 폴더(예: README)는 무시
  fi
  if [[ " ${EXCLUDE:-} " == *" $s "* ]]; then
    echo "::warning::$s 는 이번 실행에서 뺀다 (EXCLUDE — ai는 vars.AI_BUILD_PUSH_ENABLED=true 전까지 build-push 대상 아님, 런북 6-G-1)" >&2
    continue
  fi
  if [ ! -f "services/$s/Dockerfile" ]; then
    if [ "$MODE" = dispatch ]; then
      echo "::error::services/$s/Dockerfile 이 없다" >&2; exit 1
    fi
    echo "::notice::services/$s/Dockerfile 이 아직 없어 건너뛴다" >&2
    continue
  fi
  picked+=("$s")
done
emit "${picked[@]}"

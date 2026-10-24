#!/usr/bin/env bash
# 이미지 취약점 검사 — pr-check(image)와 build-push가 같이 쓴다 (3.1.4)
#   사용: .github/scripts/trivy-scan.sh <이미지> <요약에 쓸 이름>
#   - HIGH·CRITICAL(수정 버전 있는 것)은 실행 요약에 표로 남긴다
#   - 실패 조건: CRITICAL + 수정 버전 있음. 당장 못 고치는 오탐만 레포 루트 .trivyignore 에 CVE ID + 이유(PR 리뷰)
#   - Trivy 0.69.4는 2026-03 공급망 사고 버전(GHSA-69fq-xp46-6x23) → 버전 고정 + 체크섬 확인
set -euo pipefail

TRIVY_VERSION="${TRIVY_VERSION:-0.69.3}" # 올릴 때 여기만
img="${1:?이미지}"
label="${2:-$1}" # 요약에는 레지스트리 주소(계정 ID) 대신 이 이름을 쓴다

if ! command -v trivy >/dev/null || [ "$(trivy --version 2>/dev/null | awk '/^Version:/{print $2}')" != "$TRIVY_VERSION" ]; then
  tmp=$(mktemp -d)
  base="https://github.com/aquasecurity/trivy/releases/download/v${TRIVY_VERSION}"
  tgz="trivy_${TRIVY_VERSION}_Linux-64bit.tar.gz"
  (cd "$tmp" \
    && curl -sSfLO "$base/$tgz" \
    && curl -sSfL "$base/trivy_${TRIVY_VERSION}_checksums.txt" | grep " $tgz\$" | sha256sum -c - \
    && sudo tar -xzf "$tgz" -C /usr/local/bin trivy)
  rm -rf "$tmp"
fi
trivy --version | head -1

ignore=()
[ -f .trivyignore ] && ignore=(--ignorefile .trivyignore)

{
  echo "### Trivy ${label} (HIGH·CRITICAL, 수정 버전 있는 것)"
  echo '```'
  trivy image --quiet --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed "${ignore[@]}" --format table "$img" || true
  echo '```'
} >> "${GITHUB_STEP_SUMMARY:-/dev/stdout}"

trivy image --quiet --scanners vuln --severity CRITICAL --ignore-unfixed "${ignore[@]}" --exit-code 1 "$img"

# 레포·브랜치·PR·CI 규칙

> 기준 2026-10-22 (회의록2 결정 반영, ADR-49~64). 03b 레포·브랜치·PR 개정 확정본 + 08 공통 코드(B안, ADR-56) + 브랜치 WBS ID(ADR-48) + 레포·일정(ADR-64) + 2.2.7 계약 CI.
> 전제: staging 하나가 곧 데모 환경이라 **main 머지 = 배포**다.

---

## 1. 레포

| 레포 | 공개 | 내용 |
| --- | --- | --- |
| `sesac-mzc-final-2/SeatSAC` (앱, 03b의 `cinema`) | 개발 중 Private → 발표 후 Public | 서비스, frontend, 계약, Terraform, Lambda, 테스트, 문서 |
| `SeatSAC-gitops` | Private | Kustomize 매니페스트, ArgoCD Application. CI 봇이 이미지 태그 커밋 |
| `lotte-seed` | **항상 Private** | 수집 스크립트, 원본 스냅샷, 좌석 배치·편성 시드 SQL |

```
SeatSAC/
├── settings.gradle.kts · gradle/libs.versions.toml · build-logic/   빌드 설정 공통 (08)
├── libs/common-core · common-web · common-test                     런타임 규약만 (오류·traceId·헤더·로그·내부 클라이언트)
├── services/{gateway,catalog,booking,user,payment}/                각 Dockerfile, db/migration
├── frontend/                                                       React(Vite) → S3
├── contracts/{openapi,events,schemas,tools}/                       계약 + 계약 CI
├── lambdas/{notification,cognito-presignup}/                       Python
├── infra/terraform/{bootstrap,global,envs/staging}/ · infra/compose/
├── tests/k6/
├── docs/   conventions.md · contracts.md · service-runtime-contract.md · data-model.md · decisions.md · repo-workflow.md · runbooks/
└── .github/ workflows/ · CODEOWNERS · pull_request_template.md

SeatSAC-gitops/
├── apps/              App of Apps
├── platform/          LB Controller, Argo Rollouts, kube-prometheus-stack
├── base/{service}/    Deployment 또는 Rollout, Service(+preview), HPA, NetworkPolicy, ServiceMonitor, Flyway PreSync Job
├── overlays/staging/  이미지 태그, replicas, ConfigMap
└── analysis/          AnalysisTemplate
```

- `services/ai/`·`lambdas/daily-stats/`는 10/22 삭제(ADR-50·51)
- Secret 매니페스트는 gitops에 없다(Terraform이 생성, Deployment는 이름으로만 참조)

## 2. 브랜치·커밋

| 항목 | 규칙 |
| --- | --- |
| 기본 브랜치 | `main` 보호, 직접 push 금지. **main 머지 = staging 자동 배포** |
| 브랜치 이름 | `{type}/{기능ID}-{설명}`. 기능 ID가 없으면 **WBS ID** (ADR-48) |
| type | `feat` · `fix` · `refactor` · `test` · `docs` · `infra` · `ci` · `chore` |
| 예 | `feat/BKG-02-seat-hold`, `fix/CAT-05-cache-key`, `infra/3.1.3-rds-valkey`, `ci/2.2.7-contract-ci`, `docs/ADR-47` |
| 수명 | 최대 3일. 길어지면 기능 플래그로 나눠 머지 |
| 머지 | Squash merge |
| 커밋 메시지 | Conventional Commits + 기능 ID 또는 WBS ID: `feat(booking): BKG-02 좌석 선점 Lua 스크립트`, `infra(terraform): 3.1.3 RDS·Valkey` |
| 릴리스 태그 | `rc1` 11/6(M5), `rc2` 11/13(M6), `v1.0.0` 11/19(리허설 전) |
| 동결 | 11/13 이후 `fix/`만, 승인 2명 + 김종식 확인. **11/16~20 데모 주간은 승인된 fix 외 머지 금지** |

## 3. PR

| 항목 | 규칙 |
| --- | --- |
| 승인 | 1명 (11/13 이후 2명). `libs/`·`build-logic/` 변경은 2명(김종식 + 영향받는 서비스 담당) |
| 필수 검사 | `pr-check`, `contract-check / lint (OpenAPI · JSON Schema · 예시)`, `contract-check / 하위 호환 (base 대비)`, (`infra/terraform/**`이면) `infra-plan` |
| 크기 | 400줄 이하 권장(생성 코드·lock 제외) |
| 리뷰 응답 | 24시간 이내(주말 제외) |
| 머지 후 | 작성자가 ArgoCD Synced·Healthy(또는 frontend 배포 완료)까지 확인 |
| 템플릿 | 변경 요약 · WBS ID·기능 ID · 테스트 방법 · 스크린샷(화면) · **계약 변경 여부** · **DB 마이그레이션 단계(확장·전환·정리)** · **배포 영향(env·Secret·gitops·CloudFront 캐시)** |
| 계약 깨는 변경 | 구현 전 설계 변경만 라벨 `contract-break-ok` + CODEOWNERS 승인, PR 본문에 이유 |

**리뷰 체크리스트**

- DB: 컬럼 삭제·이름 변경은 다음 배포로(두 버전이 같은 DB를 쓴다)
- 계약: 필드 추가만. 삭제·타입 변경은 새 엔드포인트·`.v2`
- 이벤트: 소비자는 모르는 필드를 무시한다
- booking 만료·릴레이 로직: preview 파드도 돌므로 이전 버전과 호환
- 설정 이름이 `docs/service-runtime-contract.md` 3장과 같은지

**CODEOWNERS** (`.github/CODEOWNERS`, GitHub 아이디는 실제 값으로 확인)

| 경로 | 담당 |
| --- | --- |
| `services/gateway·catalog·user/` | 한채연 |
| `services/booking·payment/` | 남궁일 |
| `frontend/` | 한채연 · 남궁일 |
| `contracts/` | 한채연 · 남궁일 |
| `contracts/tools/`, `contracts/redocly.yaml`, `build-logic/`, `gradle/`, `lambdas/`, `infra/`, `.github/`, `tests/k6/` | 김종식 |
| `libs/` | 김종식 · 한채연 · 남궁일 |

## 4. CI/CD 워크플로

| 워크플로 | 트리거 | 하는 일 |
| --- | --- | --- |
| `pr-check` | PR | 바뀐 서비스 빌드·테스트(Testcontainers MySQL 8.4·Valkey 8.2·ElasticMQ), `libs/**` 변경 시 5개 전부, Trivy, gitleaks. 2단계: springdoc 생성본 vs 계약 diff |
| `contract-check` | PR (항상, 변경 없으면 건너뜀) | Redocly lint, JSON Schema·예시 검증, oasdiff·스키마 하위 호환 |
| `build-push` | main 머지 (바뀐 서비스만) | x86 이미지 → ECR(OIDC `cinema-gha-ecr-push`) → `SeatSAC-gitops`에 `kustomize edit set image` 커밋 |
| `frontend-deploy` | main 머지(`frontend/**`) + 수동 | Vite 빌드 → S3(`/assets/*` 1년 immutable, `index.html` no-cache) → 무효화 |
| `lambda-deploy` | main 머지(`lambdas/**`) | 패키징 → 배포 → 별칭 갱신 |
| `infra-plan` | PR(`infra/terraform/**`) | `terraform plan`(OIDC `cinema-gha-tf-plan`) → PR 코멘트. **apply는 김종식이 로컬에서 `cinema-admin`으로** |
| `cluster-schedule` | 평일 아침·밤 cron + 수동 | 노드그룹 desired 1 ↔ 0 (ng-a·ng-c) |
| `gitops-validate` (gitops 레포) | PR | `kustomize build` + kubeconform |

- OIDC 역할 세션 이름 `gha-${{ github.actor }}-${{ github.run_id }}`(CloudTrail에 실행자)
- 공통 모듈만 바뀐 머지는 배포하지 않는다. 급하면 `build-push` 수동 실행으로 한 서비스씩

## 5. 지켜야 할 것

| 규칙 | 이유 |
| --- | --- |
| 비밀 값(키·비밀번호·tfstate 내용·tfvars의 민감 값)을 Git·문서·PR에 쓰지 않는다 | 발표 후 Public 전환. gitleaks가 막는다 |
| 롯데 원본·fixture·시드는 `lotte-seed`에만 | 준수 조건(공개 금지) |
| 롯데 실제 예매 API 호출 금지, 롯데 포스터 이미지 표시 금지 | 준수 조건 |
| 사람은 ECR·frontend S3에 직접 올리지 않는다 | ECR 저장소 정책(CI 역할만 push) + 태그 불변 |
| AWS 콘솔에서 리소스를 만들지 않는다. IAM·OIDC는 `infra/terraform/global`, 나머지는 `envs/staging` | 10/21 OIDC 공급자 중복 사례 |
| `terraform destroy`로 bootstrap·global을 지우지 않는다 | tfstate·팀 접근 상실 |
| 클러스터 직접 수정 금지(selfHeal이 되돌림). 긴급 시 해당 앱 auto-sync 끄고 작업 → Git 반영 → 스크럼 공유 | GitOps |
| 팀원 평소 작업은 `cinema-dev` 프로필(MFA). `cinema-admin`은 김종식 + 백업 관리자 남궁일(`sesac-b3`) | ADR-47 |
| 작업·진척은 노션 WBS만. GitHub Issues 안 씀 | 03b |
| 발표 후 Public 전환 전: gitleaks 전체 이력 스캔, 시드·원본·`.env`·tfvars 없음 확인 | — |

<노션 반영 필요> 03b 결정 칸에 "10/22 확정 + 레포 이름 SeatSAC·SeatSAC-gitops, `libs/` 유지(08 B안), contract-check 필수 검사, 브랜치 WBS ID 보완(ADR-48)", 최종 결정 21번 "공통 코드 복사" → "공유 모듈"

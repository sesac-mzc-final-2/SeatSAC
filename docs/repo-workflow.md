# 레포·브랜치·PR·CI 규칙

> 기준 2026-10-22 (회의록2 결정 반영, ADR-49~64). 03b 레포·브랜치·PR 개정 확정본 + 08 공통 코드(B안, ADR-56) + 브랜치 WBS ID(ADR-48) + 레포·일정(ADR-64) + 2.2.7 계약 CI.
> 전제: staging 하나가 곧 데모 환경이라 **main 머지 = 배포**다(3.1.4부터 `build-push`가 main 머지마다 자동으로 돈다).
> ⚠️AI 11/5 AI 재도입(ADR-82·86·87): `services/ai`(Python) 추가, AI 전용 머지 기간 규칙은 2장 「⚠️AI 일정·머지」.

---

## 1. 레포

| 레포 | 공개 | 내용 |
| --- | --- | --- |
| `sesac-mzc-final-2/SeatSAC` (앱, 03b의 `cinema`) | 개발 중 Private → 발표 후 Public | 서비스, frontend, 계약, Terraform, Lambda, 테스트, 문서 |
| `SeatSAC-gitops` | ⚠️ **Public** (무료 플랜 ruleset, ADR-74) — 계정 ID·엔드포인트·키 금지(`gitops-validate`가 막음) | Kustomize 매니페스트, 플랫폼 ArgoCD Application. CI 앱 봇이 이미지 태그만 커밋. 서비스 Application·엔드포인트는 Terraform `envs/staging-k8s` |
| `lotte-seed` | **항상 Private** | 수집 스크립트, 원본 스냅샷, 좌석 배치·편성 시드 SQL |

```
SeatSAC/
├── settings.gradle.kts · gradle/libs.versions.toml · build-logic/   빌드 설정 공통 (08)
├── libs/common-core · common-web · common-test                     런타임 규약만 (오류·traceId·헤더·로그·내부 클라이언트)
├── services/{gateway,catalog,booking,user,payment}/                각 Dockerfile, db/migration
├── services/ai/                                                    ⚠️AI Python(FastAPI) — pyproject.toml·uv.lock·Dockerfile·src/seatsac_ai·tests, .env(커밋 금지)
├── frontend/                                                       React(Vite) → S3
├── contracts/{openapi,events,schemas,tools}/                       계약 + 계약 CI
├── lambdas/{notification,cognito-presignup}/                       Python
├── infra/terraform/{bootstrap,global,envs/staging,envs/staging-k8s}/ · infra/compose/
├── tests/k6/
├── docs/   conventions.md · contracts.md · service-runtime-contract.md · data-model.md · decisions.md · repo-workflow.md · runbooks/
└── .github/ workflows/ · scripts/(서비스 고르기·Trivy, pr-check·build-push 공용) · CODEOWNERS · pull_request_template.md

SeatSAC-gitops/
├── apps/staging/          App of Apps 대상(project·플랫폼 2·cinema-common). root 앱·서비스 Application 5개는 SeatSAC Terraform(staging-k8s argocd_apps.tf)
├── platform/              Argo Rollouts, kube-prometheus-stack values (LB Controller는 Terraform envs/staging-k8s)
├── base/{service}/        Deployment 또는 Rollout, Service(+preview), HPA, NetworkPolicy, PodMonitor, Flyway 훅 Job(Sync wave -1)
│                          ⚠️AI base/ai: Deployment(nodeSelector·toleration ng-ai)·Service·NetworkPolicy(gateway·monitoring → ai)·PodMonitor (HPA·Flyway 없음)
├── base/common/           앱 namespace 인바운드 기본 거부 + analysis
├── overlays/staging/{service}/  ConfigMap {service}-config(엔드포인트는 ${키}) + image-tag/(newTag만)
├── components/rollout/    kustomize가 Rollout 안 ConfigMap 해시 이름을 바꾸게 하는 설정
├── analysis/              AnalysisTemplate (booking·gateway)
└── .github/               CODEOWNERS · PR 템플릿 · gitops-validate
```

- `lambdas/daily-stats/`는 10/22 삭제(ADR-51). ⚠️AI `services/ai/`는 10/22 삭제(ADR-50) 후 11/5 **Python으로 다시 만든다**(ADR-82). 서비스 Application은 Terraform `argocd_apps.tf`에 `ai` → `cinema-ai`(gitops `apps/`에는 두지 않음)
- Secret 매니페스트는 gitops에 없다(Terraform이 생성, Deployment는 이름으로만 참조)

## 2. 브랜치·커밋

| 항목 | 규칙 |
| --- | --- |
| 기본 브랜치 | `main` 보호(ruleset), 직접 push 금지. **main 머지 = staging 자동 배포**(`services/**`가 바뀐 머지 → `build-push`, 3.1.4) |
| 브랜치 이름 | `{type}/{기능ID}-{설명}`. 기능 ID가 없으면 **WBS ID** (ADR-48) |
| type | `feat` · `fix` · `refactor` · `test` · `docs` · `infra` · `ci` · `chore` |
| 예 | `feat/BKG-02-seat-hold`, `fix/CAT-05-cache-key`, `infra/3.1.3-rds-valkey`, `ci/2.2.7-contract-ci`, `docs/ADR-47` |
| 수명 | 최대 3일. 길어지면 기능 플래그로 나눠 머지 |
| 머지 | Squash merge |
| 커밋 메시지 | Conventional Commits + 기능 ID 또는 WBS ID: `feat(booking): BKG-02 좌석 선점 Lua 스크립트`, `infra(terraform): 3.1.3 RDS·Valkey` |
| 릴리스 태그 | `rc1` 11/6(M5), `rc2` 11/13(M6), `v1.0.0` 11/19(리허설 전) |
| 동결 | 11/13 이후 `fix/`만, 승인 2명 + 김종식 확인. **11/16~20 데모 주간은 승인된 fix 외 머지 금지** |
| ⚠️AI 일정·머지 (ADR-87) | **rc1(11/6)**: gateway `/api/ai/**` 라우트(플래그 off)·gateway 분석 쿼리 `/ai` 제외 머지 완료. ai 인프라·골격은 플래그 off로 staging 배포 가능 · **M6(11/9~13)**: AI 기능은 계속 머지할 수 있다 — 허용 경로는 `services/ai/**`·`contracts/openapi/ai*`·gitops `base/ai/**`·`overlays/staging/ai/**`만. gateway·booking·`libs/`·Terraform 변경은 이 기간 금지(부하·전환 테스트 환경 보호). **테스트 준비 예외(11/10 일정 변경)**: 4.2.1 부하 테스트 준비가 M6로 밀려 테스트 도구·문서는 M6에도 머지할 수 있다 — `tests/k6/**`, `docs/**`(런북·문서, `CLAUDE.md` 재생성 포함), gitops `base/*/networkpolicy.yaml`의 **테스트 Job 라벨 허용 규칙 추가만**(예: `k6-loadtest` → gateway. Rollout·Deployment 템플릿은 그대로라 블루/그린·재시작이 없다). 조건: PR 본문에 「M6 예외(테스트 준비)」와 이유, 스크럼 공유, 4.2.1·4.2.2 테스트가 돌지 않는 시간에 머지. 서비스 코드·Terraform 금지는 그대로. **AI 화면 예외(11/12)**: 3.3.7 챗봇 화면도 M6에 머지할 수 있다 — 새 파일은 `frontend/src/entities/ai-chat/**`·`frontend/src/features/ai-chat/**`·`frontend/mock/**`·`frontend/README.md`, 기존 파일은 **추가만**(기존 동작 그대로): `frontend/src/widgets/page-layout/ui/PageLayout.tsx`(Dock import·배치), `frontend/src/shared/ui/icon/Icon.tsx`(아이콘 경로), `frontend/src/shared/api/http.ts`(`Retry-After`를 `ApiError`에 담기 — 기존 오류 처리 그대로, `http.test.ts` 포함), `frontend/src/app/routes/router.tsx`(주석만). `http.ts`는 모든 화면이 쓰는 클라이언트라 `pr-check` `frontend`(vitest) 통과를 조건에 넣는다. frontend는 S3 + CloudFront 배포라 클러스터 파드·블루/그린·노드와 무관하고, 화면은 챗봇 창을 열 때만 `/api/ai/**`를 부른다(런북 6-G-5) → 로그인·메인 진입 때 ai 트래픽이 없어 4.2.1·4.2.2·좌석 데모 측정에 섞이지 않는다. 조건: PR 본문에 「M6 예외(AI 화면)」와 이유, 스크럼 공유, 테스트가 돌지 않는 시간에 머지(`frontend-deploy`는 S3 업로드·CloudFront 무효화만). 그 밖의 `frontend/**`는 금지 그대로. 테스트(4.2.1·4.2.2)는 `ng-ai` desired 0·플래그 off. ai 검증은 로컬 Compose + **개발 전용 Claude API 키**(`.env`), staging 확인은 테스트가 돌지 않는 시간에 `kubectl port-forward svc/ai`(스크럼에서 시간 공유) · **rc2(11/13)**: ai 코드도 동결, 이후 ai는 `fix/`만. M6 테스트가 끝난 뒤 gateway `APP_AI_ENABLED=true` gitops PR 1회 · **데모 주간(11/16~20)**: ai `fix/`도 머지 금지(ADR-64 그대로). 11/17 AI 데모는 `ng-ai` 수동 기동(머지 아님) |

## 3. PR

| 항목 | 규칙 |
| --- | --- |
| 승인 | 1명 (11/13 이후 2명). `libs/`·`build-logic/` 변경은 2명(김종식 + 영향받는 서비스 담당) |
| 필수 검사 (ruleset) | `java`·`gitleaks`·`image`·`frontend`(pr-check), `lint (OpenAPI · JSON Schema · 예시)`·`하위 호환 (base 대비)`(contract-check). 여섯 검사 모두 **모든 PR에서 실행**된다(contract-check는 `paths` 필터 없이 `변경 감지` job이 계약 변경이 없으면 두 job을 건너뜀 = 성공. `image`도 같은 방식: 이미지 대상이 없으면 건너뜀 = 성공. `frontend`도 같은 방식: `frontend 변경 감지` job이 `frontend/**` 변경이 없으면 건너뜀 = 성공). `infra-plan`은 `paths` 필터가 있어 필수로 걸지 않는다 — `infra/terraform/**` PR은 리뷰어가 plan 코멘트를 확인. `java`는 Gradle wrapper·서비스 골격이 들어온 뒤 필수로 추가. ⚠️AI `python`(pr-check)도 같은 「변경 없으면 건너뜀」 방식으로 필수 검사에 추가 → 일곱 검사(워크플로가 main에 들어간 뒤 ruleset에 `python`을 추가한다 — 그 전에 추가하면 job이 없어 모든 PR이 대기) |
| 크기 | 400줄 이하 권장(생성 코드·lock 제외) |
| 리뷰 응답 | 24시간 이내(주말 제외) |
| 머지 후 | 작성자가 ArgoCD Synced·Healthy(또는 frontend 배포 완료)까지 확인 — `kubectl -n argocd get applications` 또는 `kubectl -n argocd port-forward svc/argocd-server 8080:80`(읽기 전용 화면) |
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
| ⚠️AI `services/ai/`, `contracts/openapi/ai*` | 김서현 (계약은 + 한채연) |
| `frontend/` | 한채연 · 남궁일 |
| `contracts/` | 한채연 · 남궁일 |
| `contracts/tools/`, `contracts/redocly.yaml`, `build-logic/`, `gradle/`, `lambdas/`, `infra/`, `.github/`, `tests/k6/` | 김종식 |
| `libs/` | 김종식 · 한채연 · 남궁일 |

## 4. CI/CD 워크플로

| 워크플로 | 트리거 | 하는 일 |
| --- | --- | --- |
| `pr-check` | PR | ⚠️AI `python`: `services/ai/**`가 바뀐 PR만 Python 3.12 → `uv sync --locked`(uv 버전은 `services/ai/Dockerfile`의 `ARG UV_VERSION`) → `ruff check` → `pytest`(Claude API는 목, 키·AWS 권한 없음, 바뀌지 않으면 건너뜀 = 성공 — 필수 검사로 추가). `image`의 서비스 고르기(`changed-services.sh`)는 Java 5개 + `ai`이고, **`services/{svc}/build.gradle.kts`가 없는 ai는 Gradle `bootJar` 없이 `docker build services/ai`**. ai 이미지는 PR에서 **실제로 띄워 본다**(`--read-only --tmpfs /tmp`, liveness 200, readiness HTTP 상태 200 또는 503만 확인(스냅샷 적재·ai Valkey 연결 성공은 보장하지 않음), `http_server_requests_seconds{application="ai"}`, 헤더 없는 `/ai/presets` → 401 + `error.v1.json` 형식). `services/{svc}/Dockerfile`이 아직 없는 서비스는 고르지 않는다. `java`: 빌드·테스트(Testcontainers MySQL 8.4·Valkey 8.2·ElasticMQ) · `gitleaks` · `image`: 바뀐 서비스만 bootJar → `docker build` → 비루트 확인 → Trivy(push 안 함, AWS 권한 없음. `build-logic/`·`gradle/`·이미지 스크립트·워크플로가 바뀌면 5개 전부) · `lambda`: `lambdas/notification` 단위 테스트(Python 3.12 `unittest`, 의존성·AWS 권한 없음, 필수 검사 아님) · `frontend`: `frontend/**`가 바뀐 PR만 node 24 → `npm ci` → `npm run check`(tsc -b · eslint · steiger FSD · vitest) → `npm run build`(`dist/index.html` 확인). frontend-deploy와 같은 node·npm 설정, AWS 권한 없음. 실패하면 같은 PR 브랜치에 고쳐서 push → 자동 재실행. 2단계: springdoc 생성본 vs 계약 diff |
| `contract-check` | PR (항상, 변경 없으면 건너뜀) | Redocly lint, JSON Schema·예시 검증, oasdiff·스키마 하위 호환 |
| `build-push` | main 머지(`services/**`, 바뀐 서비스만) + 수동(`services`=all 또는 목록, main에서만) | bootJar → x86 이미지 → Trivy(CRITICAL·수정 가능이면 실패) → ECR `cinema/{svc}:{sha7}`(OIDC `cinema-gha-ecr-push`, 같은 태그 있으면 건너뜀) → `SeatSAC-gitops` `overlays/staging/{svc}/image-tag`의 **`newTag`만** yq로 GitHub App `seatsac-build-push`(gitops에만 설치, Contents 쓰기) 토큰으로 main에 커밋(조직 정책상 Deploy key 불가). 레지스트리(계정 ID)는 gitops에 쓰지 않는다(Public, ADR-74) — Terraform ArgoCD Application이 붙인다. booking·gateway가 같이 바뀌면 booking만(블루/그린 동시 금지). ⚠️AI ai는 `docker build services/ai`(Gradle 없음) → ECR `cinema/ai` → `overlays/staging/ai/image-tag`. 저장소 변수 **`AI_BUILD_PUSH_ENABLED`가 `true`가 아니면 ai는 건너뛴다**(경고) — ECR `cinema/ai`(3.1.18)·gitops `overlays/staging/ai`(3.1.20)가 생긴 뒤 김종식이 켠다(런북 6-G-1). 그 전에는 ai 머지가 빌드·배포 없이 끝난다 |
| `frontend-deploy` | main 머지(`frontend/**`) + 수동 | Vite 빌드 → S3(`/assets/*` 1년 immutable, `index.html` no-cache) → 무효화 |
| `lambda-deploy` | **없음(현재)** | Lambda 배포는 Terraform `archive_file`(3.1.16 Pre sign-up, 3.1.8 알림)이 코드를 묶어 올린다. 코드가 바뀌면 `infra-plan`에 함수 update가 보이고 apply는 김종식이 로컬에서 `cinema-admin`으로. 별칭(alias)은 쓰지 않는다. 필요해지면 이 워크플로를 추가(CI에 배포 권한이 필요) |
| `infra-plan` | PR(`infra/terraform/**`·`lambdas/**`, 필수 검사 아님) | `terraform plan`(OIDC `cinema-gha-tf-plan`) → PR 코멘트. **apply는 김종식이 로컬에서 `cinema-admin`으로** |
| `cluster-schedule` | **백업** 월~금 08:53 켜기 · 23:37·00:37 끄기(KST) + 수동(up·down·status — `up`은 `ng-ai`를 켜지 않고 `down`은 `ng-ai`까지 끈다) | 정시 켜기·끄기(08:23·23:07)는 **AWS EventBridge Scheduler**(`envs/staging/cluster_schedule.tf`)가 한다 — GitHub schedule은 몇 시간씩 늦거나 빠져서(10/23·10/26) 이 워크플로는 30분 뒤 백업·수동용. 백업은 늦게 도착하면(끄기 월~금 22~23시·화~토 0~5시, 켜기 월~금 7~10시 밖) 건너뛰고, 데모 주간 11/16~20·EventBridge 끄기 DISABLED(`cluster_keep_on`)면 끄기 건너뜀. 노드그룹 desired 1 ↔ 0 (OIDC `cinema-gha-cluster-schedule`), 스크립트 `infra/scripts/cluster-scale.sh` |
| `gitops-validate` (gitops 레포) | PR | `kustomize build` + kubeconform, Secret 매니페스트·계정 ID·`*.amazonaws.com`·`newName` 금지, gitleaks |

- OIDC 역할 세션 이름 `gha-${{ github.actor }}-${{ github.run_id }}`(CloudTrail에 실행자)
- 이미지는 두 번 빌드된다: PR의 `image`(검사용, 버림)와 main의 `build-push`(진짜, 태그 = main 커밋 SHA). 서비스 고르기·Trivy는 `.github/scripts/`를 같이 쓴다
- 공통 모듈(`libs/`·`build-logic/`·`gradle/`)만 바뀐 머지는 배포하지 않는다. 급하면 `build-push` 수동 실행으로 한 서비스씩
- 롤백 = `SeatSAC-gitops`에서 해당 태그 커밋을 revert하는 PR. ECR 이미지는 저장소당 최근 30개가 남는다

## 5. 지켜야 할 것

| 규칙 | 이유 |
| --- | --- |
| 비밀 값(키·비밀번호·tfstate 내용·tfvars의 민감 값)을 Git·문서·PR에 쓰지 않는다. ⚠️AI Claude API 키 포함 — 로컬은 개발 전용 키를 `services/ai/.env`에만 | 발표 후 Public 전환. gitleaks가 막는다 |
| 롯데 원본·fixture·시드는 `lotte-seed`에만 | 준수 조건(공개 금지) |
| 롯데 실제 예매 API 호출 금지, 롯데 포스터 이미지 표시 금지 | 준수 조건 |
| 사람은 ECR·frontend S3에 직접 올리지 않는다 | ECR 저장소 정책(CI 역할만 push) + 태그 불변 |
| AWS 콘솔에서 리소스를 만들지 않는다. IAM·OIDC는 `infra/terraform/global`, 나머지는 `envs/staging` | 10/21 OIDC 공급자 중복 사례 |
| `terraform destroy`로 bootstrap·global을 지우지 않는다 | tfstate·팀 접근 상실 |
| 클러스터 직접 수정 금지(selfHeal이 되돌림). 긴급 시 해당 앱 auto-sync 끄고 작업 → Git 반영 → 스크럼 공유 | GitOps |
| 팀원 평소 작업은 `cinema-dev` 프로필(MFA). `cinema-admin`은 김종식 + 백업 관리자 남궁일 | ADR-47 |
| 작업·진척은 노션 WBS만. GitHub Issues 안 씀 | 03b |
| 발표 후 Public 전환 전: gitleaks 전체 이력 스캔, 시드·원본·`.env`·tfvars 없음 확인. ⚠️AI 개발용·staging Claude API 키 폐기(Anthropic 콘솔) | — |

<노션 반영 필요> 03b 결정 칸에 "10/22 확정 + 레포 이름 SeatSAC·SeatSAC-gitops, `libs/` 유지(08 B안), contract-check 필수 검사, 브랜치 WBS ID 보완(ADR-48)", 최종 결정 21번 "공통 코드 복사" → "공유 모듈"

# 🎬 SeatSAC (싯싹)

> 실시간 좌석 선점 · 무중단 배포를 보여주는 영화 예매 사이트 — 핵심 도메인 MSA + Valkey + EKS + GitOps
>
> 기간 2026-10-14 ~ 11-20 (발표 11/20) · 4명 · 작업 관리는 **노션 WBS**, 코드는 **이 레포**

---

## 목차

1. [프로젝트 개요](#1-프로젝트-개요)
2. [초기 세팅](#2-초기-세팅)
3. [브랜치 → push → PR](#3-브랜치--push--pr)
4. [GitHub Actions가 확인하는 것](#4-github-actions가-확인하는-것)
5. [프로젝트하면서 알아야 할 것](#5-프로젝트하면서-알아야-할-것)
6. [자주 막히는 곳](#6-자주-막히는-곳)

---

## 1. 프로젝트 개요

### 데모 포인트

| # | 데모 | 증명할 숫자 |
| --- | --- | --- |
| D1 | 무중단 배포 | 블루/그린 전환 중 k6 오류율 0%, 실패 버전 자동 롤백 |
| D2 | 실시간 좌석 | 같은 좌석 100명 동시 요청 → 성공 정확히 1명, 선점 p95 300ms, 다른 화면 반영 1초 |

### 팀

| 담당 | 영역 | 코드 |
| --- | --- | --- |
| 김종식 | Infra · DevOps | `infra/` `lambdas/` `.github/` `build-logic/` `tests/k6/` |
| 남궁일 | Booking · Payment · 좌석 화면 | `services/booking/` `services/payment/` |
| 한채연 | Gateway · Catalog · User · 화면 | `services/gateway/` `services/catalog/` `services/user/` `frontend/` |
| 김서현 | AI (범위 확정 전) | `services/ai/` |

### 아키텍처 (v5)

```
사용자 ─HTTPS─▶ CloudFront ─┬─ /*      ▶ S3 (React 빌드, OAC)
        │                   └─ /api/*  ▶ ALB ▶ gateway ─▶ catalog · booking · user ─▶ payment(내부만)
        └─ 로그인 ▶ Cognito (Hosted UI · PKCE)
                                          │
              RDS MySQL 8.4 (서비스별 스키마) · ElastiCache Valkey 8.2 (좌석 락 · Pub/Sub · 캐시)
              booking ─▶ SQS ─▶ Lambda (예매 알림)      Scheduler ─▶ Lambda (일별 집계)
```

| 영역 | 선택 |
| --- | --- |
| 백엔드 | Java 21 · Spring Boot · Spring Cloud Gateway (WebFlux) |
| 프런트 | React (Vite) → S3 + CloudFront |
| 인증 | Amazon Cognito (ADR-37). 서비스는 Gateway가 넣어 준 `X-User-Id`(Cognito `sub`) · `X-User-Role` 헤더만 신뢰 |
| 데이터 | RDS MySQL 8.4 (인스턴스 1개 + 서비스별 스키마·계정) · ElastiCache Valkey 8.2 |
| 메시지 | 내부 브로커 없음. 좌석 이벤트는 Valkey Pub/Sub → SSE, 예매 알림만 SQS |
| 서비스 간 통신 | REST (HTTP/1.1 + JSON), K8s DNS, RestClient + Resilience4j (연결 1s · 응답 3s) |
| 인프라 | EKS (t3.large 2대) · ALB · Terraform |
| 배포 | GitHub Actions → ECR → `cinema-gitops`(Kustomize) → ArgoCD · Argo Rollouts (booking·gateway 블루/그린) |
| 관측 | Prometheus · Grafana |

설계 근거는 노션 🧭 결정 기록(ADR)과 「MSA x Infra/DevOps 설계 판단 가이드」에 있습니다.

### 디렉터리

```
seatsac/
├── services/          gateway · catalog · booking · user · payment (Java), ai (Python)
├── libs/              공통 코드 — common-core(순수 Java) · common-web(Spring MVC) · common-test
├── build-logic/       Gradle 공통 빌드 설정 (convention plugin)
├── gradle/            libs.versions.toml (의존성 버전은 여기서만)
├── contracts/         OpenAPI · 이벤트 스키마 · 오류 형식  ← 서비스 간 약속
├── frontend/          React (Vite)
├── lambdas/           notification · daily-stats · cognito-presignup
├── infra/terraform/   AWS 전체 (VPC · EKS · RDS · Valkey · SQS · Cognito · CloudFront)
├── infra/compose/     로컬 MySQL · Valkey · ElasticMQ
├── tests/k6/          부하 시나리오
└── docs/              conventions · runbook
```

배포 매니페스트는 이 레포가 아니라 **`cinema-gitops`** 레포에 있습니다. 좌석 배치 원본·시드는 비공개 **`lotte-seed`** 레포에 있고 이 레포에는 절대 올리지 않습니다.

---

## 2. 초기 세팅

### 2-1. 설치

| 도구 | 버전 | 확인 |
| --- | --- | --- |
| JDK | 21 (Temurin 권장) | `java -version` |
| Docker Desktop | 최신 | `docker compose version` |
| Node.js | 20 LTS 이상 (frontend 담당) | `node -v` |
| Python | 3.12 (ai·lambdas) | `python3 --version` |
| AWS CLI v2 | 클러스터 확인용 | `aws --version` |
| kubectl | EKS 버전 ±1 | `kubectl version --client` |
| IDE | IntelliJ IDEA (Gradle 프로젝트로 열기) | — |

Gradle은 따로 설치하지 않습니다. 레포의 `./gradlew`를 씁니다.

### 2-2. 클론과 첫 빌드

```bash
git clone https://github.com/{조직}/seatsac.git
cd seatsac

./gradlew build          # 전체 빌드 + 테스트 (첫 실행은 의존성 다운로드로 몇 분)
```

IntelliJ: **Open → `seatsac` 폴더 → Gradle 프로젝트로 열기**. JDK는 Project Structure에서 21로 맞춥니다.

### 2-3. 로컬 의존 서비스

```bash
docker compose -f infra/compose/docker-compose.yml up -d
# MySQL 8.4 :3306 (스키마·계정 자동 생성) · Valkey 8.2 :6379 · ElasticMQ(SQS 대체) :9324

docker compose -f infra/compose/docker-compose.yml down       # 중지
docker compose -f infra/compose/docker-compose.yml down -v    # DB까지 초기화
```

### 2-4. 서비스 실행

```bash
./gradlew :services:catalog:bootRun     # local 프로필로 실행
```

| 서비스 | 로컬 포트 | 클러스터 안 주소 |
| --- | --- | --- |
| gateway | 8080 | `http://gateway:8080` |
| catalog | 8081 | `http://catalog:8080` |
| booking | 8082 | `http://booking:8080` |
| user | 8083 | `http://user:8080` |
| payment | 8084 | `http://payment:8080` |
| frontend (Vite) | 5173 | S3 (`/api`는 8080으로 프록시) |

```bash
cd frontend
cp .env.example .env.local      # Cognito 값은 김종식에게
npm install && npm run dev
```

### 2-5. AWS 접근 (클러스터 확인이 필요할 때)

개인 액세스 키는 만들지 않습니다. IAM Identity Center(SSO)로 로그인합니다. 초대 메일은 김종식이 보냅니다.

```bash
aws configure sso                     # 프로필 이름: cinema
aws sso login --profile cinema
aws eks update-kubeconfig --name cinema-staging-eks --region ap-northeast-2 --profile cinema
kubectl get pods -n cinema-staging    # 읽기·로그·port-forward 가능, 변경은 GitOps로만
```

> 클러스터는 **평일 낮에만** 켜져 있습니다. 밤·주말에 필요하면 Actions의 「cluster-schedule」을 수동 실행하거나 김종식에게 말해 주세요.

---

## 3. 브랜치 → push → PR

**main에 머지 = 데모 환경(staging) 자동 배포**입니다. main에 직접 push할 수 없고, 반드시 PR로 들어갑니다.

### 3-1. 브랜치 이름

```
{type}/{기능ID 또는 WBS}-{짧은-설명}
```

| type | 언제 | 예 |
| --- | --- | --- |
| `feat` | 기능 추가 | `feat/BKG-02-seat-hold` |
| `fix` | 버그 수정 | `fix/CAT-05-cache-key` |
| `refactor` | 동작 변화 없는 정리 | `refactor/booking-hold-service` |
| `test` | 테스트만 | `test/BKG-11-idempotency` |
| `docs` | 문서 | `docs/api-spec-booking` |
| `infra` | Terraform · CI · 배포 | `infra/3.1.1-vpc` |
| `chore` | 빌드 설정 · 의존성 | `chore/gradle-wrapper` |

브랜치는 **최대 3일**. 길어지면 작게 나눠 머지합니다.

### 3-2. 작업 흐름

```bash
# 1) 최신 main에서 브랜치 만들기
git switch main
git pull
git switch -c feat/BKG-02-seat-hold

# 2) 작업 + 커밋 (작게 자주)
git add services/booking
git commit -m "feat(booking): BKG-02 좌석 선점 Lua 스크립트"

# 3) push 전에 로컬 확인
./gradlew :services:booking:build

# 4) push
git push -u origin feat/BKG-02-seat-hold
```

### 3-3. 커밋 메시지

```
{type}({범위}): {기능ID} {무엇을}
```

예) `feat(booking): BKG-02 좌석 선점 Lua 스크립트` · `fix(gateway): GW-02 외부 X-User-Id 헤더 제거` · `infra(terraform): 3.1.1 VPC·서브넷`

### 3-4. PR 올리기

1. push 후 GitHub에 뜨는 **Compare & pull request** 클릭 (또는 `gh pr create`)
2. base: `main` ← compare: 내 브랜치
3. 제목은 커밋 메시지 형식으로: `feat(booking): BKG-02 좌석 선점`
4. 본문은 PR 템플릿을 채웁니다 — 변경 요약 · WBS ID·기능 ID · 테스트 방법 · 계약 변경 여부 · DB 마이그레이션 단계 · 배포 영향
5. 리뷰어는 CODEOWNERS가, 라벨(타입·영역·상태)과 담당자는 `pr-labeler` 워크플로가 자동으로 붙입니다. 우선순위는 본문에 `#이슈번호`를 적으면 그 이슈를 따릅니다
6. Actions 체크가 모두 ✅ 인지 확인

### 3-5. 리뷰와 머지

| 규칙 | 내용 |
| --- | --- |
| 승인 | 1명 (11/13 이후 2명) |
| 리뷰 응답 | 요청 후 24시간 이내 (주말 제외) |
| 크기 | 400줄 이하 권장 |
| 머지 방식 | **Squash and merge** |
| 머지하는 사람 | 승인 후 **작성자 본인** |
| 머지 후 | ArgoCD에서 Synced · Healthy 확인 (프런트는 배포 워크플로 완료 확인), 브랜치 삭제 |

리뷰 수정 요청을 받으면 같은 브랜치에 커밋을 추가해 push하면 PR에 자동으로 붙습니다.

### 3-6. main이 앞서 나갔을 때

```bash
git switch main && git pull
git switch feat/BKG-02-seat-hold
git rebase main            # 충돌 나면 고치고: git add . && git rebase --continue
git push --force-with-lease
```

`--force`가 아니라 **`--force-with-lease`**를 씁니다. 공유 브랜치(main)에는 절대 강제 push하지 않습니다.

---

## 4. GitHub Actions가 확인하는 것

| 워크플로 | 언제 | 하는 일 | 실패하면 |
| --- | --- | --- | --- |
| `pr-check` | 모든 PR | `./gradlew build` (컴파일 · 단위 테스트 · Testcontainers), gitleaks(비밀 유출 검사) | 머지 불가. 로그의 첫 에러부터 확인 |
| `infra-plan` | `infra/terraform/**` 바뀐 PR | `terraform fmt · validate · plan` 결과를 PR 코멘트로 | 김종식 확인 |
| `build-push` | main 머지 | 서비스 이미지 빌드 → ECR → `cinema-gitops` 태그 커밋 → ArgoCD 자동 배포 | 김종식 확인 |

**추가될 예정** — OpenAPI diff(계약 깨짐 차단) · Trivy(이미지 취약점) · ai pytest · `frontend-deploy`(S3) · `lambda-deploy` · `cluster-schedule`

> 현재 `build-push`와 `infra-plan`은 AWS OIDC 역할(WBS 3.1.10)이 생기기 전이라 실패하거나 꺼져 있을 수 있습니다. PR에서는 **`pr-check`만 통과하면 됩니다.**

### 실패했을 때

1. PR 하단 **Details** → 빨간 단계 열기
2. 로컬에서 같은 명령 재현: `./gradlew build` 또는 `./gradlew :services:{서비스}:build`
3. gitleaks 실패 = 코드에 키·비밀번호가 들어감 → 값을 지우고 **비밀은 이미 유출된 것으로 보고 김종식에게 알림**(커밋 이력에 남음)

---

## 5. 프로젝트하면서 알아야 할 것

### 5-1. 작업 관리는 노션

- GitHub Issues는 쓰지 않습니다. 작업·진척은 **노션 📋 작업(WBS)**, 매일 **☀️ 데일리 스크럼**에 `[3.2.2] 좌석 락 API — 30% → 60%` 형식으로
- 설계 결정은 **🧭 결정 기록(ADR)** 에 남기고 PR 본문에 ADR 번호를 적습니다

### 5-2. 서비스 간 약속 (깨면 분산 모놀리스가 됩니다)

| 규칙 | 내용 |
| --- | --- |
| 계약 먼저 | API를 바꾸려면 `contracts/openapi/`부터 PR. 구현은 그 다음 |
| 하위 호환 | 필드 **추가만**. 삭제 · 이름 변경 · 타입 변경은 새 엔드포인트로 |
| 직접 조인 금지 | 다른 서비스 DB를 읽지 않습니다. 필요하면 그 서비스의 `/internal/**` API를 호출 |
| 외부 경로 | 화면은 `/api/...`로 호출. Gateway가 `/api`를 떼고 서비스로 보냄 |
| 내부 경로 | `/internal/**`는 서비스끼리만. Gateway가 라우팅하지 않음 |
| 오류 응답 | `{ code, message, traceId, details? }` — `common-core`의 `ErrorCode` 사용 |
| 사용자 | 컨트롤러 인자 `@CurrentUser CurrentUser user` (헤더에서 자동). 직접 JWT를 파싱하지 않습니다 |
| 추적 | 모든 로그·오류·SQS 메시지에 `traceId`. 내부 호출은 `InternalRestClientFactory`로 (자동 전달) |
| 시간 | DB는 UTC, API 응답은 `+09:00`, 영업일은 서울 기준 (`Times` 사용) |

자세한 경로·필드는 노션의 **API 명세서**를 봅니다.

### 5-3. 공통 코드 (`libs/`)

- **두 개 이상의 서비스가 같은 방식으로 해야 하는 것만** 넣습니다 (오류 형식 · 헤더 · traceId · 로그 · 시간)
- 도메인 엔티티 · 다른 서비스 DTO · Valkey 키 · 비즈니스 로직은 넣지 않습니다
- 변경은 **추가만**, 승인 2명. `libs/`를 바꾼 PR은 모든 Java 서비스 테스트가 돌아갑니다
- gateway는 WebFlux라 `common-web`을 못 씁니다 (`common-core`만)

### 5-4. DB 마이그레이션 (블루/그린 때문에)

배포 중에는 **이전 버전과 새 버전이 같은 DB를 동시에** 씁니다.

| 단계 | 예 | 같은 PR에 넣어도 되나 |
| --- | --- | --- |
| 확장 | 컬럼 추가 (NULL 허용 또는 기본값) | ✅ |
| 전환 | 새 컬럼 읽기·쓰기 코드 배포 | ✅ |
| 정리 | 옛 컬럼 삭제 · 이름 변경 · NOT NULL | ❌ **다음 배포로** |

- 파일: `services/{svc}/src/main/resources/db/migration/V{yyyyMMddHHmm}__{설명}.sql`
- 이미 머지된 마이그레이션 파일은 **절대 수정하지 않습니다** (새 버전 파일 추가)
- 클러스터에서는 ArgoCD PreSync Job이 `{svc}_migrator` 계정으로 실행합니다

### 5-5. 비밀 값

- **Git에 비밀 0개.** 키 · 비밀번호 · 토큰 · `.env`를 커밋하지 않습니다 (gitleaks가 막음)
- 로컬 비밀번호는 `local` 고정(Compose용). 클러스터 값은 Terraform이 만든 K8s Secret이 환경변수로 들어갑니다
- 프런트 `VITE_*` 값은 **브라우저에 그대로 노출**됩니다. 비밀을 넣지 않습니다

### 5-6. 롯데시네마 데이터 (약관 준수)

- 원본 응답 · fixture · 좌석 배치 · 상영 일정 시드는 **이 레포에 올리지 않습니다** (`lotte-seed` 비공개 레포에만)
- 롯데시네마 실제 예매 API 호출 금지, 포스터 이미지 사용 금지
- 배치도 화면에 출처(롯데시네마)와 수집 시각을 표시합니다

### 5-7. 클러스터와 배포

- **클러스터를 직접 고치지 않습니다** (`kubectl edit/apply` 금지). ArgoCD가 Git 상태로 되돌립니다. 바꿀 게 있으면 `cinema-gitops`에 PR
- 내 PR이 머지되면 몇 분 안에 staging에 반영됩니다. 깨진 걸 발견하면 **revert PR**이 가장 빠른 롤백입니다
- 이미지를 직접 빌드해 ECR에 올리지 않습니다 (Mac ARM 이미지는 클러스터에서 안 뜸). 반드시 CI로

### 5-8. 일정과 동결

| 날짜 | 내용 |
| --- | --- |
| 10/20 | M1 요구사항·설계 |
| 10/27 | M2 인프라 기반 |
| 10/29 · 10/30 | M3 핵심 API · M4 무중단 배포 시연 |
| 11/6 | M5 기능 개발 완료 · 태그 `rc1` |
| 11/13 | M6 통합·부하 테스트 · 태그 `rc2` · **이후 `fix/`만, 승인 2명** |
| 11/16 ~ 20 | **데모 주간 — 승인된 fix 외 머지 금지** |
| 11/19 · 11/20 | 태그 `v1.0.0` · 최종 발표 |

---

## 6. 자주 막히는 곳

| 증상 | 해결 |
| --- | --- |
| `./gradlew: No such file or directory` | wrapper가 없음. `gradle wrapper --gradle-version 8.14.3` 후 `gradlew` · `gradle/wrapper/*` 커밋 |
| `./gradlew: Permission denied` | `git update-index --chmod=+x gradlew` 후 커밋 |
| Testcontainers 실패 | Docker Desktop이 켜져 있는지 확인 |
| `Communications link failure` (MySQL) | `docker compose ... up -d` 했는지, 3306 포트를 다른 MySQL이 쓰고 있지 않은지 |
| 로컬에서 401 | Cognito 토큰 만료 또는 `.env.local` 값 누락. 백엔드만 테스트할 때는 서비스 포트(8081~)로 직접 호출하고 `X-User-Id` 헤더를 넣기 |
| PR 체크가 노란색에서 안 끝남 | Actions 탭에서 대기 중인지 확인 (무료 러너 대기) |
| 충돌 해결이 어려움 | 혼자 오래 붙잡지 말고 스크럼 블로커로 올리기 |

---

<sub>문서 위치: 레포 루트 `README.md` · 바뀐 규칙은 노션 회의록·ADR이 원본이고, 이 문서는 그 요약입니다.</sub>
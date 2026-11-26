# [런북] 새 AWS 계정에서 처음부터 다시 만들기 (ADR-125)

| 항목 | 내용 |
| --- | --- |
| 목적 | 지금 계정을 버리고(파기·만료·비용) **다른 AWS 계정에서 같은 staging을 다시 만들어 이어서** 쓴다. 지금까지 재생성·구축에서 막혔던 것과 해결법을 한곳에 모았다 |
| 언제 | 계정 이전(ADR-125), 같은 계정 전체 재생성 리허설(OPS-14) |
| 담당 / 권한 | 김종식 (백업: 남궁일) / 새 계정 IAM 사용자(1단계만) → `cinema-admin` 역할(MFA) |
| 기준 | `docs/runbooks/terraform-infra.md`(절 번호는 거기를 가리킨다)·`terraform-bootstrap.md`. 이 문서는 **순서·바꿀 값·막히는 곳**만 모았고, 명령의 자세한 설명은 원래 절에 있다 |
| 예상 소요 | 하루(계정·도메인 이전 대기 제외). EKS 15~20분 · RDS 7개 동시 15~20분 · ArgoCD 첫 sync 약 20분 · 시드 10분 · 도메인 이전 수 시간~수일(7장) |
| 마지막 검증 | ⬜ YYYY-MM-DD · 실행자 · 총 소요 ⬜ |

> 원칙은 그대로다: **콘솔에서 리소스를 만들지 않는다**(예외: 도메인 등록·이전, 7장). apply는 `cinema-admin`으로 로컬에서만, plan에 예상 밖 `destroy`·`replace`가 하나라도 있으면 멈춘다. 비밀 값(키·비밀번호·tfstate 내용)·계정 ID·이메일을 이 문서·PR·커밋에 쓰지 않는다.

---

## 0. 먼저 알아 둘 것

| 항목 | 내용 |
| --- | --- |
| **데이터는 옮기지 않는다** | 새 계정의 RDS는 빈 스키마에서 Flyway가 다시 만든다. catalog는 시드(`lotte-seed`)를 다시 넣는다. **예매·결제·쿠폰·user 프로필·AI 수요 기록은 사라진다**. 남겨야 하면 이전 전에 옛 계정에서 `infra/scripts/db-migrate.sh`로 덤프하는 별도 작업이 필요하다(계정 간 절차는 없다 — 덤프 파일을 노트북에 두지 않는 규칙 때문에 새로 설계해야 한다) |
| **Cognito 사용자도 사라진다** | 새 사용자 풀이라 팀원·시연 계정은 다시 가입하고, 새 `sub`로 VIP·ADMIN 그룹을 다시 지정한다(3-13) |
| **레포·gitops는 두 계정이 같이 본다** | tfvars·gitops 매니페스트는 하나다. 새 계정에 맞게 바꾸는 순간 옛 계정에 apply하면 어긋난다 → **이전을 시작하면 옛 계정에는 apply·gitops 변경을 하지 않는다**(1장에서 옛 계정을 안전한 상태로 먼저 돌려놓는다) |
| GitHub 쪽은 그대로 | 레포·ruleset·GitHub App `seatsac-build-push`(gitops 쓰기)·`GITOPS_APP_ID`·`GITOPS_APP_PRIVATE_KEY`는 계정과 무관하다. 바뀌는 것은 AWS 역할 ARN·버킷·배포 ID가 들어간 Secrets·Variables(2장) |
| 이름은 그대로 | 리소스 이름은 `cinema-staging-*`로 계정과 무관하다(버킷만 `-{계정ID}` 접미사). gitops에는 계정 ID가 없어 이미지 태그·설정을 그대로 쓴다 |

---

## 1. 이전 전 — 옛 계정 정리·동결 (옛 계정, `cinema-admin`)

1. 스크럼 공유: 「X시부터 옛 계정 동결 — 머지는 문서만」
2. 시연용 상태를 되돌린다: ops-api로 멈춘 서비스 「가동」(`infra/scripts/msa-check.sh stopped`), cordon한 노드 uncordon
3. **HTTPS 원본을 `on` → `prepare`로 되돌린다**(terraform-infra 7-8 「되돌리기」). gitops `base/gateway/ingress.yaml`의 443 리스너·`tls`가 새 계정 첫 sync를 막기 때문이다(새 계정에는 `origin.<도메인>` 인증서가 아직 없다 → `no certificate found for host` → **ALB가 아예 안 생긴다**). 순서: 옛 계정 `api_origin_https = "prepare"` apply → gitops에서 443·`tls` 빼는 PR 머지 → (옛 계정 `off`는 선택 — 옛 계정을 곧 지우면 생략)
4. 남길 것만 적어 둔다(비밀 값 제외, 레포 밖): 현재 gitops 이미지 태그(`overlays/staging/*/image-tag` — 이미 Git에 있다), 포트원 콘솔 설정(웹훅 URL·허용 도메인), Anthropic 지출 한도, 도메인 상태(7장)
5. 필요하면 데이터 덤프(0장 첫 행 — 기본은 하지 않는다)
6. 옛 계정 삭제는 **새 계정 확인(5장)과 도메인 이전(7장)이 끝난 뒤**(6장)

---

## 2. 계정마다 바뀌는 값 (새 계정에서 바꿀 곳)

> 레포에 커밋하는 값은 PR로, 로컬 파일(커밋 안 함)은 각자 만든다. 계정 ID·영역 ID는 PR 본문에도 가린다(`1234********`).

| 위치 | 지금 | 새 계정에서 |
| --- | --- | --- |
| `infra/terraform/{global,envs/staging,envs/staging-k8s}/backend.hcl` (로컬) | 옛 `cinema-tfstate-{계정ID}` | bootstrap 출력 `tfstate_bucket`으로 새로 만든다(`backend.hcl.example`) |
| `global/global.tfvars` `admin_users`·`developer_users` | 옛 계정 IAM 사용자 이름 | 새 계정 IAM 사용자 이름과 **정확히** 같게(아니면 `Invalid principal`). `github_owner(_id)`·`github_repo(_id)`는 GitHub 값이라 그대로 |
| `global/global.tfvars` `frontend_distribution_ids` | 옛 배포 ID | 새 staging 출력 `cloudfront_distribution_id`(3-6) |
| `global/budget.auto.tfvars` (로컬) | `budget_emails` | 같은 내용으로 다시 만들고, SNS 구독 메일의 **Confirm**을 누른다 |
| `envs/staging/eks_access.auto.tfvars` (로컬) · GitHub Secret `EKS_PUBLIC_ACCESS_CIDRS` | 관리자 공인 IP | 다시 만든다(IP가 같으면 값도 같다, 4-6) |
| `envs/staging/staging.tfvars` `custom_domain` | `"seatsac.com"` | 처음에는 **`""`** — 새 계정에 호스팅 영역이 생기기 전에는 `no matching Route 53 Hosted Zone`. 도메인은 7장에서 |
| `envs/staging/staging.tfvars` `api_origin_https` | `"on"` | 처음에는 **`"off"`** — `prepare`·`on`은 사용자 도메인·ALB가 있어야 한다(7장 끝에서 다시) |
| `envs/staging/staging.tfvars` `cognito_group_members` | 옛 풀의 `sub` | 처음에는 **`{}`** — 새 풀에 없는 sub는 `UserNotFoundException`. 가입 뒤 새 sub로 PR(3-13) |
| `envs/staging/staging.tfvars` `cluster_schedule_demo_start` (기본 `2026-11-16`) | 지난 날짜 | 지난 날짜면 #126 규칙으로 EventBridge **끄기 일정이 만들어지지 않는다**(밤에 노드가 안 꺼짐 — GitHub 백업·수동 `down`만). 새 데모 기간이 있으면 그 날짜를, 없으면 `cluster_keep_on`·일정 의미를 확인하고 정한다 |
| `envs/staging/staging.tfvars` `cognito_domain_prefix` (기본 `cinema-staging`) | — | 옛 계정의 사용자 풀이 아직 있으면 `InvalidParameterException … Domain already exists`(접두사는 리전 전체에서 유일). 옛 풀을 먼저 지우거나 새 접두사(예: `cinema-staging-2`) + GitHub Variable `VITE_COGNITO_DOMAIN`을 같이 |
| `envs/staging-k8s/staging-k8s.tfvars` `cloudfront_distribution_id` | 옛 배포 ID | 새 값(3-6) — 안 바꾸면 `data.aws_cloudfront_distribution`에서 plan 실패(11/25 재생성 때 실제로 겪음, #210) |
| `envs/staging-k8s/staging-k8s.tfvars` 기능 플래그 | `cognito_enabled`·`ai_enabled`·`coupon_enabled`·`karpenter_enabled`·`ops_api_enabled`(#223) = true | 처음 apply 때 이미지·앞 단계가 없어 실패하는 것이 있다 → 3-8의 표대로 **처음에는 false로 두고 하나씩** 켠다 |
| GitHub Secrets `AWS_ECR_PUSH_ROLE_ARN`·`AWS_TERRAFORM_PLAN_ROLE_ARN`·`AWS_FRONTEND_DEPLOY_ROLE_ARN`·`AWS_CLUSTER_SCHEDULE_ROLE_ARN` | 옛 계정 ARN | 새 global 출력(2-4) |
| GitHub Variables `FRONTEND_BUCKET`·`CLOUDFRONT_DISTRIBUTION_ID`·`VITE_COGNITO_AUTHORITY`·`VITE_COGNITO_CLIENT_ID`·`VITE_COGNITO_DOMAIN` | 옛 값 | 새 staging 출력 `frontend_github_variables`(7-3). `*_BUILD_PUSH_ENABLED`·`GITOPS_APP_ID`는 그대로(true인지 확인) |
| Terraform 비밀 입력(`TF_VAR_*`, state에만 저장) | 옛 state | 새 state에는 없다 → 처음 apply 때 다시 넣는다: `anthropic_api_key`(6-G-1, 없으면 precondition에서 멈춤) · `portone_api_secret`·`portone_webhook_secret`(6-I). `read -rs`로 받고 `-out` 플랜 파일을 남기지 않는다 |
| 포트원 콘솔 | 웹훅 URL = 옛 CloudFront 기본 도메인 | **새 CloudFront 기본 도메인**(또는 도메인 이전 뒤 사용자 도메인)의 `/api/webhooks/portone`으로 바꾼다. 허용 도메인도 |
| gitops `base/gateway/ingress.yaml` | 443 리스너·`tls.hosts: origin.<도메인>`·`/ops` 경로 | 1장 3번대로 443·`tls`를 뺀 상태로 시작. `/ops`는 ops-api Service가 있어야 Ingress가 조정된다 → ops-api 이미지·Application(3-7·3-8) 뒤에 ALB가 생긴다 |
| gitops `analysis/booking-prepromotion.yaml` `SMOKE_SCREENING_ID` | 시드의 실제 회차 | 시드 ID는 고정이라 대개 그대로 — 6-D-0으로 200인지 확인. 틀리면 **모든 booking 배포가 abort** |
| `lotte-seed` `load/run.sh` | `RDS_HOST` | **`cinema-staging-catalog-db`**의 주소(옛 공용 `cinema-staging-mysql`이 아니다). `load/extend-job.yaml`은 아직 `DB_HOST` 키를 읽는다 — 쓰려면 `DB_HOST_CATALOG`로 고친다 |

---

## 3. 단계별 재구축 (새 계정)

**모든 단계 공통**

```bash
# Terraform에 MFA 프로필을 직접 주지 않는다(`AssumeRoleTokenProvider session option not set`) — 자격 증명을 꺼내 넘긴다
eval "$(aws configure export-credentials --profile cinema-admin --format env)" && unset AWS_PROFILE
aws sts get-caller-identity --query Arn --output text     # assumed-role/cinema-admin/… 이고 새 계정인지
# 8시간 뒤 ExpiredToken·kubernetes provider Unauthorized → 위 두 줄 다시
git switch main && git pull && git log -1 --oneline        # 오래된 main·다른 브랜치에서 plan하면 엉뚱한 destroy·add가 보인다
```

| # | 단계 | 절(terraform-infra) | 반드시 먼저 / 어기면 |
| --- | --- | --- | --- |
| 3-0 | 새 계정 사전 확인 — 도구, SCP(`simulate-principal-policy`, `run-instances --dry-run` t3.large·xlarge), 리전 `ap-northeast-2` | 0장 | `explicit deny … service control policy`면 학원에 문의 — 이후 단계가 전부 막힌다 |
| 3-1 | **bootstrap** — state 버킷 | 1장 · `terraform-bootstrap.md` | **IAM 사용자 키**로(역할이 아직 없다). `AWS_*` 환경변수 unset. 출력 `tfstate_bucket`으로 `backend.hcl` 3개(2장 첫 행) |
| 3-2 | **global** — 팀 역할 5·GitHub OIDC·CI 역할·SNS `cinema-alerts`·Budgets·cluster-schedule 역할·SchedulerRead | 2장 · 5-1 · 8-1-1 | 2-2: 계정에 GitHub OIDC 공급자가 이미 있으면 `409 EntityAlreadyExists` → import 또는 data. `budget.auto.tfvars` 먼저. 끝나면 **팀원 각자 프로필 설정(2-5)** · GitHub Secrets 4개(2장 표) |
| 3-3 | **staging 전체 1차** — 네트워크·EKS·RDS 7·Valkey 2·ECR·SQS/SNS·Lambda 3·Cognito·CloudFront·S3·Pod Identity·Karpenter 권한·ops-api ECR·리포트(#221) | 3·4·5·6-A·6-B·6-B-6·7-1·7-6-1·8-E 1)·6-J·6-K 1)·6-L 1)·6-M | 한 루트 모듈이라 한 번에 된다. 반드시 **`cinema-admin`으로**(다른 주체가 EKS를 만들면 kubectl `Unauthorized`). `terraform init -upgrade -backend-config=backend.hcl`(EKS 모듈 provider 요구). `custom_domain = ""`·`api_origin_https = "off"`·`cognito_group_members = {}`(2장). 새 계정이라 5-6의 NAT 두 번 나눈 apply는 필요 없다 |
| 3-4 | 확인 — kubectl·노드 | 4-2·4-4 | `aws eks update-kubeconfig --name cinema-staging-eks --profile cinema-admin --alias cinema-admin --user-alias cinema-admin`. `i/o timeout`이면 공인 IP(4-6). 노드 Ready는 NAT가 있어야 |
| 3-5 | SNS 구독 확인 메일 **Confirm** | 5-3 | 안 누르면 경보 메일이 오지 않는다(`PendingConfirmation`) |
| 3-6 | **새 CloudFront 배포 ID 반영** → global apply | 7-2 · #210 | `terraform -chdir=infra/terraform/envs/staging output -raw cloudfront_distribution_id` → `global.tfvars` `frontend_distribution_ids`·`staging-k8s.tfvars` `cloudfront_distribution_id` 두 곳 PR → global apply. 안 하면 staging-k8s plan 실패 · frontend-deploy 무효화 거부 |
| 3-7 | GitHub Variables 등록 → **`build-push` 수동 실행**(`services=all`, main) | 6-A-2~4 · 7-3 | 새 ECR은 비어 있다 — gitops의 기존 태그로는 `ImagePullBackOff`. 실행 전 `AI_/COUPON_/OPS_BUILD_PUSH_ENABLED=true` 확인. `main에서만 실행한다` → main에서. booking·gateway가 같이 바뀌면 booking만 — 한 번 더 실행 |
| 3-8 | **staging-k8s** — namespace·RBAC·LB Controller·db-bootstrap Job(인스턴스마다)·Secret·`rds-ca-bundle`·`cinema-infra-endpoints`·ArgoCD·root·서비스 Application·Karpenter Helm·loki ConfigMap·ESO 값·report-export ConfigMap | 4-3 · 8-A 2) · 8-E 2) · 6-C-2 · 6-G-1 3 · 6-I · 6-K 2) · 6-J-1 · 8-F | **노드가 켜져 있어야 한다**(꺼져 있으면 Job `DeadlineExceeded`, Helm `context deadline exceeded` — `cluster-scale.sh up` 뒤 다시 apply). 차트 버전이 실제로 있는지(`helm search repo … --versions`). `TF_VAR_anthropic_api_key`·포트원 `TF_VAR_*` 넣고 `-out` 없이. 플래그는 아래 표 순서 |
| 3-9 | ArgoCD 첫 sync 기다리기 | 6-C-2 | wave 순서: project(-20) → rollouts·kps·ESO(-10) → ClusterSecretStore(-8) → Karpenter NodePool(-7) → common(-5) → observability(-4) → loki(5) → alloy(6) → 서비스. 앞 wave가 Healthy가 아니면 다음이 멈춘다. 서비스 앱 `no matches for kind "Rollout"`은 CRD 전이라 재시도(최대 약 20분) |
| 3-10 | gateway Ingress → ALB → **staging 2차 apply**(CloudFront `/api` 원본) | 7-4 · 7-6 | Cognito(`cognito_enabled`)와 gateway 이미지, ops-api Service(`/ops` 경로)가 먼저. 출력 `api_origin_domain_name`이 계속 `""`면 ALB 태그를 못 찾은 것(7-4) |
| 3-11 | `frontend-deploy` 수동 실행 → 화면 | 7-5 | Variables가 맞아야 한다. `/` 403 XML이면 버킷이 비어 있다 |
| 3-12 | **시드** — `lotte-seed/load/run.sh` | 8-C · 8-E 「catalog 시드 재적재」 | 8-B PASS 뒤, 노드 켠 상태. `RDS_HOST`는 catalog 인스턴스(2장 끝 행). 검증 PASS(극장 132·상영관 39·좌석 6,767·회차 8,360·영화 22). user·payment·booking·coupon은 덤프 없이 빈 스키마(Flyway) |
| 3-13 | 사용자 가입 → 새 `sub` → `cognito_group_members` PR → staging apply → **다시 로그인** | 7-6-3·7-6-5 | 이메일이 아니라 sub. 그룹은 토큰 재발급 뒤 반영 |
| 3-14 | 분석 회차·블루/그린 확인 | 6-D-0 · 6-D-2 | `SMOKE_SCREENING_ID` 200 확인 |
| 3-15 | 앱 플래그를 **받는 쪽부터** 켠다 | 6-B-3·6-B-5·6-B-6·6-D-6·6-I·6-L·8-G | 아래 「켜는 순서」 |
| 3-16 | 가동 정책 — EventBridge 일정·cluster-schedule 백업·Budgets | 8-1 | 2장의 `cluster_schedule_demo_start` 행 |
| 3-17 | **도메인·HTTPS 원본** | 7장(이 문서) · 7-7 · 7-8 | 새 계정이 기본 도메인으로 다 동작한 뒤 |

**3-8 staging-k8s 플래그 켜는 순서** (`staging-k8s.tfvars` — 한 번에 다 true면 이미지·앞 단계가 없어 실패한다)

| 플래그 | 켜기 전에 있어야 하는 것 | 없으면 |
| --- | --- | --- |
| (기본 apply) | 노드 켜짐 · staging 1차(3-3) | Job·Helm 시간 초과 |
| `cognito_enabled` | staging의 Cognito(3-3) | gateway 기동 실패(issuer 없음) |
| `ai_enabled` | ECR `cinema/ai` 이미지(3-7) · `TF_VAR_anthropic_api_key` | precondition에서 멈춤 · `ImagePullBackOff`. `ng-ai` 0대면 `cinema-ai` Degraded가 정상 |
| `coupon_enabled` | coupon 이미지 · gitops `base/coupon` | `ImagePullBackOff` · `ComparisonError … no such file` |
| `karpenter_enabled` | staging Karpenter 권한·큐(3-3) | 컨트롤러 `AccessDenied` |
| `ops_api_enabled` | ops-api 이미지 · gitops `base/ops-api` · **고정 replicas를 바꾸는 gitops PR이 이미 머지돼 있을 것** | 이 apply부터 replicas 변경이 자동 sync로 반영되지 않는다(6-L 3)·4-1) |

**3-15 앱 플래그 켜는 순서** (gitops PR 하나씩, 받는 쪽 먼저 — 이미 main에 켜진 값이면 새 클러스터는 처음부터 켜진 채 뜬다. 그래서 **새 클러스터에서는 아래 「먼저」 쪽이 Healthy인지만 확인**하면 된다)

| 먼저 | 그다음 | 어기면 |
| --- | --- | --- |
| 알림 Lambda 연결(6-B-5) | booking `APP_OUTBOX_RELAY_ENABLED` | 10회 재시도 뒤 outbox `FAILED` |
| coupon·payment 소비자 | booking `APP_CHECKOUT_SAGA_ENABLED`·쿠폰 어댑터 `sqs` | 명령이 쌓이고 `…-oldest` 경보 |
| user 소비자(gitops #61) | booking SNS 발행 → user `APP_BOOKING_HISTORY_MODE=local`(#63) | local을 먼저 켜면 첫 채우기 뒤 확정 예매가 빠진다 |
| ai·user 소비자 | catalog `APP_OUTBOX_RELAY_ENABLED` | `ai-catalog-events` 쌓임(ng-ai 0대면 정상) |
| 포트원 Secret(`TF_VAR_portone_*`) | payment `APP_PORTONE_ENABLED` | verify 503 `PORTONE_UNAVAILABLE`, 웹훅 401 |
| ops-api Service | gateway Ingress `/ops` | Ingress 전체 조정 실패 |
| ACM `origin.<도메인>` ISSUED(7장) | gitops 443 리스너·`tls` | `no certificate found for host` |

**Pod Identity 연결은 파드보다 먼저다** — booking·Grafana·Loki·coupon·ESO·Karpenter·user·report-export 모두. 연결보다 먼저 뜬 파드는 `AccessDenied`·`Unable to load credentials`·`NoCredentialProviders`(env에 `AWS_CONTAINER_*`가 없다) → ArgoCD 화면에서 그 앱 **Restart**.

---

## 4. 알려진 문제와 해결 (지금까지 겪은 것)

> 「출처」는 terraform-infra 절 번호 또는 PR. 여러 절에 나온 것은 한 줄로 합쳤다.

### 4-1. 공통·Terraform

| 증상 | 원인 | 해결 | 출처 |
| --- | --- | --- | --- |
| `assume role with MFA enabled, but AssumeRoleTokenProvider session option not set` | Terraform이 MFA 프로필을 직접 씀 | 3장 공통 블록(export-credentials → `unset AWS_PROFILE`) | 13장, 14장 10/21 |
| `ExpiredToken` / kubernetes provider `Unauthorized` | 넘긴 임시 자격 증명 만료(8시간) | 다시 export | 13장, 14장 10/24 |
| `init`이 `bucket - The name of the S3 bucket`을 물음 | `backend.hcl` 없음(커밋 안 하는 파일) | 1장 명령으로 다시 | 13장, 14장 10/20 |
| `Error acquiring the state lock` | 잠금이 남음 | 다른 사람이 apply 중이 아닌지 확인 → `terraform force-unlock <ID>` | 12장 |
| `EntityAlreadyExists` / `already exists` | AWS에는 있고 state에 없음 | 우리 것이면 import, 학원·다른 팀 것이면 data | 12장 |
| `Resource already managed by Terraform` | 다른 state가 관리 중 | 원래 state에서 `state rm` → import. **`state rm` 없이 코드만 지우면 실제 리소스가 삭제된다** | 12·13장 |
| plan에 엉뚱한 destroy·add(`infra_endpoints` destroy, `alerts_email[0]` add, ai 9개 destroy) | 오래된 main·다른 브랜치에서 plan | `git pull && git log -1` 뒤 최신 main에서 | 14장 10/24·10/26, 6-G |
| 노드그룹 2개 change(`release_version`) | 모듈이 최신 AMI를 조회 | 노드 0대일 때 apply | 14장 10/27 |
| `-/+ replace`가 보임 | 교체가 필요한 값 변경 | 멈추고 원인 확인 | 13장 |
| `explicit deny … service control policy` | 학원 SCP | 학원 문의 | 13장 |
| `Inconsistent conditional result types`(`ops_api_enabled = true`) | 조건식 두 결과의 모양이 다름 | `for … if`로(반영됨) | #214, #217 |
| RDS 파라미터 그룹 `time_zone`이 plan마다 change 1 | AWS가 `apply_method`를 `pending-reboot`로 돌려줌 | 코드에서 pending-reboot(반영됨) | #179 |
| `LimitExceeded: Cannot exceed quota for PolicySize: 6144`(Karpenter) | 관리형 정책 상한 | `enable_inline_policy = true`(반영됨) | #205 |
| `Unsupported argument "enable_inline_policy"` | 모듈이 옛 버전 | `terraform init -upgrade` | #205 |

### 4-2. global·접근·CI

| 증상 | 원인 | 해결 | 출처 |
| --- | --- | --- | --- |
| `CreateOpenIDConnectProvider … 409 EntityAlreadyExists` | 계정에 GitHub OIDC 공급자가 이미 있음 | 2-2 표(import 또는 data) | 13장, 14장 10/21 |
| `Invalid principal` | `admin_users`·`developer_users`가 IAM 사용자 이름과 다름 | 정확한 이름으로 | 2-1 |
| `Reference to undeclared input variable "budget_emails"` | 옛 브랜치 | `origin/main` 합치기 | 13장 |
| 팀원 `AccessDenied … sts:AssumeRole` | 사용자 목록 누락·MFA 없음·대소문자 | tfvars·`mfa_serial` 확인 | 13장 |
| CI `Not authorized to perform sts:AssumeRoleWithWebIdentity` | owner·repo 대소문자, main 아닌 ref, Secret 미등록(**새 계정 ARN으로 안 바꿈**) | GitHub 표기·main·Secrets | 13장, 7-5 |
| CI `Could not load credentials from any providers` | Secret 없음, `id-token: write` 없음 | 등록·권한 | 13장 |
| 필수 검사 `image`·`python`이 ruleset 목록에 없음 | 한 번도 실행 안 됨 | PR을 한 번 돌린 뒤 추가 | 13장 |

### 4-3. 네트워크·EKS·노드

| 증상 | 원인 | 해결 | 출처 |
| --- | --- | --- | --- |
| kubectl `Unauthorized` | `cinema-admin`이 아닌 주체로 생성, 다른 kubeconfig 프로필 | `update-kubeconfig --profile cinema-admin`, Access Entry | 13장 |
| `dial tcp …:443: i/o timeout` | 공인 IP가 `eks_public_access_cidrs`에 없음 | tfvars·Secret 두 곳 → apply | 4-6 |
| `exec plugin` 오류·멈춤 | get-token이 MFA를 묻지 못함 | `aws sts get-caller-identity --profile cinema-admin`으로 세션 먼저 | 13장 |
| 컨텍스트 두 개가 같은 역할 | `--user-alias` 없이 update-kubeconfig | 지우고 `--alias`·`--user-alias`로 | 13장 |
| `Error: Kubernetes cluster unreachable` | 클러스터·노드 없음, 프로필 없음 | 프로필·클러스터 확인 | 13장 |
| LBC `AccessDenied`·`no EC2 IMDS role found` | Pod Identity 없음·SA 이름·agent 없음 | 연결 확인 → 재시작 | 13장 |
| NAT를 지운 뒤 EIP가 남음 | Terraform 밖 EIP | `aws ec2 describe-addresses` | 13장 |
| `UpdateNodegroupConfig` 거부·InService 0·DEGRADED(`AsgInstanceLaunchFailures`·`InsufficientFreeAddresses`) | 이름 불일치·용량·SCP | 8-1-4 | 8-1-3·4 |
| Karpenter `SubnetsNotFound`·`SecurityGroupsNotFound`·NodeClaim Registered 없음·`exceeded limits` | 태그(`karpenter.sh/discovery`) 없음·role 이름 불일치·상한 16 | 6-K 표 | 6-K |
| Karpenter 되돌릴 때 NodeClaim finalizer가 남음 | 컨트롤러를 먼저 지움 | gitops NodePool 먼저 빼고 컨트롤러 나중 | 6-K |

### 4-4. 데이터(RDS·Valkey·db-bootstrap·시드)

| 증상 | 원인 | 해결 | 출처 |
| --- | --- | --- | --- |
| staging plan `no matching SNS topic found` | global이 먼저가 아님 | global apply | 5-1, 13장 |
| MySQL `ERROR 2003`, Valkey 시간 초과 | SG 규칙 누락, `--tls` 누락 | `security_groups.tf`, `--tls` | 13장 |
| `automatic failover … requires at least 2 nodes` | 복제본 추가와 failover 켜기 순서 | 다시 apply, 안 되면 두 번에 | 5-6 |
| Valkey 복제 그룹 plan이 replace | AZ 목록 변경 | apply하지 않는다 | 5-6 |
| Valkey 장애 조치 뒤 booking 5xx | 풀이 옛 primary에 붙음 | booking Restart | 5-6 |
| `job: cinema-ops/db-bootstrap-… is not in complete state`·`DeadlineExceeded` | 노드 0대 | `cluster-scale.sh up` → 다시 apply(tainted 교체) | 13장, 8-D, 14장 10/24 |
| fetch-master `AccessDeniedException … GetSecretValue` | staging apply 전(Pod Identity·새 마스터 Secret) | staging 먼저 | 8-D, 8-E |
| `Unable to locate credentials`(db-bootstrap) | pod-identity-agent 없음·기동 직후 | `-replace=kubernetes_job_v1.db_bootstrap…` | 8-D |
| `FAIL: 마스터 Secret 형식` | 비밀번호에 `\uXXXX` 이스케이프 | `rotate-master-user-password` | 8-D |
| `ERROR 2026 … certificate verify failed` | CA 번들 불일치, 호스트가 IP·별칭 | `rds-ca-bundle` 갱신, 엔드포인트 그대로 | 8-D, 8-E |
| `ERROR 1045`(master) | 마스터 비밀번호 교체 중 | 1~2분 뒤 | 8-D |
| staging-k8s plan `DBInstanceNotFound` | 인스턴스 생성 중 | available 뒤 | 8-E |
| ai `GRANT … TO 'ai_query'` → `Access denied` | ai db-bootstrap 전 | ai Job PASS 뒤 | 8-E |
| micro 인스턴스 연결 수 경보 | `max_connections` 약 60 | 파드 수 × 풀 확인 | 8-E |
| 앱 `Access denied for user '{svc}_app'` / `Connections using insecure transport are prohibited` | Secret 교체 뒤 재시작 안 함 / URL에 sslMode 없음 | 재시작 / URL | 8-D |
| 시드 flyway `RSA public key is not available client side` | MariaDB Connector/J 2.x는 `sslMode`를 무시 | `useSsl=true&serverSslCert=…` | 8-C |
| 시드 `RDS_HOST 필요` | 환경변수 없이 실행 | `RDS_HOST=… bash run.sh`(catalog 인스턴스) | 14장 10/25 |
| 시드 `timed out waiting for the condition` | run.sh가 실패해도 끝까지 기다림 | 다른 터미널에서 파드 상태 | 8-C |

### 4-5. 이미지·배포·ArgoCD

| 증상 | 원인 | 해결 | 출처 |
| --- | --- | --- | --- |
| `GITOPS_APP_ID 또는 GITOPS_APP_PRIVATE_KEY 가 없어 … 건너뛴다` | 미등록·키를 Variable에 | Secret으로 | 13장 |
| `Integration not found`·`A JSON web token could not be decoded` | App ID 대신 Client ID·키 일부 | 6-A-3 | 13장 |
| `GH013: Repository rule violations`·`403 Resource not accessible by integration` | ruleset bypass에 앱 없음·Contents 읽기 전용 | bypass·Read and write | 13장 |
| `image-tag/kustomization.yaml 이 없다`·`newName이 있다` | gitops 옛 형식 | gitops PR | 13장, 14장 10/26 |
| Trivy 실패 · `Could not find …tomcat…:10.1.58` | CRITICAL·수정 버전 미배포 | Maven Central에서 실제 버전 | 6-A-6 |
| `COPY failed` · `Dockerfile USER가 숫자 UID가 아니다` | 줄 끝 주석·`USER app` | 템플릿대로 `USER 10001` | 13장 |
| `tag invalid: … already exists … immutable` | 같은 태그 재push | 정상 — 새 커밋으로 | 13장 |
| `ImagePullBackOff` | 새 ECR에 그 태그 없음(3-7 전) · 서비스 앱을 gitops·손으로 만듦(레지스트리 없음) | build-push 수동 · Terraform Application으로 | 13장, 8-G |
| `Could not resolve placeholder 'DB_HOST…'` · `configmap "cinema-infra-endpoints" not found` | ConfigMap 없음 | staging-k8s apply | 13장 |
| `helm_release.argocd` `context deadline exceeded` | 노드 0대·pull 실패·메모리 | 노드 확인 → 다시 apply | 13장 |
| Helm `chart "…" version "…" not found` | 없는 차트 버전 | `helm search repo … --versions` | 13장 |
| `InvalidSpecError … project cinema … does not exist`, root OutOfSync 교착 | root가 옛 리비전으로 시작 | 화면 Terminate → Hard Refresh → Sync | 13장, 14장 10/26 |
| `no matches for kind "Rollout"`·`"PodMonitor"` | CRD 전 | 재시도(약 20분), 10회 다 쓰면 수동 Sync | 13장 |
| `{svc}-flyway` 실패 → sync 중단 | migrator Secret·CA·endpoints 없음·SQL 오류 | Job 로그, 새 V 번호 | 13장 |
| catalog OutOfSync·파드 2분마다 재생성 | Git `replicas`와 HPA 충돌 | HPA 대상에서 replicas 제거(반영됨) | 14장 10/26 |
| 블루/그린 `preview-requests` 실패·AnalysisRun `Error` | `SMOKE_SCREENING_ID`·NP·Prometheus | 6-D-5 | 6-D-5 |

### 4-6. 화면·Cognito·CloudFront·도메인

| 증상 | 원인 | 해결 | 출처 |
| --- | --- | --- | --- |
| `InvalidParameterException … Domain already exists` | Cognito 접두사가 다른 계정에서 사용 중 | 2장 `cognito_domain_prefix` 행 | 7-6-1 |
| `/api/users/me` 503 `IDENTITY_UNAVAILABLE`, `GetUser rejected the token` | scope에 `aws.cognito.signin.user.admin` 없음 | 화면 scope | 7-6-4 |
| "User is not confirmed" / 모든 API 401 | Pre sign-up 미연결 / 풀을 다시 만들었는데 ConfigMap 그대로 | staging-k8s 다시 apply → gateway Restart | 7-6-4 |
| `UserNotFoundException` · `cognito_group_members의 값은 사용자 sub…` | 없는 sub·이메일 입력 | 가입 → sub | 7-6-5 |
| 그룹 지정했는데 `NORMAL`·403 | 지정 전에 받은 토큰 | 다시 로그인 | 7-6-5, 6-L |
| CloudFront `/` 403 XML | 버킷 비어 있음 | frontend-deploy | 7-5 |
| `/api/*` 404·S3 XML / 502·504 | 2차 apply 전 / gateway 미준비 | 7-4 | 7-5 |
| `/ops/*`가 index.html | `/ops/*` 동작 없음·무효화 전 | 6-L 1) | 6-L |
| SSE가 몰려서 옴·30초마다 끊김 | 동작 순서·압축·ALB 유휴 | `api_behaviors`, 인계 #13 | 7-5 |
| frontend-deploy `cloudfront:CreateInvalidation` 거부 | `frontend_distribution_ids`가 옛 ID | 3-6 | 7-5 |
| Ingress ADDRESS 비어 있음 | LBC 권한·SG `cinema-staging-alb`·서브넷 태그·**인증서 없는 443 리스너**·**ops-api Service 없는 `/ops`** | LBC 로그, `describe ingress`, 1장 3번 | 13장, 7-8, 6-L |
| Ingress 재생성 뒤 `/api` 502 | ALB DNS 변경 | staging apply 한 번 더 | 7-4, 7-8 |
| 도메인 관련(`no matching Route 53 Hosted Zone` 등) | 7장 | 7장 표 | 7-7, 7-8 |

### 4-7. 메시징·관측·기타

| 증상 | 원인 | 해결 | 출처 |
| --- | --- | --- | --- |
| `AccessDenied … sqs:ReceiveMessage`·`sns:Publish` | ServiceAccount 없음·Deployment가 안 씀 | gitops SA·`serviceAccountName` → Restart | 6-B-6 |
| `KMSAccessDeniedException` | 토픽 키가 고객 관리 키로 바뀜 | `alias/aws/sns` 유지 | 6-B-6 |
| `…-dlq-not-empty` · `…-oldest` · `user-booking-events-oldest` | 받는 쪽 미배포·처리 실패 | **받는 쪽 먼저** · 로그의 `messageType`·`correlationId` | 6-B-6 |
| 매핑 생성 `execution role … does not have permissions` | IAM 전파 지연 | 1분 뒤 다시 apply | 6-B-5 |
| `keep-active-*` 일정 실패 | `lambda:InvokeFunction` 등 없음 | `sqs.tf` | 6-B-4 |
| Grafana 대시보드 없음·회색·`NoCredentialProviders` | 연결보다 먼저 뜬 파드 | Restart | 6-F-4 |
| Loki `AccessDenied`·`NoSuchBucket`(`${LOKI_S3_BUCKET}` 그대로) · Alloy `forbidden … pods/log` | 연결보다 먼저 뜸·ConfigMap 없음 | init·Restart | 6-J |
| ai `KeyError: 'AI_VALKEY_HOST'` · `ERR unknown command 'FT.CREATE'` · `OOM command not allowed` | 3단계 누락·엔진·메모리 | 6-G-1·6 | 6-G |
| ai 의미 캐시 미적중 | 인덱스를 기동 때 한 번만 | ng-ai down/up. **새 클러스터면 6-G-6 벡터 검색 확인을 다시** | 6-G-6·7 |
| ESO `InvalidProviderConfig`·`SecretSyncedError … ResourceNotFoundException` | Pod Identity 전·이름 불일치·Terraform Secret 전 | 8-F | 8-F |
| ops-api 503 `NOT_CONFIGURED`·`JWKS_UNAVAILABLE`·`STATE_CONFIGMAP_MISSING` | cognito 설정·NAT·sync 전 | 6-L 표 | 6-L |
| `AccessDeniedException … eks:DescribeUpdate`(cluster-schedule) | 클러스터 ARN 필요 | 반영됨(#41) | 8-1-3 |
| `ValidationException: The execution role you provide must allow AWS EventBridge Scheduler to assume the role` | SourceArn에 일정 ARN | 일정 **그룹** ARN(반영됨), 10~20초 뒤 다시 | 8-1-3, #49 |
| 끄기 일정 `CreateSchedule` `ValidationException` | end_date 전에 한 번도 실행되지 않는 일정 | 49시간 전부터 끄기 일정을 뺀다(반영됨, #126) — 2장 `cluster_schedule_demo_start` | #126 |
| NetworkPolicy 허용인데 BLOCKED 등 | 라벨·agent·권한 | `np-check.sh`, 6-E | 6-E |
| 포트원 verify 503 `PORTONE_UNAVAILABLE`, 웹훅 401 | `APP_PORTONE_ENABLED`가 Secret보다 먼저 / 웹훅 URL이 옛 도메인 | 3-15 순서 / 2장 포트원 행 | 6-I |

---

## 5. 다 만든 뒤 확인

```bash
infra/scripts/msa-check.sh all; echo "exit=$?"    # ArgoCD 전부 Healthy · 파드 AZ 분산 · DLQ 0 · RDS Multi-AZ · Valkey 복제본 · 정지 기록 없음
infra/scripts/np-check.sh                          # REQUIRE_GATEWAY=1 REQUIRE_COUPON=1 (6-E)
```

- [ ] 기본 도메인 `https://<새 배포>.cloudfront.net/` 200 · `/api/movies?page=0` 200 · 로그인 → `/callback` · 좌석 SSE ping
- [ ] 예매 1건: 선점 → 토스페이(또는 모의 카드 k6) → CONFIRMED → 마이페이지 · 알림 Lambda 로그에 `eventId`
- [ ] 쿠폰: VIP 계정 발급 → 쿠폰 결제 → 할인 금액 · `/ops` ADMIN 200 · 일반 403
- [ ] booking 블루/그린 1회(6-D-2) — 분석 통과·전환
- [ ] 신호등·AI 대시보드 · Loki 조회 · 리포트 내보내기 1회(6-M)
- [ ] 두 번째 plan: staging·staging-k8s·global 모두 **No changes**
- [ ] 밤 끄기·아침 켜기 일정(8-1-3) · Budgets 일·월
- [ ] terraform-infra 14장에 한 줄(날짜·소요·막힌 곳) — 다음 재구축의 근거

---

## 6. 옛 계정 정리 (새 계정 확인·도메인 이전 뒤)

terraform-infra **11장 순서 그대로**(옛 계정 자격 증명으로, 옛 `backend.hcl`로):

1. `cluster-scale.sh down` → Karpenter 인스턴스 0 확인(남으면 ENI가 VPC 삭제를 막는다)
2. staging-k8s `terraform destroy -target=helm_release.argocd_apps` → ALB가 사라질 때까지(`aws elbv2 describe-load-balancers --names cinema-staging-gateway` → `LoadBalancerNotFound`)
3. staging-k8s 나머지 → staging (`plan -destroy`로 목록 확인)
4. 남은 것 확인: EIP·ENI·ALB·EBS·Route 53 호스팅 영역(7장에서 옮긴 뒤 지운다)
5. **global·bootstrap**: 계정을 통째로 닫는다면 그 전에 지운다(평소에는 지우지 않는 규칙 — 계정 폐기 때만 예외. state 버킷은 버전까지 비워야 지워진다). 계정을 남겨 두면 그대로
6. 비밀: 옛 Anthropic 키·포트원 키를 폐기하고 새 계정에서 새로 발급했는지 확인
- RDS는 `skip_final_snapshot = true`라 스냅샷이 남지 않는다(0장 — 데이터를 옮기지 않는 전제). Secrets Manager는 `recovery_window_in_days = 0`이라 바로 지워진다

---

## 7. 도메인·Route 53·DNS 이전 (다른 AWS 계정으로)

> 지금 도메인(`custom_domain`, apex 하나)은 **옛 계정의 Route 53에 등록**돼 있고, 등록할 때 자동으로 생긴 **퍼블릭 호스팅 영역**이 DNS를 맡는다. Terraform(`custom_domain.tf`)은 그 영역을 **찾아 쓰기만** 한다(영역을 만들거나 지우지 않는다) — 영역에 들어 있는 레코드(인증서 검증 CNAME·apex 별칭 A/AAAA·`origin.` 별칭 A)는 Terraform이 만든 것이다.
> 옮길 것은 두 가지이고 **서로 별개**다: ① **등록**(누가 도메인을 소유·갱신하나 — 계정 간 이전) ② **DNS 서비스**(어느 호스팅 영역이 답하나 — 네임서버 교체). 옛 계정을 닫으면 ① 등록도 같이 사라지므로 **반드시 ①을 옮긴다**.
> 콘솔 예외: 도메인 등록·이전은 Terraform 밖(구매·소유권)이라 사람이 한다(7-7과 같은 예외, 스크럼 공유). 아래는 CLI로 적었고 콘솔로 해도 된다. 등록자 연락처는 개인정보라 PR·문서에 쓰지 않는다.

### 7-1. 다운타임과 순서의 원칙

- **같은 별칭(도메인)을 두 CloudFront 배포에 동시에 붙일 수 없다** — 옛 배포가 쥐고 있으면 새 배포 apply가 `CNAMEAlreadyExists`로 실패한다. 그래서 「옛 배포에서 떼기 → 새 배포에 붙이기」 사이에 **사용자 도메인이 잠깐 끊긴다**(기본 도메인 `*.cloudfront.net`은 두 계정 모두 계속 동작). 시연·측정이 없는 시간에, 스크럼 공유 뒤
- 새 계정의 인증서 DNS 검증은 **새 호스팅 영역이 실제로 답해야** 끝난다 → 네임서버 교체가 퍼진 뒤에 새 계정 `custom_domain`을 켠다
- 순서 요약: 새 계정 기본 도메인으로 완성(3장) → 새 계정에 빈 호스팅 영역 → 옛 계정 HTTPS 원본·도메인 떼기 → **등록 이전** → 네임서버를 새 영역으로 → 퍼짐 확인 → 새 계정 `custom_domain` → `api_origin_https` prepare → gitops 443 → on → 옛 영역 삭제

### 7-2. 준비 (이전 하루 전)

```bash
D=<도메인>
# (옛 계정) 등록 상태 — 이전 잠금·만료일·자동 갱신
aws route53domains get-domain-detail --region us-east-1 --domain-name $D \
  --query '{expiry:ExpirationDate, autoRenew:AutoRenew, status:StatusList, ns:Nameservers[].Name}'
# 만료가 가까우면(며칠 안) 먼저 갱신 여부를 정한다 — 이전 중 만료되면 도메인을 잃는다
# (옛 계정) 지금 영역의 레코드 목록(값은 레포 밖에 메모 — 계정 ID·영역 ID는 PR에 쓰지 않는다)
ZID=$(aws route53 list-hosted-zones-by-name --dns-name $D --query "HostedZones[?Name=='$D.'].Id" --output text)
aws route53 list-resource-record-sets --hosted-zone-id $ZID --query 'ResourceRecordSets[].[Name,Type]' --output text
```

- 레코드는 Terraform이 만든 것(검증 CNAME·apex A/AAAA·`origin.` A)과 영역 기본(NS·SOA)뿐이어야 한다. 손으로 넣은 레코드(메일 MX·TXT 등)가 있으면 7-4에서 새 영역에 같이 옮긴다
- 미리 TTL을 낮춘다(선택): 옛 영역 NS 레코드 TTL은 기본 2일이다. 이전 하루 전에 `aws route53 change-resource-record-sets`로 NS TTL을 300으로 낮추면 7-6의 전파가 빨라진다(TLD 쪽 위임 TTL은 바꿀 수 없어 효과는 일부)

### 7-3. 새 계정에 빈 호스팅 영역 만들기 (새 계정)

```bash
aws route53 create-hosted-zone --name $D --caller-reference "seatsac-$(date +%s)" \
  --hosted-zone-config Comment="SeatSAC custom domain (ADR-125)" \
  --query 'DelegationSet.NameServers' --output text        # 새 네임서버 4개 — 7-5에서 쓴다(값은 레포 밖에 메모)
```

- 같은 이름의 영역이 새 계정에 이미 있으면(재시도) 하나만 남기고 지운다 — 둘이면 `multiple Route 53 Hosted Zone matched`(그때는 `custom_domain_zone_id`에 네임서버가 맞는 쪽 ID)
- 영역 생성은 도메인과 같은 콘솔 예외로 둔다(Terraform `custom_domain.tf`가 영역을 data로 찾는 구조라서). 비용 월 $0.50

### 7-4. 옛 계정에서 도메인 떼기 (옛 계정 — 이 순간부터 사용자 도메인이 끊긴다)

1. 1장 3번에서 `api_origin_https`를 `prepare`로 이미 내렸다면 이어서 **`off`** apply(인증서가 리스너에 물려 있지 않은지 — gitops 443은 1장 3번에서 이미 뺐다)
2. `custom_domain = ""` apply — plan 기대: **삭제** 인증서 1·검증 레코드 1·검증 1·별칭 2, **변경** CloudFront 1(`aliases → []`)·Cognito 앱 클라이언트 1(콜백). 추가 없음
   - 옛 계정은 1장 이후 동결이므로 **옛 커밋(동결 직전 main)에서** `-var custom_domain= -var api_origin_https=off`로 plan·apply한다(레포 tfvars는 이미 새 계정 값일 수 있다)
3. `aws cloudfront get-distribution --id <옛 배포> --query Distribution.Status` → `Deployed`(별칭이 실제로 풀린 뒤에야 새 배포에 붙는다)
4. 손으로 넣은 레코드가 있으면 새 영역(7-3)에 같은 값으로 만든다

### 7-5. 등록 이전 (옛 계정 → 새 계정)

```bash
# (옛 계정) 이전 시작 — 새 계정 ID로. 결과의 Password는 화면에서만 보고 레포·채팅·PR에 쓰지 않는다
aws route53domains transfer-domain-to-another-aws-account --region us-east-1 \
  --domain-name $D --account-id <새 계정 ID> --query '{op:OperationId}' --output text
#   Password는 같은 응답에 있다 — 안전한 경로(관리자 둘이 직접)로만 넘긴다
# (새 계정) 3일 안에 수락
read -rs XFER_PASSWORD
aws route53domains accept-domain-transfer-from-another-aws-account --region us-east-1 \
  --domain-name $D --password "$XFER_PASSWORD"; unset XFER_PASSWORD
aws route53domains get-operation-detail --region us-east-1 --operation-id <OperationId>   # SUCCESSFUL
aws route53domains list-domains --region us-east-1 --query 'Domains[].DomainName'         # (새 계정) 보인다
```

- 등록 이전은 **DNS를 바꾸지 않는다** — 이전이 끝나도 네임서버는 옛 계정 영역을 가리킨다(옛 영역이 살아 있는 동안 계속 답한다). 그래서 다음 7-6 전에 옛 영역을 지우지 않는다
- 이전 후 자동 갱신 설정이 새 계정 기준이 된다 — 원하는 값으로 `enable-domain-auto-renew`/`disable-domain-auto-renew`
- 3일 안에 수락하지 않으면 이전이 취소된다(다시 시작). 이전에 이전 잠금·만료 임박이 걸리면 7-2로 돌아간다

### 7-6. 네임서버를 새 영역으로 (새 계정)

```bash
aws route53domains update-domain-nameservers --region us-east-1 --domain-name $D \
  --nameservers Name=<NS1> Name=<NS2> Name=<NS3> Name=<NS4>          # 7-3의 네임서버 4개
aws route53domains get-domain-detail --region us-east-1 --domain-name $D --query 'Nameservers[].Name'
# 전파 확인 — 공개 리졸버가 새 영역의 SOA를 돌려줄 때까지(수 분~최대 2일)
dig +short NS $D @8.8.8.8
dig +short SOA $D @1.1.1.1
```

- 전파 중에는 리졸버마다 옛·새 영역이 섞여 답한다. 옛 영역에는 이제 apex 별칭이 없고(7-4), 새 영역도 아직 비어 있어 **사용자 도메인은 어느 쪽이든 아직 끊긴 상태**다 — 정상
- 퍼진 것을 확인한 뒤에 7-7로(새 인증서 DNS 검증이 새 영역에서 답을 받아야 끝난다. 덜 퍼졌으면 apply가 검증 대기에서 오래 멈춘다 — 그래도 실패는 아니고 기다리면 끝난다)

### 7-7. 새 계정에 도메인 붙이기 (새 계정 — 여기서 사용자 도메인이 다시 열린다)

1. `staging.tfvars` `custom_domain = "<도메인>"` PR(같은 이름 영역이 하나면 `custom_domain_zone_id`는 비워 둔다) → `infra-plan` 기대: terraform-infra 7-7과 같다(**추가** 인증서 1·검증 레코드 1·검증 1·별칭 2, **변경** CloudFront 1·Cognito 앱 클라이언트 1) → apply
2. `frontend-deploy` 수동 실행(콜백은 화면 주소 + `/callback`이라 그대로지만 캐시 무효화 겸) → 사용자 도메인으로 로그인·SSE·결제 확인
3. **HTTPS 원본** — terraform-infra 7-8 순서 그대로: `api_origin_https = "prepare"` apply → 인증서 `origin.<도메인>` ISSUED 확인 → gitops `base/gateway/ingress.yaml`에 443 리스너·`tls.hosts` 되돌리는 PR(1장 3번에서 뺀 것) → `on` apply
4. 포트원 콘솔: 웹훅 URL·허용 도메인을 사용자 도메인으로(2장 포트원 행) → 첫 결제로 확인
5. 확인
   ```bash
   curl -sI https://$D/ | head -1                                                # 200
   curl -s https://$D/api/movies?page=0 -o /dev/null -w '%{http_code}\n'         # 200
   curl -s https://<새 기본 도메인>/api/movies?page=0 -o /dev/null -w '%{http_code}\n'   # 200
   curl -N https://$D/api/screenings/<회차>/seats/stream                          # 15초마다 ping
   ```

### 7-8. 옛 영역 정리 (옛 계정, 7-6 뒤 이틀 이상 지나서)

```bash
# 위임이 완전히 새 영역으로 옮겨진 뒤(옛 NS TTL 2일). 영역 안의 레코드를 NS·SOA만 남기고 지운 뒤 영역 삭제
aws route53 list-resource-record-sets --hosted-zone-id $ZID --query 'ResourceRecordSets[?Type!=`NS` && Type!=`SOA`].[Name,Type]'
aws route53 delete-hosted-zone --id $ZID
```

| 증상 | 원인 | 해결 |
| --- | --- | --- |
| 새 계정 apply `CNAMEAlreadyExists` | 옛 CloudFront가 아직 별칭을 쥐고 있다 | 7-4 2·3번(옛 배포 `Deployed`까지) |
| `transfer-domain-to-another-aws-account` 거부 | 이전 잠금·만료 임박·최근 등록·이전(TLD 규칙) | `get-domain-detail`의 상태 확인, 만료 전 갱신 |
| 수락 `InvalidInput` | 비밀번호 오타·3일 초과 | 옛 계정에서 이전 다시 시작 |
| 새 계정 plan `no matching Route 53 Hosted Zone` | 7-3을 안 했다·이름 철자 | 7-3 |
| `multiple Route 53 Hosted Zone matched` | 같은 이름 영역이 둘 | 하나 삭제 또는 `custom_domain_zone_id` |
| 인증서 검증 대기가 길다 | 네임서버가 아직 덜 퍼졌다 | 7-6의 `dig` 확인 — 기다린다 |
| 사용자 도메인이 일부 사용자에게만 열린다 | 리졸버 캐시(옛 NS) | TTL만큼 기다린다(최대 2일) — 옛 영역을 그 전에 지우지 않는다 |
| 로그인 뒤 `redirect_mismatch` | 새 Cognito 앱 클라이언트 콜백에 도메인이 아직 없다 | 7-7 1번 apply가 끝났는지 |
| 결제 웹훅 401·도착 안 함 | 포트원 웹훅 URL이 옛 기본 도메인 | 7-7 4번 |

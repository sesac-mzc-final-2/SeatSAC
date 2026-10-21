# [런북] Terraform 인프라 (bootstrap · global · staging)

| 항목 | 내용 |
| --- | --- |
| 목적 | 빈 상태에서 SeatSAC AWS 인프라를 같은 순서로 다시 만들고, 평소 변경·복구를 같은 절차로 한다 |
| 언제 | 최초 구축, 재생성 리허설(OPS-14, 11/11~12), 계정 초기화 후, 평소 인프라 변경 |
| 담당 / 권한 | 김종식 (백업: 백업 관리자) / 최초 1회는 IAM 사용자, 이후 `cinema-admin` 역할 (MFA) |
| 코드 | `infra/terraform/{bootstrap,global,envs/staging}` |
| 예상 소요 | bootstrap 2분 · global 3분 · staging 네트워크 5분 (이후 단계는 실측 후 기록) |
| 마지막 검증 | ⬜ YYYY-MM-DD · 실행자 · 총 소요 ⬜분 |

> 원칙: **콘솔에서 만들거나 고치지 않는다.** 모든 apply 전에 plan을 보고, `destroy`·`replace`가 하나라도 있으면 멈춘다. 비밀 값(비밀번호·키·tfstate 내용)은 이 문서에 적지 않는다.

---

## 0. 사전 조건

- [ ] 도구: Terraform ≥ 1.10, AWS CLI v2 (3.1.2부터 kubectl, helm)
- [ ] 계정: 학원 조직(Organizations)의 **멤버 계정**이다. IAM Identity Center는 쓰지 않는다(계정 인스턴스는 AWS 계정 접근을 지원하지 않음)
- [ ] SCP: 주요 작업 허용 확인 완료(10/21 `simulate-principal-policy` 전부 `allowed`, t3.large `run-instances --dry-run` 성공). 새 서비스를 쓰기 전에는 같은 방법으로 확인
- [ ] 자격 증명 확인
  ```bash
  aws sts get-caller-identity
  # 최초 1회(bootstrap·global): IAM 사용자 ARN
  # 이후: arn:aws:sts::<계정ID>:assumed-role/cinema-admin/... 
  ```
- [ ] 팀 공지 (apply 중 다른 사람이 apply 하지 않도록). 데모 주간(11/16~20)에는 하지 않는다

**순서와 이유**

```
bootstrap (state 버킷)                       ← 이게 있어야 나머지 state를 S3에 둔다
  └─ global (팀 역할·GitHub OIDC·CI 역할)     ← 계정 수준. staging을 지워도 남는다
       └─ staging
            ├─ 1) 네트워크 (3.1.1)            ← NAT가 있어야 노드가 Ready
            ├─ 2) EKS (3.1.2)                 ← global의 역할 ARN으로 Access Entry
            ├─ 3) RDS·Valkey (3.1.3)
            ├─ 4) ECR·SQS·Lambda (3.1.4·3.1.8·3.1.9)
            ├─ 5) Cognito·CloudFront·S3 (3.3.x·3.1.11)
            └─ 6) DB 계정·K8s Secret (3.1.12) → ArgoCD 부트스트랩(3.1.5) → 시드 적재
```

---

## 1. bootstrap — state 버킷 (계정당 1회)

```bash
cd infra/terraform/bootstrap
terraform init
terraform plan                  # 기대: S3 버킷 1개 + 버전 관리·암호화·퍼블릭 차단 설정
terraform apply
terraform output                # tfstate_bucket = cinema-tfstate-<계정ID>
```

- **확인**: S3에 `cinema-tfstate-<계정ID>`, 버전 관리 켜짐, 퍼블릭 차단 4개 모두 켜짐
- 이 폴더만 로컬 state를 쓴다. `bootstrap/terraform.tfstate`는 커밋하지 않고 **destroy 하지 않는다** (`prevent_destroy`)
- 이미 버킷이 있으면 이 단계를 건너뛴다

**다음 단계 준비**: 출력된 버킷 이름을 `global/backend.hcl`, `envs/staging/backend.hcl`에 적는다.

```hcl
bucket = "cinema-tfstate-<계정ID>"
```

---

## 2. global — 팀 접근·CI 역할 (3.1.10, OPS-11)

### 2-1. 값 채우기

`infra/terraform/global/global.tfvars`

```hcl
admin_users     = ["<김종식 IAM 사용자>", "<백업 관리자 IAM 사용자>"]
developer_users = ["<김종식>", "<팀원2>", "<팀원3>", "<팀원4>"]
attach_assume_policy_to_users = false   # 팀원 IAM 사용자에 이미 Admin이 있으면 false
github_owner = "<GitHub에 보이는 그대로>"
github_repo  = "<레포 이름, 대소문자 그대로>"
```

- IAM 사용자 이름은 콘솔 IAM 사용자 목록과 **정확히** 같아야 한다 (틀리면 apply에서 `Invalid principal`)
- GitHub owner·repo는 **대소문자까지** GitHub 표기와 같아야 한다 (틀리면 Actions에서 `Not authorized to perform sts:AssumeRoleWithWebIdentity`)

### 2-2. GitHub OIDC 공급자가 이미 있는지 확인

공급자는 계정당 URL 하나만 만들 수 있다.

```bash
aws iam list-open-id-connect-providers
```

| 결과 | 처리 |
| --- | --- |
| `token.actions.githubusercontent.com` 없음 | 그대로 2-3 진행 (Terraform이 만든다) |
| 있음 + 우리가 만든 것(다른 state에서 관리 중) | 그 state에서 `terraform state rm` → 코드 삭제 → 2-3 전에 global에서 `terraform import` (12장) |
| 있음 + 다른 팀·학원 것 | 만들지 말고 `data "aws_iam_openid_connect_provider"`로 참조하도록 `github_oidc.tf` 수정 |

### 2-3. 적용

```bash
cd infra/terraform/global
terraform init -backend-config=backend.hcl
terraform fmt && terraform validate
terraform plan  -var-file=global.tfvars -out=plan.out
# 기대: 역할 5개(cinema-admin, cinema-developer, cinema-gha-ecr-push, cinema-gha-tf-plan, cinema-gha-frontend-deploy)
#       + 정책 연결·인라인 정책 + (없었다면) OIDC 공급자 1개.  destroy 0
terraform apply plan.out
```

### 2-4. 출력값 사용

```bash
terraform output admin_role_arn         # → 3.1.2 EKS Access Entry
terraform output developer_role_arn     # → 3.1.2 EKS Access Entry
terraform output github_secrets         # → GitHub 레포 Actions Secrets 3개
terraform output aws_config_profiles    # → 팀원 ~/.aws/config
```

**GitHub Secrets 등록**: 레포 → Settings → Secrets and variables → Actions → **Repository secrets**

| 이름 | 값 |
| --- | --- |
| `AWS_ECR_PUSH_ROLE_ARN` | `github_secrets` 출력값 |
| `AWS_TERRAFORM_PLAN_ROLE_ARN` | 〃 |
| `AWS_FRONTEND_DEPLOY_ROLE_ARN` | 〃 |

### 2-5. 팀원 접속 설정 (각자 5분)

1. IAM 사용자로 콘솔 로그인 → 내 이름 → **보안 자격 증명** → **MFA 디바이스 할당** → 인증 앱 → QR 스캔 → 코드 2개 입력
2. 같은 화면의 MFA ARN(`arn:aws:iam::<계정ID>:mfa/<이름>`) 복사
3. `aws_config_profiles` 출력을 `~/.aws/config`에 붙이고 `mfa_serial`을 자기 값으로 변경
4. 확인
   ```bash
   aws sts get-caller-identity --profile cinema-dev
   # MFA 코드 입력 → Arn에 assumed-role/cinema-developer 가 보이면 성공 (8시간 유지)
   ```
5. 평소에는 `cinema-dev`, 인프라 변경 시에만 `cinema-admin` (관리자 2명)

### 2-6. 확인

- [ ] 4명 모두 `cinema-dev` 전환 성공
- [ ] 관리자 2명 `cinema-admin` 전환 성공
- [ ] developer로 tfstate 객체 읽기 거부: `aws s3 cp s3://cinema-tfstate-<계정ID>/staging/terraform.tfstate - --profile cinema-dev` → `AccessDenied`
- [ ] PR에서 `infra-plan` 워크플로 성공 (Secrets 등록 후)
- [ ] 두 번째 `terraform plan -var-file=global.tfvars` → No changes

---

## 3. staging 1) 네트워크 (3.1.1)

```bash
export AWS_PROFILE=cinema-admin
cd infra/terraform/envs/staging
terraform init -backend-config=backend.hcl
terraform fmt -recursive && terraform validate
terraform plan  -var-file=staging.tfvars -out=plan.out
# 기대: VPC 1 · 서브넷 6 · IGW 1 · NAT 1 · EIP 1 · 라우트 테이블·연결 · S3 엔드포인트 1 · SG 4 + 규칙. destroy 0
terraform apply plan.out          # 3~5분 (NAT 생성이 가장 오래)
terraform output
terraform plan -var-file=staging.tfvars   # No changes
```

- **확인**: 서브넷 6개·태그 / app 라우트 → `nat-` / S3 엔드포인트 / alb SG 인바운드가 `pl-` 접두사 목록
- **기록**: vpc_id, NAT 공인 IP, 소요 시간 → 14장 실행 기록

---

## 4. staging 2) EKS (3.1.2) — ⬜ 작업 후 채움

- [ ] 노드그룹은 app 서브넷(`module.vpc.private_subnets`), On-Demand t3.large ng-a 1대 · ng-c 1대
- [ ] 애드온: VPC CNI(네트워크 정책 켬) · EBS CSI · Pod Identity Agent · metrics-server
- [ ] `authentication_mode = "API"`, Access Entry: `cinema-admin` → ClusterAdmin(cluster) / `cinema-developer` → View(namespace `cinema-staging`) + `kubernetes_groups = ["cinema-developers"]`
- [ ] `enable_cluster_creator_admin_permissions = false`이면 admin Access Entry가 먼저 들어가는지 plan에서 확인 (아니면 내 kubectl 권한도 없어짐)
- [ ] 노드그룹 `desired_size`는 `ignore_changes` (밤 0대)
- [ ] 확인: 4명 `aws eks update-kubeconfig --name cinema-staging-eks --profile cinema-dev` → `kubectl get pods -n cinema-staging` 성공, developer의 `kubectl delete`·`get secret` 실패

## 5. staging 3) RDS·Valkey (3.1.3) — ⬜

## 6. staging 4) ECR·SQS·Lambda (3.1.4·3.1.8·3.1.9) — ⬜

- [ ] ECR 저장소 `cinema/{service}`: 태그 불변(`IMMUTABLE`) + 저장소 정책으로 `cinema-gha-ecr-push`만 push

## 7. staging 5) Cognito·CloudFront·S3 (3.1.11) — ⬜

- [ ] `cinema-gha-frontend-deploy`의 CloudFront 무효화 권한을 배포 ARN으로 좁힘 (global 수정)

## 8. staging 6) DB 계정·K8s Secret·ArgoCD·시드 (3.1.12·3.1.5) — ⬜

---

## 9. 최종 확인

- [ ] global·staging 모두 두 번째 plan이 No changes
- [ ] `kubectl get pods -n cinema-staging` 전부 Running
- [ ] CloudFront 도메인에서 화면·API 동작
- [ ] 14장 실행 기록에 단계별 소요 시간 기록

---

## 10. 평소 변경 절차

```bash
git switch main && git pull
git switch -c infra/<기능ID 또는 WBS ID>-<설명>     # 예: infra/OPS-11-iam-roles, infra/3.1.3-rds-valkey
# 코드 수정
terraform fmt -recursive && terraform validate
terraform plan -var-file=<env>.tfvars -out=plan.out    # 결과를 PR 본문에 붙임
# PR 리뷰 1명 → 승인 후
terraform apply plan.out                                # apply는 김종식(또는 백업 관리자)만
```

- plan 마지막 줄 `Plan: A to add, C to change, D to destroy`의 **D가 0이 아니면** 이유를 PR에 적는다
- `init`은 모듈·provider 버전이나 backend 설정을 바꿀 때만 다시 한다

---

## 11. 삭제 (재생성 리허설·발표 후)

```bash
export AWS_PROFILE=cinema-admin
cd infra/terraform/envs/staging
terraform plan -destroy -var-file=staging.tfvars     # 지워질 목록 확인
terraform destroy -var-file=staging.tfvars
```

- 순서: ArgoCD 앱 정리(LB Controller가 만든 ALB·대상 그룹이 남지 않게) → staging destroy
- **global·bootstrap은 지우지 않는다.** 팀 접근·CI 역할·state 버킷은 계속 쓴다
- 삭제 후 확인: EIP·ENI·ALB·EBS 볼륨이 남지 않았는지 (`aws ec2 describe-addresses`, `describe-volumes`)

---

## 12. 복구

| 상황 | 명령 | 주의 |
| --- | --- | --- |
| state 잠금이 남음 (`Error acquiring the state lock`) | `terraform force-unlock <Lock ID>` | 다른 사람이 apply 중이 아닌지 먼저 확인 |
| AWS에는 있는데 state에 없음 (`EntityAlreadyExists`, `already exists`) | `terraform import <주소> <ID 또는 ARN>` | 다른 팀 리소스면 import 대신 `data`로 참조 |
| 다른 state가 이미 관리 중 (`Resource already managed by Terraform`) | 원래 state에서 `terraform state rm <주소>` → 그 코드 삭제 → 새 state에서 import | `state rm` 없이 코드만 지우고 apply하면 **실제 리소스가 삭제됨** |
| apply 중간 실패 | 원인 수정 후 다시 `plan` → `apply` | 성공한 리소스는 state에 있어 다시 만들지 않음. tainted는 재생성 |
| 콘솔에서 바뀜 (drift) | `terraform plan`으로 차이 확인 → 코드를 고치거나 apply로 되돌림 | 콘솔 수정 금지 |
| 잘못된 apply 후 state 되돌리기 | S3 버전 관리에서 이전 `terraform.tfstate` 버전 확인 | 최후 수단. 실제 리소스와 맞는지 plan으로 확인 |

---

## 13. 자주 막히는 곳

| 증상 | 원인 | 해결 |
| --- | --- | --- |
| `init`에서 `bucket - The name of the S3 bucket` 입력 요구 | `-backend-config=backend.hcl` 누락 또는 bootstrap 전 | 1장 → `backend.hcl` 채우기 → `init -backend-config=backend.hcl` |
| `CreateOpenIDConnectProvider ... 409 EntityAlreadyExists` | 계정에 GitHub OIDC 공급자가 이미 있음 | 2-2 표대로 import 또는 data 참조 |
| `Resource already managed by Terraform` (import 시) | 그 리소스를 이미 다른(또는 같은) state가 관리 중 | `terraform state list`로 어느 폴더 state인지 확인 → 12장 |
| `AccessDenied ... sts:AssumeRole` (팀원) | `global.tfvars` 사용자 목록에 없음 / MFA 없이 시도 / 이름 대소문자 | tfvars 수정 후 apply, `mfa_serial` 확인 |
| Actions `Not authorized to perform sts:AssumeRoleWithWebIdentity` | `github_owner`·`github_repo` 대소문자 불일치, 이벤트(PR·main) 불일치 | GitHub 표기와 맞춰 global apply |
| Actions `Could not load credentials from any providers` | Secret 미등록 또는 `permissions: id-token: write` 없음 | 2-4 Secrets 등록, 워크플로 permissions 확인 |
| `explicit deny ... service control policy` | 학원 SCP | 학원에 문의 |
| NAT를 지웠는데 EIP가 남음 | Terraform 밖 EIP(EC2 연결)일 가능성 | `aws ec2 describe-addresses`로 태그·연결 대상 확인 |
| plan에 `-/+ replace`가 나옴 | 교체가 필요한 값 변경(서브넷 CIDR, SG description 등) | 멈추고 원인 확인. 의존 리소스까지 다시 만들어질 수 있음 |

---

## 14. 실행 기록

| 날짜 | 실행자 | 단계 | 소요 | 문제 | 런북 수정 |
| --- | --- | --- | --- | --- | --- |
| 10/20 | 김종식 | 1 bootstrap · 3 네트워크 | ⬜ | init 때 버킷 입력 요구 | 1장 "다음 단계 준비" 추가 |
| 10/21 | 김종식 | 2 global | ⬜ | OIDC 공급자 409, import 시 already managed | 2-2·12장·13장 추가 |
# [런북] Terraform 인프라 (bootstrap · global · staging)

| 항목 | 내용 |
| --- | --- |
| 목적 | 빈 상태에서 SeatSAC AWS 인프라를 같은 순서로 다시 만들고, 평소 변경·복구를 같은 절차로 한다 |
| 언제 | 최초 구축, 재생성 리허설(OPS-14, 11/11~12), 계정 초기화 후, 평소 인프라 변경 |
| 담당 / 권한 | 김종식 (백업: 백업 관리자) / 최초 1회는 IAM 사용자, 이후 `cinema-admin` 역할 (MFA) |
| 코드 | `infra/terraform/{bootstrap,global,envs/staging,envs/staging-k8s}` |
| 예상 소요 | bootstrap 2분 · global 3분 · staging 네트워크 5분 · EKS 15~20분 + staging-k8s 3분 · RDS·Valkey 15~20분(동시 생성) (이후 단계는 실측 후 기록) |
| 마지막 검증 | ⬜ YYYY-MM-DD · 실행자 · 총 소요 ⬜분 |

> 원칙: **콘솔에서 만들거나 고치지 않는다.** 모든 apply 전에 plan을 보고, `destroy`·`replace`가 하나라도 있으면 멈춘다. 비밀 값(비밀번호·키·tfstate 내용)은 이 문서에 적지 않는다.

---

## 0. 사전 조건

- [ ] 도구: Terraform ≥ 1.10, AWS CLI v2 (3.1.2부터 kubectl, helm)
  - Windows: Terraform·`cluster-scale.sh`·이 런북 명령을 그대로 쓰는 사람(관리자 2명)은 **WSL2 + Ubuntu**. `kubectl`·로그 확인만 하는 팀원은 Git Bash + `winget install Amazon.AWSCLI Kubernetes.kubectl`도 된다
  - WSL의 `~/.aws`는 Windows(`C:\Users\<이름>\.aws`)와 **별개** → WSL 안에서 프로필을 다시 설정한다. Git Bash는 Windows와 같은 폴더를 쓴다
  - Git Bash에서 MFA 입력이 안 뜨면 Windows Terminal에서 열거나 `winpty aws ...`, `/aws/rds/...` 같은 인자가 경로로 바뀌면 `MSYS_NO_PATHCONV=1 aws ...`
- [ ] 계정: 학원 조직(Organizations)의 **멤버 계정**이다. IAM Identity Center는 쓰지 않는다(계정 인스턴스는 AWS 계정 접근을 지원하지 않음)
- [ ] SCP: 주요 작업 허용 확인 완료(10/21 `simulate-principal-policy` 전부 `allowed`, t3.large `run-instances --dry-run` 성공). 새 서비스를 쓰기 전에는 같은 방법으로 확인
- [ ] 자격 증명 확인
  ```bash
  aws sts get-caller-identity
  # 최초 1회(bootstrap·global): IAM 사용자 ARN
  # 이후: arn:aws:sts::<계정ID>:assumed-role/cinema-admin/... 
  ```
- [ ] **Terraform용 자격 증명** — 이 문서의 `export AWS_PROFILE=cinema-admin`은 CLI(`aws`·`kubectl`·스크립트)용이다. `terraform` 명령 전에는 MFA 세션을 환경변수로 넘긴다(Terraform은 MFA 코드를 묻지 못함):
  ```bash
  eval "$(aws configure export-credentials --profile cinema-admin --format env)"   # MFA 입력
  unset AWS_PROFILE
  aws sts get-caller-identity      # assumed-role/cinema-admin 이면 이 터미널에서 terraform 실행 (8시간)
  ```
- [ ] 팀 공지 (apply 중 다른 사람이 apply 하지 않도록). 데모 주간(11/16~20)에는 하지 않는다

**순서와 이유**

```
bootstrap (state 버킷)                       ← 이게 있어야 나머지 state를 S3에 둔다
  └─ global (팀 역할·GitHub OIDC·CI 역할)     ← 계정 수준. staging을 지워도 남는다
       └─ staging
            ├─ 1) 네트워크 (3.1.1)            ← NAT가 있어야 노드가 Ready
            ├─ 2) EKS (3.1.2)                 ← global의 역할 ARN으로 Access Entry
            │    └─ staging-k8s (namespace·RBAC·LB Controller) ← 클러스터가 있어야 함
            ├─ 3) RDS·Valkey (3.1.3)
            ├─ 4) ECR·SQS·Lambda (3.1.4·3.1.8·3.1.9)
            ├─ 5) Cognito·CloudFront·S3 (3.3.x·3.1.11)
            └─ 6) DB 스키마·계정·K8s Secret (3.1.12, staging-k8s) → 시드 적재
                 └─ ArgoCD·root 앱·서비스 Application (3.1.5, staging-k8s 같은 apply, 6-C-2) → CloudFront /api (7-4)
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

**다음 단계 준비**: `global`·`envs/staging`·`envs/staging-k8s`에 `backend.hcl`을 만든다. **커밋하지 않는다**(`.gitignore`) — 버킷 이름에 계정 ID가 들어가고 이 레포는 Public이다. 각 폴더의 `backend.hcl.example`과 같은 한 줄이다.

```bash
# infra/terraform 에서 (cinema-admin 자격 증명으로)
B="cinema-tfstate-$(aws sts get-caller-identity --query Account --output text)"
for d in global envs/staging envs/staging-k8s; do echo "bucket = \"$B\"" > "$d/backend.hcl"; done
```

- key·region·암호화·잠금은 각 폴더 `versions.tf`의 `backend "s3"` 블록에 있다
- CI(`infra-plan`)는 파일 없이 `-backend-config="bucket=cinema-tfstate-${ACCOUNT_ID}"`로 넘긴다(계정 ID는 마스킹)
- 새로 clone했거나 `git pull` 뒤 파일이 사라졌으면(예전에 커밋돼 있던 파일) 위 명령을 다시 실행한다

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

**프로필 전환·세션 끝내기**

| 하고 싶은 것 | 명령 |
| --- | --- |
| 명령 하나만 다른 역할로 | `--profile cinema-dev` / `--profile cinema-admin` (각각 처음 한 번 MFA, 8시간 캐시) |
| 이 터미널 전체를 한 역할로 | `export AWS_PROFILE=cinema-admin` (apply 전 필수) |
| **Terraform 실행 전 (MFA 세션 넘기기)** | `eval "$(aws configure export-credentials --profile cinema-admin --format env)"` → `unset AWS_PROFILE` → `aws sts get-caller-identity`가 `assumed-role/cinema-admin`. Terraform은 MFA 코드를 묻지 못하므로 CLI가 만든 임시 키를 환경변수로 넘긴다(8시간). 만료되면 다시 실행 |
| 역할 사용 그만두기 | `unset AWS_PROFILE` → `aws sts get-caller-identity`가 `user/...`로 나오면 됨. kubeconfig도 dev 컨텍스트로 돌려 둔다 |
| 이 PC의 역할 세션 지우기(공용 PC) | `rm -rf ~/.aws/cli/cache` → 다음 사용 때 MFA 다시 |
| 세션이 샌 것 같을 때(비상) | IAM 콘솔 → 역할 → **Revoke active sessions**(관리자, 스크럼 공유). 장기 키(`AKIA…`)가 샜으면 그 키 비활성화·삭제 + 학원에 알림 |

- 액세스 키 ID는 IAM 사용자 → 보안 자격 증명에서 보이지만 **시크릿 키는 만들 때 한 번만** 보인다. 잃어버렸으면 새 키를 만들고 이전 키를 지운다. 키는 각자 자기 PC에서만 입력한다

### 2-6. 확인

- [ ] 4명 모두 `cinema-dev` 전환 성공
- [ ] 관리자 2명 `cinema-admin` 전환 성공
- [ ] developer로 tfstate **내용** 읽기 거부: `aws s3 cp s3://cinema-tfstate-<계정ID>/staging/terraform.tfstate - --profile cinema-dev` → `AccessDenied`(GetObject)
  - `aws s3 ls`(파일 이름 목록, ListBucket)는 **두 프로필 모두 되는 게 정상**이다. 막는 것은 내용뿐
  - 막는 이유: tfstate에는 모든 리소스 속성이 **평문**으로 저장된다(`sensitive`도 state에서는 평문). 3.1.12부터 `{svc}_app`·`{svc}_migrator` 비밀번호와 K8s Secret 값, 지금도 경보 메일·허용 IP가 들어 있다. developer는 `kubectl get secret`·Secrets Manager·SSM 값 조회가 막혀 있는데, tfstate가 읽히면 이 차단이 모두 우회된다. `ReadOnlyAccess`는 S3 객체 읽기를 허용하므로 명시적 Deny(`DenyTfstateRead`)가 있어야 한다
  - `cp`가 developer로 **된다면**: ① `aws sts get-caller-identity --profile cinema-dev`의 Arn이 `assumed-role/cinema-developer`인지 ② `aws iam get-role-policy --role-name cinema-developer --policy-name cinema-developer-deny --query 'PolicyDocument.Statement[].Sid'`에 `DenyTfstateRead`가 있는지(없으면 global apply)
  - tfstate 내용이 밖으로 나갔다면 그 안의 비밀번호(3.1.12 계정)를 모두 교체한다
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

## 4. staging 2) EKS (3.1.2)

코드: `envs/staging/eks.tf`(클러스터·노드그룹·애드온·Access Entry·LB Controller Pod Identity·노드 SG 규칙) + `envs/staging-k8s/`(namespace·팀원 RBAC·LB Controller Helm)

| 항목 | 값 |
| --- | --- |
| 클러스터 | `cinema-staging-eks`, Kubernetes 1.36, 인증 모드 `API`(Access Entry만), 공개+프라이빗 엔드포인트 |
| 노드그룹 | `ng-a`(app 서브넷 a) · `ng-c`(app 서브넷 c), t3.large On-Demand, AL2023, min 0 · max 2 · desired 1. 실제 이름은 접두사 + 랜덤(출력 `eks_node_group_names`) |
| 애드온 | vpc-cni(`enableNetworkPolicy=true`) · eks-pod-identity-agent · kube-proxy · coredns · metrics-server. **EBS CSI 없음**(PV 안 씀 — Prometheus도 emptyDir, 밤에 노드 0대라 보존 의미 없음) |
| 접근 | `cinema-admin` → ClusterAdmin(cluster) / `cinema-developer` → View(namespace `cinema-staging`·`monitoring`) + 그룹 `cinema-developers`(port-forward Role) |
| 로그 | 컨트롤 플레인 audit·authenticator, CloudWatch 7일 |
| ALB | LB Controller가 gateway Ingress를 보고 만든다(3.1.5). 노드 SG에 ALB SG → 8080 허용 |

### 4-1. envs/staging (AWS 리소스)

```bash
export AWS_PROFILE=cinema-admin          # ⚠️ 반드시. 만든 사람 자동 관리자 권한을 껐으므로 다른 주체로 만들면 kubectl 권한이 없다
aws sts get-caller-identity              # assumed-role/cinema-admin 확인
cd infra/terraform/envs/staging
cp eks_access.auto.tfvars.example eks_access.auto.tfvars   # 처음 한 번: 팀 공인 IP 입력 (4-6)
terraform init -upgrade -backend-config=backend.hcl   # 새 모듈(eks, eks-pod-identity) 받기. EKS v21.29는 AWS provider ≥ 6.59
terraform fmt -recursive && terraform validate
terraform plan -var-file=staging.tfvars -out=plan.out
# 기대: EKS 클러스터 1 · 노드그룹 2 · 애드온 5 · Access Entry 2 + 정책 연결 2 · IAM 역할(클러스터·노드×2·LB Controller)
#       · KMS 키(시크릿 암호화) · 로그 그룹 1 · SG 2 + 규칙 · Pod Identity 연결 1
#       destroy = lambda_stats SG와 그 규칙(10/22 AI·OPS-08 제외)만. 그 외 destroy가 있으면 멈춘다
terraform apply plan.out                 # 15~20분 (클러스터 ~10분, 노드그룹 ~3분)
terraform output eks_kubeconfig_command
```

### 4-2. 관리자 kubectl 확인

```bash
aws eks update-kubeconfig --region ap-northeast-2 --name cinema-staging-eks --profile cinema-admin --alias cinema-admin --user-alias cinema-admin
kubectl config use-context cinema-admin
kubectl get nodes -L topology.kubernetes.io/zone,cinema/az     # 2대 Ready, zone a·c 하나씩
kubectl get pods -n kube-system                                  # aws-node·kube-proxy·coredns·eks-pod-identity-agent·metrics-server Running
kubectl get daemonset aws-node -n kube-system -o jsonpath='{.spec.template.spec.containers[*].name}'   # aws-network-policy-agent 포함
kubectl top nodes                                                # metrics-server 동작 (기동 후 1~2분)
```

### 4-3. envs/staging-k8s (클러스터 안)

처음 한 번 차트 버전을 확인해서 `staging-k8s.tfvars`의 `lbc_chart_version`에 적는다(`CHANGE_ME`면 plan이 멈춘다).

```bash
helm repo add eks https://aws.github.io/eks-charts && helm repo update
helm search repo eks/aws-load-balancer-controller --versions | head -5     # CHART VERSION 열

cd ../staging-k8s
terraform init -backend-config=backend.hcl       # 같은 버킷, 키 staging-k8s/terraform.tfstate
terraform fmt -recursive && terraform validate
terraform plan -var-file=staging-k8s.tfvars -out=plan.out
# 기대: namespace 2 · Role 2 · RoleBinding 2 · helm_release 1. destroy 0
terraform apply plan.out                          # 2~3분
```

- 이 폴더는 CI `infra-plan` 대상이 아니다(plan 역할의 state 권한이 `staging/*`뿐). PR에는 로컬 plan 결과를 붙인다

### 4-4. 확인

```bash
# LB Controller
kubectl get deploy aws-load-balancer-controller -n kube-system           # 1/1
kubectl logs -n kube-system deploy/aws-load-balancer-controller | grep -i -m3 "error\|credential"   # 비어 있어야 함
aws eks list-pod-identity-associations --cluster-name cinema-staging-eks  # kube-system/aws-load-balancer-controller

# HPA 동작 (metrics-server) — 임시 확인 후 바로 지운다. 관리자만, 앱 namespace가 아닌 default에서
kubectl create deploy hpa-smoke --image=registry.k8s.io/pause:3.10 -n default
kubectl set resources deploy hpa-smoke -n default --requests=cpu=10m
kubectl autoscale deploy hpa-smoke -n default --cpu-percent=70 --min=1 --max=2
kubectl get hpa hpa-smoke -n default        # 1분 안에 TARGETS가 <unknown>이 아니라 0%/70%
kubectl delete hpa,deploy hpa-smoke -n default
```

팀원(각자, `cinema-dev`). **한 줄씩 따로** 실행한다(여러 명령을 한 줄로 붙여 넣으면 `Unknown options` 오류). kubectl은 MFA를 묻지 못하므로 먼저 `get-caller-identity`로 세션을 만든다:

```bash
aws sts get-caller-identity --profile cinema-dev                 # MFA 입력 → assumed-role/cinema-developer
aws eks update-kubeconfig --region ap-northeast-2 --name cinema-staging-eks --profile cinema-dev --alias cinema-dev --user-alias cinema-dev
kubectl config use-context cinema-dev
kubectl get pods -n cinema-staging                               # 성공 (아직 비어 있음: No resources found)
kubectl auth can-i create pods/portforward -n cinema-staging     # yes
kubectl auth can-i get secrets -n cinema-staging                 # no
kubectl auth can-i delete pods -n cinema-staging                 # no
kubectl get pods -n kube-system                                  # Forbidden
```

- [ ] 노드 2대 Ready(a·c), 시스템 파드 Running, `kubectl top nodes` 값 나옴
- [ ] LB Controller 1/1, 로그에 자격 증명 오류 없음
- [ ] hpa-smoke TARGETS 숫자로 표시 → 지움
- [ ] 4명 `cinema-dev`로 위 결과 일치
- [ ] 두 번째 plan(staging·staging-k8s) No changes. 노드 수를 바꾼 뒤에도 desired_size는 무시돼 No changes

### 4-5. 주의

- 노드 0대로 줄일 때(밤·주말)는 `aws eks update-nodegroup-config --scaling-config desiredSize=0`(3.1.14 워크플로). Terraform은 desired_size 변경을 무시한다
- 삭제 순서: ArgoCD 앱(Ingress → ALB 삭제) → `staging-k8s` destroy → `staging` destroy. LB Controller를 먼저 지우면 ALB·대상 그룹이 남는다
- 버전 업그레이드는 한 단계씩(1.36 → 1.37): `eks_version` 변경 → plan(컨트롤 플레인 → 애드온 → 노드그룹 순 교체) → 데모 주간 금지

### 4-6. API 공개 엔드포인트 허용 IP

EKS API(kubectl·helm)는 팀 공인 IP 대역에서만 열린다(`eks_public_access_cidrs`, 전체 공개 금지). 공인 IP는 커밋하지 않는다.

| 쓰는 곳 | 값을 두는 곳 |
| --- | --- |
| 로컬 apply·plan (관리자) | `envs/staging/eks_access.auto.tfvars` (`.example` 복사, 커밋 안 됨) |
| CI `infra-plan` | GitHub Secret `EKS_PUBLIC_ACCESS_CIDRS` = `["203.0.113.10/32","198.51.100.7/32"]` (JSON 배열) |

- 내 공인 IP 확인: `curl -s https://checkip.amazonaws.com`
- IP가 바뀌거나 팀원이 다른 곳에서 접속할 때: 두 곳을 같이 고치고 `terraform apply`(1~2분, 엔드포인트 재생성 없음). 증상은 `kubectl` 타임아웃(`dial tcp ... i/o timeout`)
- plan 출력에는 `(sensitive value)`로 가려진다 → PR 코멘트에 IP가 남지 않는다
- GitHub Actions 대역은 열지 않는다: `infra-plan`(AWS API)·`cluster-schedule`(EKS 관리 API)은 클러스터 API를 부르지 않는다. 나중에 Actions에서 kubectl이 필요해지면 그때 다시 결정

## 5. staging 3) RDS·Valkey (3.1.3)

코드: `envs/staging/{rds,valkey,alerts}.tf` + `global/alerts.tf`(SNS `cinema-alerts`) · 로컬 짝: `infra/compose/docker-compose.yml`

| 항목 | 값 |
| --- | --- |
| RDS | `cinema-staging-mysql`, MySQL 8.4, `db.t4g.small`, gp3 20GB(자동 확장 50GB), 암호화, Single-AZ(AZ a), 퍼블릭 접근 없음, 백업 7일(03:00 KST), 마이너 자동 업그레이드 끔 |
| RDS 파라미터 그룹 | `cinema-staging-mysql84`: `time_zone=UTC`, `character_set_server=utf8mb4`, `collation_server=utf8mb4_0900_ai_ci`, `require_secure_transport=1`, 느린 쿼리 0.5초 → CloudWatch Logs 7일(error·slowquery) |
| 마스터 계정 | `cinema_master`, 비밀번호는 RDS 관리(Secrets Manager, 7일 자동 교체). **tfstate·tfvars에 없음**. 앱·Flyway는 사용 안 함 |
| Valkey | `cinema-staging-valkey`, 8.2, `cache.t4g.micro` 1노드(AZ a), TLS 필수·저장 암호화, AUTH 없음, 스냅샷 없음 |
| Valkey 파라미터 그룹 | `cinema-staging-valkey8`: `maxmemory-policy=noeviction`, `notify-keyspace-events=Ex` |
| TTL 없는 키 | `hold-expiry`(sorted set)·`seat-seq:{회차}`(booking). `noeviction`이라 지워지지 않는다. 인프라가 따로 할 일 없음 — 정리는 앱(`ZREM`), 커지면 메모리 80% 경보로 드러남 |
| 경보 → `cinema-alerts` | Valkey 메모리 80%·Evictions > 0, RDS CPU 80%(15분)·연결 130·남은 저장소 2GB |
| 접근 | RDS 3306·Valkey 6379는 노드 SG에서만. 노트북에서는 닿지 않는다 → 확인·계정 생성은 클러스터 안에서 |

### 5-0. 사전 조건

- [ ] **브랜치에 main 반영**: `global/alerts.tf`가 3.1.14의 `budget_emails` 변수를 쓴다. 3.1.14가 main에 머지된 뒤 이 브랜치를 갱신하지 않으면 `Reference to undeclared input variable "budget_emails"`
  ```bash
  git fetch origin
  git switch infra/3.1.3-rds-valkey
  git merge origin/main          # 충돌 나면 해결 후 git commit, 그다음 git push
  ```
  - 이 브랜치의 `eks.tf`가 3.1.2 리뷰 수정(애드온 버전 고정·`kms_key_administrators`) 전 상태라면 merge로 함께 들어온다. merge 없이 plan하면 애드온·KMS 정책 변경이 같이 보이니 멈춘다
- [ ] `global/budget.auto.tfvars` 있음(8-1-1, 커밋 안 됨). SNS 구독 메일도 같은 `budget_emails`를 쓴다
- [ ] `export AWS_PROFILE=cinema-admin` · `aws sts get-caller-identity` → `assumed-role/cinema-admin`
- [ ] 클러스터 안 확인(5-3 아래쪽)을 같은 날 할 거면 노드가 켜져 있는지(`infra/scripts/cluster-scale.sh status`)

### 5-1. global 먼저 (SNS 주제)

staging은 `data "aws_sns_topic" "alerts"`로 주제를 찾는다 → **global이 먼저**다(순서가 바뀌면 staging plan이 `no matching SNS topic`으로 멈춘다).

```bash
cd infra/terraform/global
terraform init -backend-config=backend.hcl        # main 반영 뒤 처음이면
terraform fmt -recursive && terraform validate
terraform plan -var-file=global.tfvars -out=plan.out   # 기대: SNS 주제 1 + 구독(메일 수). destroy 0
terraform apply plan.out
```

- 메일마다 "AWS Notification - Subscription Confirmation"이 온다 → **Confirm subscription**. 안 누르면 경보가 안 온다(스팸함 확인)
- 확인: `aws sns list-subscriptions-by-topic --topic-arn $(aws sns list-topics --query "Topics[?ends_with(TopicArn,':cinema-alerts')].TopicArn" --output text) --query 'Subscriptions[].SubscriptionArn' --output text` → `PendingConfirmation`이 없어야 함 (메일 주소는 화면에만, 문서·PR에 붙이지 않는다)

### 5-2. staging

```bash
cd ../envs/staging
terraform fmt -recursive && terraform validate
terraform plan -var-file=staging.tfvars -out=plan.out
# 기대: DB 파라미터 그룹 1 · RDS 1 · 로그 그룹 2 · ElastiCache 서브넷 그룹 1 · 파라미터 그룹 1 · 복제 그룹 1 · 경보 5. destroy 0
#       EKS·애드온·KMS 쪽 변경이 보이면 멈춘다 (5-0 main 반영 확인)
terraform apply plan.out      # RDS 10~15분, Valkey 10~15분 (동시에 만들어짐)
terraform output rds_endpoint
terraform output valkey_primary_endpoint
```

- 엔드포인트·포트는 비밀이 아니다(VPC 밖에서 닿지 않음). 그래도 PR·노션에는 `<rds_endpoint>`처럼 자리만 적는다(공개 레포, 계정 ID가 섞이지 않게)
- `rds_master_secret_arn`은 3.1.12 Job만 쓴다. 값(비밀번호)은 꺼내 보지 않는다

### 5-3. 확인

AWS API로 (노트북에서):

```bash
# RDS 상태·버전·Single-AZ·퍼블릭·파라미터 적용
aws rds describe-db-instances --db-instance-identifier cinema-staging-mysql \
  --query 'DBInstances[0].[DBInstanceStatus,EngineVersion,MultiAZ,PubliclyAccessible,DBParameterGroups[0].ParameterApplyStatus,MasterUserSecret.SecretStatus]'
#   available · 8.4.x · False · False · in-sync · active      (pending-reboot이면 재시작 필요 → 5-4)

# RDS time_zone = UTC 외 핵심 파라미터
aws rds describe-db-parameters --db-parameter-group-name cinema-staging-mysql84 \
  --query "Parameters[?contains(['time_zone','character_set_server','collation_server','require_secure_transport'],ParameterName)].[ParameterName,ParameterValue]" \
  --output table
#   time_zone UTC · utf8mb4 · utf8mb4_0900_ai_ci · 1

# Valkey 상태·암호화
aws elasticache describe-replication-groups --replication-group-id cinema-staging-valkey \
  --query 'ReplicationGroups[0].[Status,TransitEncryptionEnabled,AtRestEncryptionEnabled]'
#   available · True · True

# Valkey 파라미터 (ElastiCache는 CONFIG 명령을 막으므로 API로 본다)
aws elasticache describe-cache-parameters --cache-parameter-group-name cinema-staging-valkey8 --source user \
  --query 'Parameters[].[ParameterName,ParameterValue]' --output table
#   maxmemory-policy noeviction · notify-keyspace-events Ex

# 경보
aws cloudwatch describe-alarms --alarm-name-prefix cinema-staging- --query 'MetricAlarms[].[AlarmName,StateValue]' --output table
```

클러스터 안에서 접속 확인 (노드가 켜져 있을 때, 관리자만, `default` namespace — 확인 후 `--rm`으로 바로 지워진다):

```bash
# Valkey: TLS로 PING → PONG
kubectl run valkey-check -n default --rm -it --restart=Never --image=valkey/valkey:8.2 -- \
  valkey-cli -h <valkey_primary_endpoint> --tls PING

# MySQL: 네트워크·TLS 확인 (계정 없이 접속 시도 → Access denied면 연결 자체는 정상)
kubectl run mysql-check -n default --rm -it --restart=Never --image=mysql:8.4 -- \
  mysql -h <rds_endpoint> -u nobody --ssl-mode=REQUIRED -e 'select 1'
#   ERROR 1045 (28000): Access denied for user 'nobody'   ← 정상
#   ERROR 2002/2003 (연결 실패·타임아웃)                    ← SG·서브넷 문제
```

- DB 안에서 보는 확인(`SELECT @@global.time_zone, NOW(), UTC_TIMESTAMP();` → `UTC`, 두 시각 같음)은 계정이 생기는 **3.1.12 Job**에서 같이 한다. 파라미터 그룹 확인(위)으로 3.1.3 완료 판단은 충분하다
- `kubectl run`은 GitOps 예외인 일회성 점검이다. 관리자만, 앱 namespace에서는 하지 않는다

체크:

- [ ] RDS available·8.4·Single-AZ·퍼블릭 False·파라미터 in-sync·마스터 Secret active
- [ ] `time_zone=UTC`, utf8mb4, `require_secure_transport=1`
- [ ] Valkey available·TLS·저장 암호화, `noeviction`·`Ex`
- [ ] 경보 5개 `OK` 또는 `INSUFFICIENT_DATA`(데이터 전이면 정상), 구독 Confirm 완료(`PendingConfirmation` 없음)
- [ ] 클러스터에서 Valkey `PONG`, MySQL `Access denied`
- [ ] 두 번째 plan(global·staging) No changes
- [ ] 14장 실행 기록 + 노션 3.1.3 작업 기록에 결과(엔드포인트 값은 적지 않음)

### 5-4. 로컬 Compose와 맞추기

staging과 같은 상황에서 같은 동작이 나오게 로컬도 같은 설정을 쓴다.

| 설정 | staging | 로컬 Compose |
| --- | --- | --- |
| MySQL 시간대 | 파라미터 `time_zone=UTC` | `--default-time-zone=+00:00` |
| Valkey 삭제 정책 | `noeviction` | `--maxmemory-policy noeviction` |
| Valkey 만료 알림 | `notify-keyspace-events Ex` | 같음 |

Compose 설정이 바뀌면 각자 컨테이너를 다시 만든다(데이터 볼륨은 유지):

```bash
docker compose -f infra/compose/docker-compose.yml up -d --force-recreate mysql valkey
docker compose -f infra/compose/docker-compose.yml exec mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SELECT @@global.time_zone, NOW(), UTC_TIMESTAMP();"'   # 비밀번호는 Compose 환경변수에서
#   +00:00 · 두 시각이 같으면 OK
docker compose -f infra/compose/docker-compose.yml exec valkey valkey-cli CONFIG GET maxmemory-policy
#   noeviction
```

### 5-5. 주의

- 마스터 비밀번호는 **3.1.12 DB 계정 생성 Job만** 읽는다(Secrets Manager `rds_master_secret_arn`). RDS는 VPC 밖(노트북)에서 닿지 않으므로 스키마 4개·`{svc}_app`·`{svc}_migrator` 계정은 클러스터 안에서 만든다
- 3.1.12부터 서비스 계정 비밀번호는 Terraform `random_password`로 만들어 **tfstate에 평문으로** 들어간다 → tfstate 읽기는 관리자·plan 역할만(2-6)
- 인스턴스 클래스·파라미터 변경은 `apply_immediately = true`라 바로 적용되고, 일부(클래스 변경·static 파라미터)는 **재시작**된다. 데모 주간 금지. `pending-reboot`이 남으면 `aws rds reboot-db-instance --db-instance-identifier cinema-staging-mysql`(관리자, 팀 공지 후)
- RDS·Valkey는 노드를 0대로 꺼도 계속 켜져 있다(비용 계속 발생, 경보도 계속 동작)
- destroy 시 최종 스냅샷은 만들지 않는다(`skip_final_snapshot`). 자동 백업은 보존 기간(7일) 동안 남는다 — 복원: `aws rds restore-db-instance-to-point-in-time`
- Valkey 메모리가 부하 테스트에서 70%를 넘으면 `valkey_node_type = "cache.t4g.small"` (재생성 없이 변경, 잠깐 끊김). `noeviction`이라 가득 차면 선점 쓰기가 `OOM` 오류로 실패한다
- Evictions 경보가 울리면 파라미터 그룹이 적용되지 않은 것 → 5-3 `describe-cache-parameters`로 확인

## 6. staging 4) ECR·SQS·Lambda (3.1.4·3.1.8·3.1.9)

### 6-A. ECR·build-push (3.1.4)

코드: `envs/staging/ecr.tf` · `.github/workflows/build-push.yml` · `services/*/Dockerfile` · 역할은 이미 있는 `global/github_oidc.tf`의 `cinema-gha-ecr-push`(main에서만)

| 항목 | 값 |
| --- | --- |
| 저장소 | `cinema/{gateway,catalog,booking,user,payment}`, 태그 **IMMUTABLE**, push 때 기본 스캔, AES256 |
| 저장소 정책 | push 계열 4개 API(`PutImage`·`InitiateLayerUpload`·`UploadLayerPart`·`CompleteLayerUpload`)를 `cinema-gha-ecr-push` 외 **모두 Deny**(관리자 포함). pull은 같은 계정 노드 역할이라 정책 불필요 |
| 수명 주기 | 태그 없는 이미지 1일 · 저장소당 최근 30개만 |
| `force_delete` | `true` — 11장 재생성 리허설에서 destroy가 이미지 때문에 멈추지 않게. 지운 뒤에는 `build-push` 수동 실행 `services=all` |
| 태그 | git SHA 7자리. 같은 태그가 이미 있으면 빌드·push를 건너뜀(재실행 안전) |
| Trivy | 0.69.3 고정(0.69.4는 2026-03 공급망 사고 버전). HIGH 이상은 실행 요약에 표, **CRITICAL + 수정 버전 있음**이면 실패 |
| gitops 커밋 | `overlays/staging/{svc}/image-tag/kustomization.yaml`의 `newTag` **하나만** yq로 바꿔 GitHub App `seatsac-build-push` 토큰으로 main에 직접 커밋(`deploy(staging): {svc} → {tag}`). gitops가 Public이라 레지스트리(계정 ID)는 쓰지 않는다 — `newName`이 있으면 실패. 레지스트리는 Terraform ArgoCD Application이 붙인다(6-C). `kustomize build`가 깨지면 커밋하지 않는다 |
| 블루/그린 동시 금지 | booking·gateway가 한 머지에 같이 바뀌면 booking만 커밋 → booking 전환 뒤 수동 실행 `services=gateway` |

#### 6-A-0. PR 이미지 검사 (`pr-check` → `image`)

| 단계 | 하는 일 |
| --- | --- |
| `image-changes` | PR base와 비교해 `services/{svc}/**`가 바뀐 서비스만. `build-logic/`·`gradle/`·`settings.gradle.kts`·`build.gradle.kts`·`.github/scripts/`·`pr-check.yml`·`build-push.yml`이 바뀌면 5개 전부 |
| `image-build` (서비스마다) | bootJar → `docker build`(x86) → `USER`가 숫자 UID(0 아님)인지 → Trivy(`build-push`와 같은 `.github/scripts/trivy-scan.sh`). **push 안 함, AWS 권한 없음** |
| `image` | 위 결과를 모아 성공·실패 하나로. 대상이 없으면 성공 → **이 이름을 SeatSAC ruleset 필수 검사에 추가** |

- 실패하면 같은 PR 브랜치에 고쳐서 push → 자동 재실행(PR을 다시 만들 필요 없음)
- 여기서 만든 이미지는 버린다. 배포 이미지는 머지 뒤 `build-push`가 main 커밋으로 다시 만든다(태그 = main SHA)
- 필수 검사 등록: SeatSAC → Settings → Rules → main ruleset → Require status checks → `image` 추가 (한 번 실행된 뒤에 목록에 뜬다)

#### 6-A-1. envs/staging apply (ECR)

```bash
# 터미널 B (0장 Terraform 자격 증명)
cd infra/terraform/envs/staging
terraform plan -var-file=staging.tfvars -out=plan.out   # 예상: ECR 저장소 5 + 수명 주기 5 + 저장소 정책 5 = 15 add, 다른 변경 없음
terraform apply plan.out
terraform output ecr_repository_urls
```

#### 6-A-2. GitHub 설정 (SeatSAC 레포)

| 종류 | 이름 | 값 | 비고 |
| --- | --- | --- | --- |
| Secret | `AWS_ECR_PUSH_ROLE_ARN` | global 출력 `github_secrets.AWS_ECR_PUSH_ROLE_ARN` | 2-4에서 이미 넣었으면 그대로 |
| Variable | `GITOPS_APP_ID` | GitHub App `seatsac-build-push`의 **App ID**(숫자, 앱 General 맨 위. Client ID 아님) | 공개 값 |
| **Secret** | `GITOPS_APP_PRIVATE_KEY` | 앱 **Private key**(`.pem`) 내용 전체(`-----BEGIN …` ~ `-----END …`) | **Variable에 넣지 않는다**(Variable은 로그에 가려지지 않는다). 없으면 gitops 단계만 경고 후 건너뜀(ECR push까지는 됨) |
| Variable | `GITOPS_REPO_NAME` (선택) | `SeatSAC-gitops` | 비우면 이 값. 조직은 이 레포와 같다 |

#### 6-A-3. SeatSAC-gitops 쓰기 권한 = GitHub App `seatsac-build-push`

조직 정책으로 **Deploy key는 쓸 수 없다**("Some settings on this page can't be changed because of a sesac-mzc-final-2 policy"). GitHub App은 사람 계정에 묶이지 않고, 레포 하나·권한 하나로 좁히고, 실행마다 1시간짜리 토큰을 받는다.

| 단계 | 어디서 | 값 |
| --- | --- | --- |
| 1. 앱 만들기 (Owner) | 조직 Settings → Developer settings → GitHub Apps → **New GitHub App** | 이름 `seatsac-build-push` · Homepage URL `https://github.com/sesac-mzc-final-2/SeatSAC` · Callback·Setup URL 비움 · OAuth during installation 끔 · Device Flow 끔 · **Webhook Active 끔** · Repository permissions **Contents: Read and write**만(Metadata 읽기 자동) · 나머지 No access · **Only on this account** |
| 2. 키 받기 | 앱 General 페이지 **맨 아래 Private keys** → Generate a private key | `.pem`이 브라우저 다운로드 폴더에 한 번만 받아진다(못 받았으면 생긴 키를 Delete하고 다시 Generate) |
| 3. 설치 | 앱 왼쪽 **Install App** → `sesac-mzc-final-2` Install → **Only select repositories → SeatSAC-gitops** | SeatSAC 등 다른 레포는 고르지 않는다 |
| 4. SeatSAC에 등록 | SeatSAC → Settings → Secrets and variables → Actions | 6-A-2 표: Variable `GITOPS_APP_ID`, **Secret** `GITOPS_APP_PRIVATE_KEY` |
| 5. 키 파일 지우기 | 로컬 | 다운로드 폴더의 `.pem` 삭제(휴지통 비우기). 레포 폴더·노션·메신저에 두지 않는다 |
| 6. ruleset 우회 | **SeatSAC-gitops** → Settings → Rules → main ruleset → Bypass list → Add bypass → **Apps → `seatsac-build-push`** | 사람은 계속 PR만, 앱만 태그 커밋 직접 push |

- 워크플로는 `actions/create-github-app-token`으로 **SeatSAC-gitops에만 쓰는 설치 토큰**(1시간)을 받아 checkout·push한다. 커밋 작성자는 `seatsac-build-push[bot]`
- 키 교체: 앱 Private keys에서 새로 Generate → Secret 덮어쓰기 → 옛 키 Delete. 키가 Variable·로그·문서에 노출됐으면 **바로 교체**
- ArgoCD는 SeatSAC-gitops를 **HTTPS로 읽는다**(Public이라 레포 자격 증명 없음, 6-C). 이 앱은 build-push 쓰기 전용이다

#### 6-A-4. 첫 실행 (수동)

1. Actions → `build-push` → Run workflow (branch **main**) → `services=all`, `update_gitops`는 gitops 레포·앱(6-A-3)이 준비됐으면 켬
2. 확인

```bash
# 터미널 A (cinema-dev도 조회 가능)
for s in gateway catalog booking user payment; do
  aws ecr describe-images --repository-name cinema/$s --query 'imageDetails[].imageTags' --output text
done
```

3. 사람이 push하면 막히는지 (관리자도 거부가 정상)

```bash
aws ecr get-login-password --profile cinema-admin | docker login --username AWS --password-stdin <레지스트리>
docker push <레지스트리>/cinema/booking:test   # denied ... explicit deny in a resource-based policy

# Docker 없이 (API 하나만 호출)
aws ecr initiate-layer-upload --repository-name cinema/booking --profile cinema-admin
# AccessDeniedException ... with an explicit deny in a resource-based policy   ← 정상
```

#### 6-A-5. 확인

| 항목 | 기대 |
| --- | --- |
| 두 번째 plan | No changes |
| 수동 실행 `services=all` | 5개 push, 요약에 Trivy 표 |
| 같은 커밋으로 재실행 | "이미 있음 → 건너뜀" |
| 서비스 하나만 바꾼 PR 머지 | 그 서비스만 빌드, gitops에 `deploy(staging): {svc} → {sha7}` 커밋 1개 |
| `libs/`만 바꾼 PR 머지 | `build-push`가 돌지 않음 (경로 필터 `services/**`) |
| 사람 `docker push` | explicit deny |

#### 6-A-6. 주의

- `build-push`는 **main에서만** 실행된다(역할 신뢰 정책). 다른 브랜치에서 Run workflow 하면 첫 단계에서 멈춘다
- 롤백은 SeatSAC-gitops에서 태그 커밋을 revert하는 PR. ECR 이미지는 지우지 않는다(최근 30개 유지)
- Trivy가 CRITICAL로 막으면: 베이스 이미지(`eclipse-temurin:21-jre`) 갱신이나 의존성 버전 업으로 고친다. 당장 고칠 수 없는 오탐만 레포 루트 `.trivyignore`에 CVE ID + 이유 주석(PR 리뷰)
- Trivy가 알려 준 "수정 버전"이 Maven Central에 아직 없으면(릴리스 투표 실패 등, 10/25 tomcat 10.1.58) 빌드가 `Could not find …`로 깨진다 → 실제로 배포된 다음 버전으로 올린다(`https://repo1.maven.org/maven2/<그룹 경로>/<이름>/`에서 확인)
- 레포가 Public이라 Actions 로그도 공개된다. 워크플로가 계정 ID를 가리지만(`mask-aws-account-id`), 실행 로그·요약을 노션·PR에 붙일 때 레지스트리 주소가 없는지 한 번 더 본다

### 6-B. SQS 알림 큐·DLQ·booking Pod Identity (3.1.9, OPS-07) · Lambda (3.1.8) — ⬜

코드: `envs/staging/sqs.tf`(큐·DLQ·큐 정책·Pod Identity·경보 2개) · `variables.tf`(`app_namespace`) · `outputs.tf` · SeatSAC-gitops `base/booking/serviceaccount.yaml`·`rollout.yaml`(`serviceAccountName: booking`). 큐 URL은 이미 ConfigMap `cinema-infra-endpoints`(`SQS_NOTIFICATION_URL`, 6-C-1)에 있다 → `envs/staging-k8s` 변경 없음

| 만드는 것 | 이름·값 |
| --- | --- |
| 알림 큐 | `cinema-staging-notification-events` · 보존 7일(DLQ 14일보다 짧게) · 가시성 제한 180초(Lambda 30초 × 6) · 긴 폴링 20초 · SSE-SQS |
| DLQ | `cinema-staging-notification-events-dlq` · `maxReceiveCount 3` · 보존 14일 · 알림 큐에서만 받음(redrive allow) |
| 큐 정책 | 두 큐 모두 TLS가 아닌 요청 거부 |
| Pod Identity | 역할 `cinema-staging-booking-pod`(이름 그대로, 난수 접미사 없음) → `sqs:SendMessage`·`sqs:GetQueueUrl` on 알림 큐만. 연결 `cinema-staging` / ServiceAccount `booking` |
| 경보 2개 → SNS `cinema-alerts` → 메일 | `cinema-staging-notification-dlq-not-empty`: DLQ 메시지 > 0 (같은 메시지를 SQS가 3번 내줬는데 삭제되지 않음 — 보통 Lambda 실패) · `cinema-staging-notification-oldest-2d`: 알림 큐의 가장 오래된 메시지 > 2일 (받는 쪽이 없음 — DLQ로 안 가고 7일 뒤 삭제되는 경우) |
| 지표 유지 | EventBridge Scheduler `cinema-staging-sqs-metrics/keep-active-{notification,dlq}`: 1시간마다 `GetQueueAttributes` — SQS는 6시간 넘게 호출이 없는 큐의 지표를 끊어 경보가 "데이터 없음"으로 조용해지므로 |

**순서**: ① SeatSAC PR 머지 → `envs/staging` apply(연결이 먼저 있어야 한다) → ② gitops PR 머지(ServiceAccount) → booking 새 파드에 자격 증명 주입. 순서가 바뀌면 파드가 자격 증명 없이 뜬다 → ArgoCD에서 booking **Restart**

#### 6-B-1. apply (3.1.9)

```bash
# 터미널 B (0장 Terraform 자격 증명). 최신 main에서
cd infra/terraform/envs/staging
git pull && git log -1 --oneline
terraform init -backend-config=backend.hcl
terraform plan -var-file=staging.tfvars -out=plan.out
#   기대: aws_sqs_queue 2 · aws_sqs_queue_redrive_allow_policy 1 · aws_sqs_queue_policy 2 · aws_cloudwatch_metric_alarm 2
#         module.booking_pod_identity (IAM 역할·정책·연결 등 4) · 지표 유지 Scheduler(그룹 1·역할 1·정책 1·일정 2)
#         → 약 16 to add, 0 change, 0 destroy
terraform apply plan.out && rm -f plan.out     # 저장된 plan에는 비밀 값이 평문
terraform output -raw booking_pod_role_arn | sed -E 's/[0-9]{12}/<ACCOUNT_ID>/'
```

- plan에 노드그룹 `release_version` 변경이 같이 보이면 3.1.14 때와 같은 AMI 갱신이다(노드 0대일 때 apply가 안전, 13장)
- 큐 URL·ARN 출력에는 계정 ID가 들어 있다 → PR·노션에는 `<ACCOUNT_ID>`로 가린다

#### 6-B-2. 확인

```bash
# 터미널 A (cinema-admin)
Q=cinema-staging-notification-events
URL=$(aws sqs get-queue-url --queue-name $Q --query QueueUrl --output text)
aws sqs get-queue-attributes --queue-url "$URL" \
  --attribute-names RedrivePolicy SqsManagedSseEnabled VisibilityTimeout MessageRetentionPeriod \
  --query 'Attributes' --output json | sed -E 's/[0-9]{12}/<ACCOUNT_ID>/g'
#   기대: maxReceiveCount 3 · …-dlq · SqsManagedSseEnabled true · 180 · 604800
aws eks list-pod-identity-associations --cluster-name cinema-staging-eks --namespace cinema-staging \
  --query 'associations[].[serviceAccount,associationId]' --output text       # booking 1줄
aws cloudwatch describe-alarms --alarm-names cinema-staging-notification-dlq-not-empty cinema-staging-notification-oldest-2d \
  --query 'MetricAlarms[].[AlarmName,StateValue]' --output text              # 둘 다 OK 또는 INSUFFICIENT_DATA
aws iam get-role --role-name cinema-staging-booking-pod --query 'Role.RoleName' --output text   # 정확한 이름(접미사 없음)
aws scheduler list-schedules --group-name cinema-staging-sqs-metrics --query 'Schedules[].[Name,State]' --output text   # keep-active-* 2개 ENABLED

# gitops PR 머지 뒤(booking 새 파드) — Pod Identity가 주입됐는지 (값은 출력하지 않는다)
P=$(kubectl -n cinema-staging get pods -l app.kubernetes.io/name=booking -o jsonpath='{.items[*].metadata.name}'); P=${P%% *}
kubectl -n cinema-staging get pod "$P" -o jsonpath='{.spec.serviceAccountName}{"\n"}'          # booking
kubectl -n cinema-staging get pod "$P" -o jsonpath='{range .spec.containers[0].env[*]}{.name}{"\n"}{end}' \
  | grep AWS_CONTAINER                                                                         # …_FULL_URI · …_AUTHORIZATION_TOKEN_FILE
```

- gitops PR은 booking 파드 템플릿을 바꾸므로 **블루/그린 배포 + 분석**(6-D-2)이 한 번 돈다 → 6-D-2 첫 확인을 같이 해도 된다
- 메시지가 실제로 가는지는 릴레이를 켠 뒤(6-B-3)에 본다. 그 전에는 큐가 비어 있는 게 정상

#### 6-B-3. 아웃박스 릴레이 켜기 (3.2.6, 남궁일 — SeatSAC #39 머지 · 알림 Lambda 3.1.8 연결 뒤)

릴레이 코드는 `APP_OUTBOX_RELAY_ENABLED`가 `true`일 때만 돈다(기본 `false`). **켜는 조건: 6-B-1·2 확인 + 알림 Lambda(3.1.8) 이벤트 소스 연결 완료**

- 꺼 두는 동안 확정된 예매는 아웃박스에 `PENDING`으로 남는다(DB라 사라지지 않음) → Lambda가 붙은 뒤 켜면 순서대로 보내진다. **기다리는 쪽이 안전하다**
- Lambda 전에 켜야 하면(3.2.6 통합 확인 등): 메시지는 큐에 쌓이기만 하고 받는 쪽이 없어 **DLQ로도 가지 않는다** → 보존 7일이 지나면 SQS가 조용히 지운다. 2일째에 `…-notification-oldest-2d` 경보가 오니 그 전에 Lambda를 붙이거나 릴레이를 다시 끈다
- 큐에서 사라진 경우의 복구: 아웃박스 행은 `SENT`로 남아 있다(릴레이가 지우지 않음) → 그 기간 행을 `PENDING`으로 되돌리면 같은 `eventId`(= `message_key`)로 다시 보내진다. DB 손 작업이라 남궁일과 함께(`UPDATE booking_db.outbox_message SET status='PENDING', attempt_count=0, next_attempt_at=:now WHERE status='SENT' AND sent_at BETWEEN :from AND :to`), Lambda는 `eventId`로 중복을 무시한다

```yaml
# SeatSAC-gitops overlays/staging/booking/kustomization.yaml  booking-config literals에 추가
      - APP_OUTBOX_RELAY_ENABLED=true
```

```bash
# 켠 뒤: 예매를 하나 확정하고(BKG-10) 1~2초 뒤
kubectl -n cinema-staging port-forward svc/booking 18082:8080 &
curl -s localhost:18082/actuator/prometheus | grep -E '^booking_(notification_publish_total|outbox_pending)'   # sent 증가 · pending 0
kill %1
aws sqs get-queue-attributes --queue-url "$URL" --attribute-names ApproximateNumberOfMessages \
  --query 'Attributes.ApproximateNumberOfMessages' --output text                   # Lambda(3.1.8) 전에는 확정 수만큼 쌓인다
```

- 꺼져 있던 동안 확정된 예매는 아웃박스에 `PENDING`으로 쌓여 있다가 켜는 순간 보내진다(`booking_outbox_pending`이 0으로)
- Lambda(3.1.8)가 붙기 전에 켰다면 큐에 쌓이기만 한다. **큐를 비우지(purge) 않는다** — 보존 7일 안에 Lambda가 붙으면 그때 처리된다(넘기면 위 복구)

#### 6-B-4. 문제 해결

| 증상 | 원인 | 해결 |
| --- | --- | --- |
| booking 로그 `AccessDenied … sqs:SendMessage` | Pod Identity 연결의 ServiceAccount·namespace가 파드와 다름, 큐 ARN이 다름 | `list-pod-identity-associations` vs `kubectl get pod … serviceAccountName`. 고치면 booking **Restart** |
| `Unable to load credentials` · 파드 env에 `AWS_CONTAINER_*`가 없음 | 연결보다 파드가 먼저 뜸(웹훅은 파드 생성 때만 주입) 또는 `serviceAccountName` 없음 | apply 확인 후 ArgoCD에서 booking **Restart** |
| `booking_notification_publish_total{result="retry"}` 계속 증가 | 큐 URL 오타·큐 없음·권한 | 로그의 오류 메시지, ConfigMap `SQS_NOTIFICATION_URL`(6-C-1) |
| 아웃박스 행이 `FAILED` | 10회 재시도 실패(릴레이를 큐보다 먼저 켬 등) | 원인을 고친 뒤 남궁일과 `FAILED` → `PENDING` 되돌림(손 작업, 3.2.6 런북) |
| DLQ 경보 메일 | 같은 메시지가 3번 수신됐는데 삭제되지 않음: Lambda 실패·제한 시간 초과, 처리가 가시성 제한(180초)보다 김, 사람이 콘솔·CLI로 수신해 봄 | DLQ 메시지의 수신 횟수(`aws sqs receive-message --queue-url <DLQ URL> --attribute-names ApproximateReceiveCount ApproximateFirstReceiveTimestamp --visibility-timeout 0`)와 Lambda 로그(CloudWatch)의 `eventId`·오류를 함께 본다 → 고친 뒤 `aws sqs start-message-move-task --source-arn <DLQ ARN>`으로 DLQ → 알림 큐 재전송 |
| `…-notification-oldest-2d` 경보 메일 | 받는 쪽 없음: Lambda 연결 전에 릴레이를 켬, 이벤트 소스 매핑 꺼짐·오류 | Lambda 연결(3.1.8) 또는 릴레이 끄기(`APP_OUTBOX_RELAY_ENABLED=false` gitops PR). 7일을 넘겼으면 6-B-3 복구 |

- 삭제(11장): 큐를 지우면 남은 메시지도 사라진다. 다시 만들면 URL이 같으므로 앱 설정은 그대로다


### 6-C. gitops Public 준비: 엔드포인트 ConfigMap · 서비스 ArgoCD Application (ADR-74)

SeatSAC-gitops는 Public이다(무료 플랜에서 ruleset을 쓰려면 Public이어야 한다). 그래서 **계정 ID·엔드포인트를 gitops에 두지 않고** 둘 다 `envs/staging-k8s`가 클러스터에 넣는다.

| 무엇 | 어디서 | gitops에는 |
| --- | --- | --- |
| RDS·Valkey·SQS·Cognito 주소 | ConfigMap `cinema-infra-endpoints`(`cinema-staging`) — `infra_endpoints.tf`. 키 `DB_HOST`·`VALKEY_HOST`·`SQS_NOTIFICATION_URL`·`COGNITO_ISSUER_URI`·`COGNITO_CLIENT_ID` | `{svc}-config`에 `${DB_HOST}` 같은 자리만. 파드가 두 ConfigMap을 envFrom으로 받고 Spring이 치환. Flyway Job은 `$(DB_HOST)` |
| ECR 레지스트리(계정 ID) | 서비스 Application 5개 `cinema-{svc}` — `argocd_apps.tf`의 `kustomize.images` `cinema/{svc}=<레지스트리>/cinema/{svc}` | `image-tag/`에 `newTag`만 |
| 플랫폼·`cinema-common`·AppProject | gitops `apps/staging` — root 앱 `cinema-root`(Terraform)가 sync | 매니페스트 |

#### 6-C-1. ConfigMap 확인 (apply 없음)

ConfigMap `cinema-infra-endpoints`는 3.1.4에서 이미 만들었다. 3.1.5부터 `envs/staging-k8s`를 apply하면 **ArgoCD·Application도 같이 만들어지므로**(6-C-2), 여기서는 apply하지 않고 상태만 확인한다. 처음 만드는 환경이면 6-C-2의 한 번의 apply에서 같이 생긴다.

```bash
# 터미널 A (cinema-admin)
kubectl -n cinema-staging get cm cinema-infra-endpoints \
  -o go-template='{{range $k, $v := .data}}{{$k}}{{"\t"}}{{if $v}}set{{else}}EMPTY{{end}}{{"\n"}}{{end}}'
# 기대(Cognito 전): DB_HOST·VALKEY_HOST·SQS_NOTIFICATION_URL = set, COGNITO_ISSUER_URI·COGNITO_CLIENT_ID = EMPTY
```

- 값은 화면에 출력하지 않는다(위 명령은 키와 set/EMPTY만 보여 준다)
- `COGNITO_*`는 Cognito(3.3.x) 전까지 빈 값이다. 만든 뒤 `staging-k8s.tfvars`에 `cognito_enabled = true` → plan(ConfigMap 1 change + `argocd-apps` 1 change(gateway Application 추가)) → apply
- SQS URL은 큐 이름으로 정해져서 3.1.8 전에도 들어간다(큐가 없으면 booking 발행만 실패)
- 값이 바뀌어도 파드는 자동으로 다시 뜨지 않는다 → ArgoCD에서 해당 앱 **Restart**(또는 다음 배포)
- ConfigMap이 없거나 값이 틀리면 **6-C-2 사전 조건을 모두 확인한 뒤** 6-C-2의 plan·apply 한 번으로 고친다(ConfigMap만 따로 apply하지 않는다)

#### 6-C-2. ArgoCD 설치 · root 앱 · 서비스 Application (3.1.5)

코드: `envs/staging-k8s/argocd.tf`(ArgoCD Helm·팀원 조회 Role) · `argocd_apps.tf`(Helm `argocd-apps`: root + 서비스) · gitops `apps/staging`·`base/gateway/ingress.yaml`

**한 번의 apply**로 ArgoCD → Application이 만들어진다(3.1.4의 `argocd_apps_enabled` 두 단계는 없앴다 — `kubernetes_manifest`는 CRD가 있어야 plan이 되지만 Helm은 순서만 지키면 된다).

| 사전 조건 | 확인 | 없으면 |
| --- | --- | --- |
| 노드 켜짐 | `kubectl get nodes` 2대 Ready | Helm 대기·Job이 시간 초과 (13장) → `infra/scripts/cluster-scale.sh up` |
| 3.1.12 계정·Secret | 8-B `PASS … (VERIFY_IDENTITY)` | Flyway 훅 Job 실패 → 앱 sync 중단 |
| 6-C-1 ConfigMap | 6-C-1 확인 명령 (DB_HOST·VALKEY_HOST·SQS = set) | 이 apply에서 같이 만들어진다 |
| ECR 이미지 · gitops 태그 | 6-A-4 수동 실행 `services=all` → gitops `image-tag`에 sha7 | `CHANGE_ME` 태그 → `ImagePullBackOff` |
| gitops PR `infra/3.1.5-argocd` 머지 | 플랫폼 차트 버전 고정·gateway Ingress·project wave | 플랫폼 앱이 `CHANGE_ME` 리비전으로 실패 |
| 차트 버전이 실제로 있는지 | `helm search repo argo/argo-cd --versions \| head -3` · `argo/argocd-apps` · `argo/argo-rollouts` · `prometheus-community/kube-prometheus-stack` | `Could not find` (3.1.4 tomcat 사례) → tfvars·gitops를 있는 버전으로 |
| **gateway만**: Cognito(7장) + `cognito_enabled = true` | ConfigMap `COGNITO_*`가 빈 값이 아님 | Terraform이 gateway Application을 만들지 않는다(정상) |

```bash
# 터미널 B (0장 Terraform 자격 증명)
cd infra/terraform/envs/staging-k8s
terraform init -upgrade -backend-config=backend.hcl
terraform plan -var-file=staging-k8s.tfvars -out=plan.out
# 기대(Cognito 전): namespace 1(argocd) · helm_release 2(argocd·argocd-apps) · Role 1 · RoleBinding 1. destroy 0
#   plan의 argocd-apps values에 ECR 레지스트리(계정 ID)가 보인다 → 붙여 넣을 때 <ACCOUNT_ID>로 가린다
terraform apply plan.out          # ArgoCD 3~5분 + Application 생성
rm -f plan.out                    # 저장된 plan에는 비밀 값이 평문으로 있다
```

**확인** (터미널 A, `cinema-admin`)

```bash
kubectl -n argocd get pods                                  # application-controller·repo-server·server·redis·applicationset Running
kubectl -n argocd get applications -w                       # 10~20분: 아래 순서로 Synced·Healthy
#   cinema-root → (wave -20) project → (-10) argo-rollouts·kube-prometheus-stack → (-5) cinema-common
#   cinema-catalog·booking·user·payment: CRD가 생길 때까지 재시도(최대 10회) 뒤 Synced·Healthy
kubectl -n cinema-staging get jobs                          # {svc}-flyway Complete (catalog·booking·user·payment)
kubectl -n cinema-staging get rollout,deploy,pods
kubectl -n cinema-staging get rollout,deploy -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.template.spec.containers[0].image}{"\n"}{end}' \
  | sed -E 's/[0-9]{12}/<ACCOUNT_ID>/'                      # <ACCOUNT_ID>.dkr.ecr…/cinema/{svc}:{sha7}
terraform output argocd_service_apps                        # Cognito 전: catalog·booking·user·payment
```

**ArgoCD 화면** (port-forward 전용, Ingress 없음)

| 누가 | 방법 | 할 수 있는 것 |
| --- | --- | --- |
| 팀원 (`cinema-dev`) | `kubectl -n argocd port-forward svc/argocd-server 8080:80` → http://localhost:8080 (로그인 없이) | 읽기 전용 (anonymous → `role:readonly`). `kubectl -n argocd get applications`도 가능 |
| 관리자 | 같은 주소에서 `admin` / `kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' \| base64 -d` | sync·Restart·롤백 버튼. 비밀번호는 화면에만, 붙여 넣지 않는다 |

**gateway (Cognito 뒤)**

1. `cognito_enabled = true` → plan(ConfigMap 1 change · `argocd-apps` 1 change) → apply
2. gitops `overlays/staging/gateway/image-tag`가 `CHANGE_ME`면 `build-push` 수동 실행 `services=gateway`
3. `kubectl -n cinema-staging get ingress gateway` ADDRESS에 ALB DNS → 7-4(CloudFront `/api`)

- 이미지 태그는 Terraform이 관리하지 않는다(build-push → gitops → ArgoCD). 태그가 바뀌어도 이 폴더 plan은 No changes
- 앱 설정은 gitops PR, ArgoCD 화면에서 손으로 고친 값은 selfHeal이 되돌린다. 급할 때만 관리자가 해당 앱 auto-sync를 끄고 작업 → Git 반영 → 다시 켬
- 로그·PR·노션에 이미지 주소·ALB DNS를 붙일 때는 계정 ID를 `<ACCOUNT_ID>`로 가린다

### 6-D. 블루/그린 배포·자동 롤백 확인 (3.1.6, OPS-03·04)

코드: SeatSAC-gitops `base/{booking,gateway}/rollout.yaml` · `analysis/` (AnalysisTemplate + 분석 트래픽 k6 `analysis/smoke/`) · `base/{booking,gateway}/networkpolicy.yaml`(`rollout-smoke` 허용). Terraform 변경 없음

| 단계 | 일어나는 일 |
| --- | --- |
| 1 | 새 태그 커밋(build-push) 또는 설정 PR → Rollout이 **preview** ReplicaSet을 띄움(active는 그대로 트래픽 처리) |
| 2 | preview 확장과 **동시에**(Ready 전) `prePromotionAnalysis` 시작: 분석 Job이 preview Ready를 기다린 뒤 `{svc}-preview:8080`에 조회 6분(200이 95% 미만이면 실패), Prometheus 지표 4분 지연 뒤 1분 × 3회 (런타임 계약 5장) |
| 3a | 통과 → active Service를 preview 해시로 전환 → 이전 버전 60초 뒤 축소 |
| 3b | 실패 → **abort**: active 그대로(= 자동 롤백, 사용자 영향 없음), preview 30초 뒤 축소. Rollout·ArgoCD 앱이 `Degraded` |

**사전 조건**: 6-C-2 확인 완료(앱 Synced·Healthy), 시드 적재(8-C)·**분석 회차 ID(6-D-0)**, `kube-prometheus-stack`이 booking·gateway PodMonitor를 수집(6-D-1), gateway는 Cognito 뒤. 첫 배포(이전 버전 없음)는 분석 없이 바로 active가 된다

**도구** (터미널 A, 한 번): Argo Rollouts kubectl 플러그인 — 컨트롤러 차트 2.43.8과 같은 릴리스 계열

```bash
brew install argoproj/tap/kubectl-argo-rollouts     # macOS. Linux: GitHub 릴리스 바이너리
kubectl argo rollouts version
```

#### 6-D-0. 분석 회차 ID 고르기 (`SMOKE_SCREENING_ID`, 시드 적재 뒤 1회)

booking 분석 Job은 `GET /screenings/{id}/seats`가 **200**이어야 통과한다. 없는 회차면 404 → k6 checks 50% → Job 실패 → **정상 배포도 전부 abort**. 그래서 gitops `analysis/booking-prepromotion.yaml`의 `SMOKE_SCREENING_ID`는 `CHANGE_ME`로 두고, `gitops-validate`가 숫자가 아니면 PR을 막는다. 아래로 실제 회차를 골라 넣는다.

| 조건 | 이유 |
| --- | --- |
| 시드(lotte-seed)에 있는 회차 | catalog가 404를 돌려주지 않게 |
| **좌석 배치가 있는 극장**(`hasLayout: true`, 4곳)·`totalSeats > 0` | 좌석 현황이 Valkey·RDS 집계까지 끝까지 탄다 |
| 날짜는 상관없음 | 좌석 현황은 지난 회차도 200(`SCREENING_STARTED`는 선점 경로에만). 한 번 정하면 고정 |
| (나중에) 결제 경로를 넣을 때는 **미래 회차** + 분석 전용 회차 | 선점이 `SCREENING_STARTED`로 막히고, 진짜 예매가 생겼다 취소되므로 데모 데이터와 섞이지 않게 (#37 이후, 남궁일) |

```bash
# 터미널 A (cinema-admin, 노드 켜짐). 응답은 화면에서만 보고 PR·노션에 붙이지 않는다(시드 내용)
kubectl -n cinema-staging port-forward svc/catalog 18081:8080 &
kubectl -n cinema-staging port-forward svc/booking 18082:8080 &

# 1) 좌석 배치가 있는 극장
curl -s localhost:18081/theaters | jq -r '.items[] | select(.hasLayout) | "\(.theaterId)\t\(.name)"'

# 2) 그 극장의 회차 — 시드 영업일을 모르면 최근 날짜를 훑는다 (시드는 하루 편성)
T=<1)에서 고른 theaterId>
for d in $(for i in $(seq 0 30); do date -d "-$i day" +%F 2>/dev/null || date -v-${i}d +%F; done); do
  n=$(curl -s "localhost:18081/screenings?date=$d&theaterId=$T" | jq '.items | length')
  [ "${n:-0}" -gt 0 ] && { echo "영업일 $d: 회차 $n개"; break; }
done
curl -s "localhost:18081/screenings?date=$d&theaterId=$T" \
  | jq -r '.items[] | select(.totalSeats > 0) | "\(.screeningId)\t\(.totalSeats)석\t\(.startsAt)"' | head -3

# 3) booking으로 확인 — 둘 다 200, 좌석 목록이 비어 있지 않아야 한다
S=<2)에서 고른 screeningId>
curl -s -o /dev/null -w 'seats %{http_code}\n' localhost:18082/screenings/$S/seats
curl -s -o /dev/null -w 'seat-counts %{http_code}\n' "localhost:18082/internal/screenings/seat-counts?ids=$S"
curl -s localhost:18082/screenings/$S/seats | jq 'keys'     # 좌석 필드가 있는지만 본다

kill %1 %2
```

- 영업일 훑기가 비면: 시드 적재 확인(8-C `PASS`)부터. 영업일은 lotte-seed 적재 결과 또는 한채연에게 묻는다(시드 SQL·원본은 레포·PR에 붙이지 않는다)
- 고른 ID를 gitops PR로 `SMOKE_SCREENING_ID`에 넣는다(숫자만, 따옴표 안). 회차 ID는 숫자일 뿐이라 Public 레포에 두어도 된다
- 시드를 다시 적재(재생성 리허설 3.1.15 등)해 ID가 바뀌면 이 절을 다시 하고 gitops를 고친다

#### 6-D-1. 수집 확인 (분석이 볼 지표가 있는지)

```bash
kubectl -n monitoring port-forward svc/kube-prometheus-stack-prometheus 9090:9090
# http://localhost:9090 → Status > Targets 에 podMonitor/cinema-staging/booking UP
# 쿼리: http_server_requests_seconds_count{application="booking"} 에 rollouts_pod_template_hash 라벨이 있어야 한다
```

#### 6-D-2. 정상 배포 (통과 → 전환)

```bash
# SeatSAC main에 booking 변경 머지 → build-push가 gitops 태그 커밋 (또는 수동 실행 services=booking)
kubectl argo rollouts get rollout booking -n cinema-staging --watch
#   기대: Preview(새 해시) Running → AnalysisRun Running(약 6분) → Successful → Active 전환 → 이전 ReplicaSet 60초 뒤 ScaledDown
kubectl -n cinema-staging get analysisrun -l rollouts-pod-template-hash          # 최신 Successful
kubectl -n cinema-staging get jobs | grep -i preview-traffic                       # 분석 Job Complete
```

- 데모(무중단 배포)는 이 단계 동안 k6 `tests/k6/deploy-during-load.js`(OPS-10)를 active(gateway)로 돌려 오류율 0%를 본다

#### 6-D-3. 실패 버전 시연 (abort → 자동 롤백)

앱 코드를 고치지 않고 **gitops 설정 PR**로 preview만 망가뜨린다. 설정이 바뀌면 ConfigMap 해시가 바뀌어 새 ReplicaSet(preview)이 뜬다. active 파드는 이전 ConfigMap을 그대로 쓴다

```yaml
# SeatSAC-gitops overlays/staging/booking/kustomization.yaml — 시연용 PR (제목에 [시연·revert 예정])
      - APP_SERVICES_CATALOG_URL=http://catalog-unavailable:8080   # 원래 http://catalog:8080
```

1. 위 PR 머지 → `kubectl argo rollouts get rollout booking -n cinema-staging --watch`
2. preview는 Ready가 된다(readiness = DB만). 분석 Job의 `GET /screenings/{id}/seats`가 catalog를 못 찾아 503 → `http-5xx-ratio` 실패 → **Aborted**, active는 이전 버전 그대로
3. 그동안 사용자 화면·k6(active)는 오류 없음 = 자동 롤백 확인
4. **시연 PR을 revert하는 PR** 머지 → Rollout이 안정 버전과 같은 템플릿으로 돌아와 `Healthy`(새 배포 없음)

- 같은 방식으로 gateway도 가능(`gateway-config`의 `APP_SERVICES_CATALOG_URL`) — Cognito 뒤
- 결제 경로 실패(`booking_payment_call_total` timeout·error)로 시연하려면 분석 트래픽에 결제 경로가 들어가야 한다(BKG-10 #37 이후, 남궁일과 정함)

#### 6-D-4. 이미지 버전이 실패했을 때 되돌리기

| 상황 | 할 일 |
| --- | --- |
| 분석 실패로 Aborted | active는 안전. gitops에서 **해당 `deploy(staging): {svc} → {sha7}` 커밋을 revert하는 PR** → 머지하면 `Healthy`. 앱 수정은 SeatSAC에서 고쳐 다시 머지(새 태그) |
| 전환된 뒤 문제 발견(60초 안) | ArgoCD 화면(admin) → Rollout → **Abort**는 전환 전에만 의미. 전환 후에는 revert PR이 원칙 |
| preview가 10분 안에 Ready 안 됨 | `progressDeadlineAbort`로 자동 abort. `kubectl -n cinema-staging describe rollout booking`·preview 파드 로그 확인 → revert PR |

- `kubectl argo rollouts abort/promote/undo`는 클러스터 직접 조작이다 → 평소에 쓰지 않는다(selfHeal·Git과 어긋남). 긴급 시 관리자만, 스크럼 공유

#### 6-D-5. 자주 막히는 곳

| 증상 | 원인 | 해결 |
| --- | --- | --- |
| gitops PR에서 `블루/그린 분석 회차 ID (숫자)` 실패 | `SMOKE_SCREENING_ID`가 `CHANGE_ME`이거나 숫자가 아님 | 6-D-0으로 실제 회차를 골라 넣는다 |
| `preview-requests` 실패(결과 0) | 분석 Job이 preview에 못 닿음(NetworkPolicy·Job ImagePull) 또는 PodMonitor 미수집 | `kubectl -n cinema-staging logs job/<…preview-traffic…>`, 6-D-1 Targets |
| `preview-traffic` 실패 | ① 조회가 200이 아님(k6 `checks` < 95%, 종료 코드 99) — 새 버전의 경로·라우트 고장 또는 `SMOKE_SCREENING_ID`가 시드에 없는 회차(404) ② Job 파드 오류(이미지 `grafana/k6:1.7.1` pull, ConfigMap `rollout-smoke-scripts` 없음) | Job 로그의 k6 요약에서 `✗ seats 200` 같은 실패 check 확인. ②는 `kubectl -n cinema-staging get cm rollout-smoke-scripts`(cinema-common 앱) |
| AnalysisRun `Error`(Failed 아님) | Prometheus 주소·쿼리 오류 | `kubectl -n cinema-staging describe analysisrun <이름>` 메시지 |
| booking·gateway가 동시에 preview | 블루/그린 동시 금지(노드 2대) | build-push가 booking만 커밋. 사람 PR도 하나씩 |

### 6-E. NetworkPolicy 허용 목록 확인 (3.1.13, OPS-15)

코드: SeatSAC-gitops `base/common/default-deny-ingress.yaml`(앱 namespace 인바운드 기본 거부) · `base/{svc}/networkpolicy.yaml`(허용) · 확인 스크립트 `infra/scripts/np-check.sh`. 집행은 VPC CNI 네트워크 정책 에이전트(`enableNetworkPolicy = "true"`, 3.1.2). Terraform 변경 없음

| 호출하는 쪽 → | 허용 대상 (8080) | 근거 |
| --- | --- | --- |
| ALB (public 서브넷 `10.20.0.0/24`·`10.20.1.0/24`) | gateway | CloudFront `/api` → ALB → gateway |
| gateway | catalog · booking · user | 라우팅 |
| catalog | booking | 잔여 좌석 `GET /internal/screenings/seat-counts` |
| booking | catalog · payment | 회차 확인 · 결제 |
| user | booking · catalog | 예매 내역 · 회차 요약·극장 검증 |
| payment | (없음) | 호출하는 쪽이 아니다 |
| 분석 Job `rollout-smoke` | booking · gateway (preview) | 3.1.6 |
| `monitoring` namespace | 전체 | Prometheus 수집 |

```bash
# 터미널 A (cinema-admin, 노드 켜짐)
kubectl get daemonset -n kube-system aws-node -o jsonpath='{.spec.template.spec.containers[*].name}{"\n"}'   # aws-node aws-eks-nodeagent (정책 에이전트)
kubectl -n cinema-staging get networkpolicy                  # Cognito 전: default-deny-ingress + catalog·booking·user·payment-ingress 4개 (gateway 앱이 아직 없음) · 뒤: + gateway-ingress = 5개
infra/scripts/np-check.sh                                    # 서비스 파드 안에서 서로 8080에 붙어 본다 (get·exec만, 읽기)
# Cognito 뒤(gateway 배포 후): REQUIRE_GATEWAY=1 infra/scripts/np-check.sh   — gateway 파드가 없으면 실패
```

기대(gateway는 Cognito 전이라 건너뜀):

```
from     → to        기대     결과
catalog  → booking   OPEN     OPEN     ✅
catalog  → user      BLOCKED  BLOCKED  ✅
catalog  → payment   BLOCKED  BLOCKED  ✅
booking  → catalog   OPEN     OPEN     ✅
booking  → user      BLOCKED  BLOCKED  ✅
booking  → payment   OPEN     OPEN     ✅
user     → catalog   OPEN     OPEN     ✅
user     → booking   OPEN     OPEN     ✅
user     → payment   BLOCKED  BLOCKED  ✅
payment  → catalog   BLOCKED  BLOCKED  ✅
payment  → booking   BLOCKED  BLOCKED  ✅
payment  → user      BLOCKED  BLOCKED  ✅
모두 기대대로 (12개 검사, 런타임 계약 6장)
```

| 결과 | 뜻 | 판정 |
| --- | --- | --- |
| `OPEN` | 연결 성공 | 허용 대상이면 ✅ |
| `BLOCKED` | **시간 초과**(timeout 종료 코드 124) — VPC CNI 정책은 패킷을 버리므로 차단은 시간 초과로 보인다 | 차단 대상이면 ✅ |
| `ERROR:DNS` | 파드 안에서 서비스 이름이 안 풀림 | 정책 판단 불가 → ❌ (CoreDNS·Service 이름 확인) |
| `ERROR:REFUSED` | 연결 거부 — 대상이 8080에서 안 듣고 있음 | ❌ (대상 파드 상태) |
| `ERROR:NOT_READY` | 대상 Service에 Ready 엔드포인트가 없음 | ❌ (대상 readiness) |
| `ERROR:EXEC` | exec 실패 | ❌ (권한·컨테이너 이름·bash) |

- 종료 코드: `0` 모두 기대대로 · `1` 기대와 다르거나 판단 불가(필수 서비스 파드 없음, 검사 0개 포함) · `2` 클러스터 조회 실패(권한·연결 — "파드 없음"으로 착각하지 않게 바로 멈춤)
- 필수 서비스: catalog·booking·user·payment. gateway는 Cognito 전에는 없어도 되고, 뒤에는 `REQUIRE_GATEWAY=1`로 필수

- **Cognito 뒤(gateway 배포 후) 다시 실행**: `gateway → payment BLOCKED`(인계 #14 완료 조건), `gateway → catalog·booking·user OPEN`, 그리고 다른 서비스 → gateway가 **BLOCKED**인지(ALB 대역만 허용)도 같이 본다(20개 검사)
- **ALB → gateway 실경로는 `np-check.sh`가 보지 않는다**(스크립트는 앱 파드끼리만). public 서브넷 대역 규칙은 아래로 따로 확인한다 — 인계 #14 완료 조건
  ```bash
  kubectl -n cinema-staging get pods -l app.kubernetes.io/name=gateway -o wide     # gateway 파드 IP
  aws elbv2 describe-target-health --target-group-arn "$(aws elbv2 describe-target-groups \
    --query "TargetGroups[?contains(TargetGroupName,'cinemast-gateway')].TargetGroupArn | [0]" --output text)" \
    --query 'TargetHealthDescriptions[].[Target.Id,TargetHealth.State]' --output text   # 파드 IP healthy
  curl -sS -o /dev/null -w '%{http_code}\n' "https://$(cd infra/terraform/envs/staging && terraform output -raw cloudfront_domain_name)/api/movies"   # 200 (CAT-01, 인증 없음)
  ```
  대상 그룹 이름은 LB Controller가 `k8s-cinemast-gateway-…`처럼 줄여 만든다. 못 찾으면 `aws elbv2 describe-target-groups --query 'TargetGroups[].TargetGroupName'`으로 확인
- **허용 대역이 ALB 서브넷과 정확히 같은지** 대조한다. healthy·200은 "너무 좁은" 허용만 잡고 "너무 넓은" 허용(예: VPC CIDR로 되돌아감)은 못 잡는다
  ```bash
  grep '^public_subnets' infra/terraform/envs/staging/staging.tfvars                # ① 기준: ["10.20.0.0/24", "10.20.1.0/24"]
  kubectl -n cinema-staging get networkpolicy \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{..ipBlock.cidr}{"\n"}{end}'   # ② ipBlock은 gateway 정책에만, ①과 같은 2개
  aws ec2 describe-subnets --subnet-ids $(aws elbv2 describe-load-balancers \
    --query "LoadBalancers[?contains(LoadBalancerName,'cinemast-gateway')].AvailabilityZones[].SubnetId" --output text) \
    --query 'Subnets[].CidrBlock' --output text                                    # ③ 실제 ALB 서브넷 = ①
  ```
  판정: ①·②·③이 같은 두 CIDR이고, ②에서 gateway 밖의 정책에 ipBlock이 없으면 ✅. ②가 더 넓으면(`10.20.0.0/16` 등) gitops `base/gateway/networkpolicy.yaml`을 고친다. ③이 다르면 ALB 서브넷 태그를 확인한다(아래 표 마지막 줄). ALB 이름도 `k8s-cinemast-gateway-…`이고, 못 찾으면 `aws elbv2 describe-load-balancers --query 'LoadBalancers[].LoadBalancerName'`으로 확인
- Prometheus Targets가 모두 UP이면 `monitoring` 허용은 확인된 것(6-D-1)
- BLOCKED는 연결이 버려져 `TIMEOUT`(기본 3초) 뒤에 나온다 → 전체 1분 안팎

| 증상 | 원인 | 해결 |
| --- | --- | --- |
| 허용인데 BLOCKED (서비스 간 503·타임아웃) | 호출하는 파드 라벨이 `app.kubernetes.io/name`과 다름, 허용 규칙 누락 | 그 서비스 `networkpolicy.yaml`에 규칙 추가 PR + 런타임 계약 6장 표 먼저 |
| 차단이어야 하는데 OPEN | 정책 에이전트가 안 돎(`aws-eks-nodeagent` 없음)·VPC CNI 애드온 설정 | `enableNetworkPolicy` 확인(3.1.2), 노드 재기동 뒤 다시 |
| 종료 코드 2 `kubectl get pods 실패` | 자격 증명 만료·노드 꺼짐·권한 | `cinema-admin` 다시 로그인, `cluster-scale.sh status` |
| `ERROR:EXEC` 전부 | exec 권한 없음(`cinema-dev`)·컨테이너 이름이 서비스 이름과 다름·이미지에 bash 없음 | `cinema-admin`으로, `kubectl -n cinema-staging get pod <파드> -o jsonpath='{.spec.containers[*].name}'` |
| `ERROR:NOT_READY`·`REFUSED` | 대상 서비스가 기동 중·CrashLoop | 대상이 Ready가 된 뒤 다시(정책 문제 아님) |
| gateway 배포 후 화면 `/api` 502·504 | ALB ENI가 public 서브넷 밖에 생김(서브넷 태그) 또는 대역 변경 | ALB 서브넷 확인 → gitops `base/gateway/networkpolicy.yaml` ipBlock을 `staging.tfvars public_subnets`와 맞춤 |

## 7. staging 5) CloudFront·S3 (3.1.11) · Cognito (3.1.16) — ⬜

코드: `envs/staging/cloudfront.tf` + `envs/staging/cloudfront/spa-rewrite.js`(CloudFront Function) · `global/github_oidc.tf`(무효화 권한) · `.github/workflows/frontend-deploy.yml`. Cognito는 `cognito.tf`(3.1.16, 7-6).

| 항목 | 값 |
| --- | --- |
| S3 | `cinema-staging-frontend-<계정ID>`, 공개 접근 차단, SSE-S3, 버킷 정책은 이 CloudFront 배포(OAC)만 `GetObject` |
| CloudFront | 기본 도메인(`*.cloudfront.net`)·기본 인증서, PriceClass_200(한국 포함), HTTP/2·3, IPv6. 접근 로그 없음 |
| 동작 | `/*` → S3(spa-shell 정책, 보안 헤더) · `/assets/*` → S3(1년) · `/api/screenings/*/seats/stream` → ALB(CachingDisabled, **압축 끔**) · `/api/*` → ALB(CachingDisabled, 모든 메서드) |
| 캐시 정책 3종 | `cinema-staging-static-assets`(1년) · `cinema-staging-spa-shell`(원본 Cache-Control 따름, 최대 60초) · 관리형 `CachingDisabled`(/api) |
| 원본 요청 정책 | `cinema-staging-api-origin`: 뷰어 헤더 전부 + `CloudFront-Viewer-Address`, 쿼리 전부, 쿠키 없음 |
| CloudFront Function | `cinema-staging-spa-rewrite`(viewer-request): 확장자 없는 경로 → `/index.html` |
| ALB 원본 | gateway Ingress가 만든 ALB를 태그(`ingress.k8s.aws/stack=cinema-staging/gateway`)로 찾는다. HTTP 80, 원본 응답 대기 30초(SSE heartbeat 15초보다 길게) |

### 7-1. envs/staging 1차 apply (S3·CloudFront, ALB 전)

ALB는 gateway Ingress(gitops 3.1.5)가 만든다. 그 전에는 `/api/*` 동작 없이 S3만 배포된다.

```bash
export AWS_PROFILE=cinema-admin
cd infra/terraform/envs/staging
terraform init -backend-config=backend.hcl
terraform fmt -recursive && terraform validate
terraform plan -var-file=staging.tfvars -out=plan.out
# 기대: S3 버킷 1 + 설정 4(공개 차단·소유권·암호화·정책) · OAC 1 · 캐시 정책 2 · 원본 요청 정책 1 · Function 1 · 배포 1. destroy 0
#       출력 api_origin_domain_name 은 "" (ALB 없음)
terraform apply plan.out                 # CloudFront 배포 생성 5~10분
terraform output cloudfront_domain_name          # 출력 이름은 한 번에 하나만 받는다
terraform output cloudfront_distribution_id      # → 7-2 global.tfvars
terraform output frontend_github_variables       # → 7-3 GitHub Variables
```

### 7-2. global — 무효화 권한을 이 배포로 좁힘

```bash
cd ../../global
# global.tfvars 의 frontend_distribution_ids = ["<cloudfront_distribution_id 출력값>"] 로 수정하고 커밋(비밀 아님)
terraform plan -var-file=global.tfvars -out=plan.out    # 기대: aws_iam_role_policy.gha_frontend_deploy 1 update. destroy 0
terraform apply plan.out
```

### 7-3. GitHub Variables 등록 (Secrets가 아니라 **Variables** — 비밀 없음)

레포 → Settings → Secrets and variables → Actions → **Variables**

| 이름 | 값 |
| --- | --- |
| `FRONTEND_BUCKET` | `frontend_github_variables` 출력 |
| `CLOUDFRONT_DISTRIBUTION_ID` | 〃 |
| `VITE_COGNITO_REDIRECT_URI` | 〃 (`https://<배포 도메인>/callback`). Cognito 앱 클라이언트 콜백 URL에도 같은 값 |
| `VITE_COGNITO_AUTHORITY` · `VITE_COGNITO_CLIENT_ID` · `VITE_COGNITO_DOMAIN` | 〃 (3.1.16 Cognito apply 뒤 출력에 생긴다, 7-6) |

Secret `AWS_FRONTEND_DEPLOY_ROLE_ARN`은 2-4에서 이미 등록했다.

### 7-4. envs/staging 2차 apply (ALB가 생긴 뒤 — gitops 3.1.5 gateway Ingress 이후)

```bash
cd ../envs/staging
aws elbv2 describe-load-balancers --query 'LoadBalancers[].DNSName'     # gateway ALB가 보여야 한다
terraform plan -var-file=staging.tfvars -out=plan.out
# 기대: aws_cloudfront_distribution.frontend 1 update(원본 alb-gateway + 동작 2 추가). destroy 0
terraform apply plan.out                 # 배포 변경 반영 3~5분
terraform output api_origin_domain_name  # ALB DNS 이름
```

- 태그로 못 찾으면(출력이 계속 `""`): `aws elbv2 describe-tags --resource-arns <ALB ARN>` 으로 `ingress.k8s.aws/stack` 값을 확인해 `gateway_ingress_stack`을 맞추거나, 임시로 `api_origin_domain_name`에 DNS 이름을 직접 넣는다
- Ingress를 지우고 다시 만들면 ALB DNS가 바뀐다 → plan에 배포 update가 보이면 apply

### 7-5. 첫 배포·확인

```bash
# frontend/ 에 package.json·package-lock.json 이 머지된 뒤: Actions → frontend-deploy → Run workflow (main)
DOMAIN=$(terraform output -raw cloudfront_domain_name)
curl -sI "https://$DOMAIN/"                       | grep -i -E "^(HTTP|cache-control|x-cache|strict-transport)"   # 200, no-cache
curl -sI "https://$DOMAIN/movies/1"               | grep -i -E "^(HTTP|content-type)"     # 200 text/html (SPA 라우팅)
curl -sI "https://$DOMAIN/assets/$(aws s3 ls s3://$(terraform output -raw frontend_bucket)/assets/ | awk 'NR==1{print $4}')" \
                                                  | grep -i -E "^(HTTP|cache-control)"    # max-age=31536000, immutable
curl -s  "https://$DOMAIN/api/movies?page=0"      | head -c 200                           # gateway 응답(JSON) — ALB 뒤 gateway가 떠 있어야 함
curl -sN "https://$DOMAIN/api/screenings/1/seats/stream" --max-time 40                    # SSE: 15초마다 ping이 바로바로 옴 (몰려서 오면 압축·캐시 확인)
aws s3api get-public-access-block --bucket "$(terraform output -raw frontend_bucket)"     # 4개 모두 true
```

- [ ] `/` 200 + `cache-control: no-cache` + `x-cache: Miss from cloudfront`(index.html은 캐시 안 됨)
- [ ] `/movies/1`·`/callback` 200 text/html (Function 동작), `/assets/<없는 파일>` 403(그대로 둔다)
- [ ] `/assets/*` `max-age=31536000, immutable`, 두 번째 요청 `x-cache: Hit from cloudfront`
- [ ] `http://` 요청이 `https://`로 301
- [ ] `/api/*` 응답에 `x-trace-id` 헤더(gateway), `/api/actuator/health` 404(gateway가 막음)
- [ ] SSE 40초 동안 ping 2회 이상, 끊기지 않음 (인계 #13)
- [ ] `frontend-deploy` Summary에 무효화 ID, 화면 새로고침으로 변경 반영
- [ ] developer(`cinema-dev`)로 `aws s3 cp` 업로드 시도 → `AccessDenied` (CI 역할만 쓴다)
- [ ] 두 번째 `terraform plan`(staging·global) No changes

| 증상 | 원인 | 해결 |
| --- | --- | --- |
| `/`가 403 `AccessDenied`(XML) | 버킷이 비었다(아직 배포 전) / 버킷 정책의 SourceArn 불일치 | `frontend-deploy` 실행 / `terraform plan`으로 정책 확인 |
| `/api/*`가 404 또는 S3 XML | 2차 apply 전(ALB 원본 없음) | 7-4 |
| `/api/*` 502·504 | ALB SG가 CloudFront 접두사 목록만 허용 — 정상. gateway 파드 Ready 아님 / ALB 대상 그룹 unhealthy | `kubectl get pods -n cinema-staging`, Ingress 이벤트 |
| SSE 이벤트가 15초 이상 몰려서 옴 | `/api/screenings/*/seats/stream` 동작이 `/api/*` 뒤에 있거나 compress가 켜짐 | `cloudfront.tf` `api_behaviors` 순서·`compress=false` 확인 |
| SSE 30초마다 끊김 | booking heartbeat가 30초 넘게 없음 / ALB 유휴 120초 미설정(3.1.5) | 인계 #13 세트 ①~④ 확인 |
| `frontend-deploy` `AssumeRoleWithWebIdentity` 거부 | main이 아닌 ref에서 실행 / Secret 미등록 | main에서 실행 |
| `frontend-deploy` `cloudfront:CreateInvalidation` 거부 | `frontend_distribution_ids`에 다른 ID | 7-2 |
| 새 화면이 안 보임 | 브라우저가 옛 index.html을 메모리 캐시 | 무효화 완료 확인 후 강력 새로고침. CloudFront는 index.html을 캐시하지 않는다 |

### 7-6. Cognito 사용자 풀 · Pre sign-up · gateway 활성화 (3.1.16, ADR-37·63)

코드: `envs/staging/cognito.tf` · `lambdas/cognito-presignup/handler.py` · `envs/staging-k8s/staging-k8s.tfvars`(`cognito_enabled = true`). gitops 변경 없음(`gateway-config`가 이미 `${COGNITO_ISSUER_URI}`·`${COGNITO_CLIENT_ID}`를 쓴다)

| 만드는 것 | 이름·값 |
| --- | --- |
| 사용자 풀 | `cinema-staging-users` · 이메일 로그인(대소문자 무시) · 가입 필수 email·name · 비밀번호 8자(소문자·숫자) · 삭제 보호 끔(재생성 리허설) |
| 앱 클라이언트 | `cinema-staging-web` · 공개(시크릿 없음) · 코드 + PKCE · 범위 `openid email profile aws.cognito.signin.user.admin` · 콜백 `https://<배포 도메인>/callback`·`http://localhost:5173/callback` · 로그아웃 `…/` · Access·ID 60분, Refresh 7일 |
| Hosted UI 도메인 | `https://cinema-staging.auth.ap-northeast-2.amazoncognito.com` (클래식 Hosted UI) |
| 그룹 | `ADMIN` → gateway `X-User-Role: ADMIN` |
| Pre sign-up Lambda | `cinema-staging-cognito-presignup`(Python 3.12, arm64) — Hosted UI 가입을 자동 확인(메일 발송 없음). 로그 7일 |

**순서** (gateway 이미지 태그가 `CHANGE_ME`라 Application보다 먼저 태그를 넣는다)

1. SeatSAC PR 머지 → **7-6-1** `envs/staging` apply
2. Actions → `build-push` → `services=gateway` 수동 실행(gitops `overlays/staging/gateway/image-tag`에 태그 커밋)
3. **7-6-2** `envs/staging-k8s` apply(`cognito_enabled = true`) → gateway Application → 파드·Ingress(ALB)
4. 7-4 CloudFront `/api` → 7-3 Variables 3개 추가 → `frontend-deploy` → 7-5 확인
5. 6-E `REQUIRE_GATEWAY=1 infra/scripts/np-check.sh` · ALB → gateway 실경로·대역 대조(인계 #14)

#### 7-6-1. envs/staging apply

```bash
# 터미널 B (0장 Terraform 자격 증명). 최신 main
cd infra/terraform/envs/staging
git pull && git log -1 --oneline
terraform init -backend-config=backend.hcl            # 새 프로바이더 hashicorp/archive 설치 → .terraform.lock.hcl 바뀜
terraform providers lock -platform=darwin_arm64 -platform=linux_amd64   # 맥·CI 둘 다의 해시
git diff --stat .terraform.lock.hcl                    # archive 블록이 추가됐으면 PR로 커밋(처음 한 번)
terraform plan -var-file=staging.tfvars -out=plan.out
#   기대: aws_cognito_user_pool · _client · _domain · _user_group 각 1 · aws_lambda_function 1 · aws_lambda_permission 1
#         aws_iam_role 1 · aws_iam_role_policy 1 · aws_cloudwatch_log_group 1 → 9 to add, 0 change, 0 destroy
terraform apply plan.out && rm -f plan.out
terraform output -json frontend_github_variables | jq 'keys'     # 6개 (값은 7-3에서 Variables로)
```

- `InvalidParameterException … Domain already exists`: 접두사 `cinema-staging`을 다른 계정이 쓰고 있다 → `staging.tfvars`에 `cognito_domain_prefix = "cinema-staging-<짧은 접미사>"` → 다시 plan. 런타임 계약 3장 `VITE_COGNITO_DOMAIN`도 같이 고친다
- 출력의 사용자 풀 ID·클라이언트 ID는 비밀이 아니지만(화면에 들어간다) 사용자 풀 ID에는 리전만 있다. 계정 ID는 없다

#### 7-6-2. gateway 활성화 (envs/staging-k8s)

```bash
cd ../staging-k8s
terraform plan -var-file=staging-k8s.tfvars -out=plan.out
#   기대: kubernetes_config_map_v1.infra_endpoints 1 change(COGNITO_ISSUER_URI·COGNITO_CLIENT_ID 채움)
#         helm_release.argocd_apps 1 change(cinema-gateway Application 추가). destroy 0
terraform apply plan.out && rm -f plan.out

# 터미널 A
kubectl -n cinema-staging get cm cinema-infra-endpoints \
  -o go-template='{{range $k, $v := .data}}{{$k}}{{"\t"}}{{if $v}}set{{else}}EMPTY{{end}}{{"\n"}}{{end}}'   # COGNITO_* = set
kubectl -n argocd get application cinema-gateway                  # Synced · Healthy (분석 없음: 첫 배포)
kubectl -n cinema-staging get pods,ingress -l app.kubernetes.io/name=gateway
kubectl -n cinema-staging get ingress gateway -o jsonpath='{.status.loadBalancer.ingress[0].hostname}{"\n"}' | sed -E 's/[0-9]{12}/<ACCOUNT_ID>/'
```

- 다른 앱(catalog·booking·user·payment)은 ConfigMap이 바뀌어도 자동으로 다시 뜨지 않는다. **user**는 Cognito 값을 쓰지 않으니(엔드포인트 기본값) 그대로 둔다
- gateway 파드가 `CreateContainerConfigError`·기동 실패면 ConfigMap `COGNITO_*`가 비었는지(apply 순서), `ImagePullBackOff`면 2번(build-push)을 안 한 것

#### 7-6-3. 사용자·관리자

```bash
POOL=$(cd ../staging && terraform output -raw cognito_user_pool_id)
# 가입은 화면(Hosted UI)에서. 관리자 지정: 가입한 이메일로
aws cognito-idp admin-add-user-to-group --user-pool-id "$POOL" --username '<가입 이메일>' --group-name ADMIN
aws cognito-idp list-users-in-group --user-pool-id "$POOL" --group-name ADMIN --query 'Users[].Username' --output text
# 비밀번호 분실(메일 발송 없음): 관리자가 임시 비밀번호 설정 → 다음 로그인 때 변경
aws cognito-idp admin-set-user-password --user-pool-id "$POOL" --username '<이메일>' --password '<임시>' --no-permanent
```

- 이메일·비밀번호는 PR·노션·런북에 적지 않는다. 그룹 변경은 **다음 토큰부터** 반영(다시 로그인)

#### 7-6-4. 확인

```bash
DOMAIN=$(cd ../staging && terraform output -raw cloudfront_domain_name)
HOSTED=$(cd ../staging && terraform output -raw cognito_hosted_ui_url)
curl -s "$(cd ../staging && terraform output -raw cognito_issuer_uri)/.well-known/openid-configuration" | jq -r .issuer   # 발급자 URL
curl -s -o /dev/null -w '%{http_code}\n' "https://$DOMAIN/api/movies"        # 200 (7-4 뒤)
curl -s -o /dev/null -w '%{http_code}\n' "https://$DOMAIN/api/users/me"      # 401 (토큰 없음)
```

- [ ] Hosted UI(`$HOSTED/login?client_id=…&response_type=code&scope=openid&redirect_uri=https://$DOMAIN/callback`)에서 가입 → 메일 확인 없이 바로 로그인
- [ ] 화면 로그인 → `/api/users/me` 200(프로필 생성, email·name 채워짐). **503 `IDENTITY_UNAVAILABLE`**이면 화면이 `aws.cognito.signin.user.admin` 범위를 요청하지 않은 것(아래)
- [ ] ADMIN 지정 후 다시 로그인 → `/api/admin/**` 403이 아님
- [ ] 로그아웃 → Hosted UI 세션 종료 후 `/`로 돌아옴
- [ ] Lambda 로그 그룹 `/aws/lambda/cinema-staging-cognito-presignup`에 가입 시각 기록

| 증상 | 원인 | 해결 |
| --- | --- | --- |
| `/api/users/me` 503 `IDENTITY_UNAVAILABLE`, user 로그 `GetUser rejected the token` | Access Token에 `aws.cognito.signin.user.admin` 범위가 없음(화면 `scope`) | frontend `scope`에 추가(런타임 계약 3장) → 다시 로그인 |
| 가입 후 "User is not confirmed" | Pre sign-up 연결 안 됨·Lambda 오류 | `aws cognito-idp describe-user-pool --user-pool-id $POOL --query 'UserPool.LambdaConfig'`, Lambda 로그 |
| Hosted UI `redirect_mismatch` | 콜백 URL이 클라이언트 목록과 다름(끝 `/`·http/https) | `VITE_COGNITO_REDIRECT_URI` = `https://<배포 도메인>/callback` |
| 모든 API 401 | gateway issuer·client_id와 토큰이 다름(풀을 다시 만들었는데 ConfigMap 그대로) | 7-6-2 다시 apply → ArgoCD gateway **Restart** |
| `/api/admin/**` 403 | 그룹 지정 전 토큰 | 로그아웃 → 다시 로그인 |

## 8. staging 6) DB 스키마·계정·K8s Secret·시드 (3.1.12)

코드: `envs/staging/db_bootstrap.tf`(Job Pod Identity) + `envs/staging-k8s/db_bootstrap.tf`(비밀번호·Secret·Job) + `envs/staging-k8s/scripts/db-bootstrap/` · Flyway 훅 틀: gitops `base/{svc}/flyway-job.yaml`(3.1.5에서 연결) · 시드: 비공개 `lotte-seed/load/`

| 항목 | 값 |
| --- | --- |
| 스키마 | `catalog_db`·`booking_db`·`user_db`·`payment_db` (utf8mb4 / utf8mb4_0900_ai_ci) |
| 계정 | `{svc}_app`(자기 스키마 SELECT·INSERT·UPDATE·DELETE) · `{svc}_migrator`(자기 스키마 ALL), 모두 `@'%'`·`REQUIRE SSL` |
| 비밀번호 | `random_password` 영숫자 32자 × 8 → **`staging-k8s` tfstate에만** 평문 (2-6 tfstate 거부가 중요한 이유) |
| 앱 Secret (`cinema-staging`) | `{svc}-secrets`: `SPRING_DATASOURCE_PASSWORD` · `{svc}-migrator-secrets`: `FLYWAY_USER`·`FLYWAY_PASSWORD` (팀원은 목록도 볼 수 없음 — 이름은 이 표·문서로 확인) |
| 계정 생성 Job (`cinema-ops`) | `db-bootstrap-<해시8>`: initContainer(aws-cli)가 Pod Identity `cinema-staging-db-bootstrap-pod`로 마스터 Secret 읽기 → mysql 컨테이너가 멱등 SQL 실행·권한 검증. 입력 Secret `db-bootstrap-accounts`. `cinema-ops`는 팀원 조회 범위 밖 |
| TLS 검증 | RDS CA 번들(`truststore.pki.rds.amazonaws.com/ap-northeast-2`)을 ConfigMap `rds-ca-bundle`(cinema-staging·cinema-ops, `rds_ca.tf`)로. db-bootstrap은 `mysql --ssl-mode=VERIFY_IDENTITY --ssl-ca`, Flyway·시드 Job은 `FLYWAY_URL`의 `serverSslCert` → 서버 인증서·호스트 이름 검증 |
| 다시 도는 조건 | 스크립트·비밀번호·RDS 주소·이미지·CA 번들이 바뀌면 Job 이름(해시)이 바뀌어 새로 실행. 마스터 비밀번호 자동 교체(7일)와는 무관 |

### 8-A. 적용

전제: 3.1.3(RDS available, `time_zone=UTC`) 완료 · staging-k8s 3.1.2 적용됨 · **노드가 켜져 있음**(Job 실행, `infra/scripts/cluster-scale.sh up`) · Terraform 자격 증명(0장 사전 조건·2-5 `export-credentials`)

```bash
# 1) staging: Job용 Pod Identity (역할 1 + 정책 1 + 연결 1)
cd infra/terraform/envs/staging
terraform plan -var-file=staging.tfvars -out=plan.out    # 기대: module.db_bootstrap_pod_identity 3개 add. destroy 0
terraform apply plan.out

# 2) staging-k8s: random provider 추가 → init 한 번
cd ../staging-k8s
terraform init -upgrade -backend-config=backend.hcl      # random·http provider 추가 → 한 번
terraform fmt -recursive && terraform validate
terraform plan -var-file=staging-k8s.tfvars -out=plan.out
# 기대: namespace 1(cinema-ops) · random_password 8 · Secret 9(app 4·migrator 4·db-bootstrap-accounts 1) · ConfigMap rds-ca-bundle 2
#       · ServiceAccount 1 · ConfigMap 1 · Job 1. destroy 0
#       Secret data는 (sensitive value)로 가려져야 한다. 값이 보이면 멈춘다
terraform apply plan.out          # Job 완료까지 기다린다 (보통 1~2분, 노드가 막 켜졌으면 이미지 받는 시간 추가)
terraform output db_bootstrap_job
```

- apply가 `timeout while waiting for resource ... db-bootstrap`으로 끝나면: 노드 0대 / 이미지 pull 실패 / Pod Identity 미연결 → 8-D 표
- plan 결과를 PR에 붙일 때 Secret·Job 줄은 `(sensitive value)`인지 다시 확인한다(staging-k8s는 CI plan 대상이 아니라 로컬 plan을 붙인다)

### 8-B. 확인

kubectl만 쓴다(Terraform 자격 증명 불필요). 관리자 컨텍스트로:

```bash
kubectl config use-context cinema-admin
JOB=$(kubectl -n cinema-ops get jobs -l app.kubernetes.io/name=db-bootstrap -o name | tail -1)   # job/db-bootstrap-xxxxxxxx
kubectl -n cinema-ops logs $JOB -c fetch-master     # OK: 마스터 접속 정보 준비 (user=cinema_master)
kubectl -n cinema-ops logs $JOB -c bootstrap        # 스키마 4·계정 8 표 + 마지막 줄 PASS
kubectl -n cinema-staging get secrets -l app.kubernetes.io/managed-by=terraform   # 8개 (값은 보지 않는다)
```

- Job 이름은 `terraform output db_bootstrap_job`으로도 볼 수 있지만, 그때는 Terraform 자격 증명(0장 사전 조건·2-5 `export-credentials`)이 필요하다. `AWS_PROFILE=cinema-admin`만 걸린 터미널에서 `terraform output`을 쓰면 `AssumeRoleTokenProvider session option not set` 오류
- Job이 여러 개 보이면(입력이 바뀌어 다시 돈 경우) Terraform이 지금 관리하는 것은 최신 1개다. 이전 Job은 apply 때 지워진다

팀원(`cinema-dev`)으로:

```bash
kubectl get secrets -n cinema-staging                  # Forbidden (AmazonEKSViewPolicy에 secrets 없음 — 이름 목록도 안 보임)
kubectl get pods -n cinema-ops                         # Forbidden (관리자 namespace)
```

- [ ] bootstrap 로그 `time_zone=UTC · require_secure_transport=1`, 마지막 줄 `PASS: 스키마 4 · 계정 8 · 권한 확인 완료`
- [ ] Secret 8개(`{svc}-secrets` 4 · `{svc}-migrator-secrets` 4)
- [ ] 팀원: `cinema-staging` Secret 목록·내용 Forbidden, `cinema-ops` Forbidden
- [ ] 두 번째 plan(staging-k8s) No changes — Job이 다시 만들어지지 않음
- [ ] 로그·PR·노션 어디에도 비밀번호가 없음

### 8-C. 시드 적재 (catalog_db, 1회)

시드는 비공개 `lotte-seed/load/`에만 있다(이 레포·gitops·노션에 올리지 않는다). 실행은 관리자(Job·ConfigMap 생성 필요), 한채연과 같이.

전제: 8-B 통과(`catalog-migrator-secrets` 있음) · 노드 켜짐 · SeatSAC main 최신(catalog Flyway V202610211700 포함)

```bash
export AWS_PROFILE=cinema-admin
kubectl config use-context cinema-admin
cd <lotte-seed 클론 경로>/load
export REPO=<SeatSAC 클론 경로, 레포 루트 절대 경로>       # 예: $HOME/work/SeatSAC (services/ 가 바로 아래 있는 폴더)
ls "$REPO"/services/catalog/src/main/resources/db/migration/V*.sql   # 파일이 보여야 한다
export RDS_HOST=$(aws rds describe-db-instances --db-instance-identifier cinema-staging-mysql \
  --query 'DBInstances[0].Endpoint.Address' --output text)
bash run.sh
# 마지막 줄: 완료: catalog_db 시드 적재·검증 통과
```

- `RDS_HOST`는 `aws rds`로 구한다. `terraform output`은 이 터미널(`AWS_PROFILE`)에서 MFA 오류가 난다(0장 사전 조건·2-5)
- `REPO`는 PC 어디에 클론했든 그 **레포 루트**면 된다. 실행 전 `git -C "$REPO" switch main && git -C "$REPO" pull`

- 일회성 `kubectl apply`(Job·ConfigMap)는 GitOps 예외로 이 절차만 허용한다. 성공하면 run.sh가 Job·ConfigMap을 지운다(실패 시 Job은 남겨 둠 → 로그 확인 후 `kubectl -n cinema-staging delete job catalog-seed-load`)
- Job의 initContainer가 catalog Flyway를 먼저 적용한다. 나중에 3.1.5 Flyway 훅 Job은 이력을 보고 그대로 넘어간다(같은 Flyway 11.7.2)
- 재실행 안전: 이미 들어 있으면 검증만 한다. 다른 데이터가 있으면 손대지 않고 실패한다
- 시드 ConfigMap이 잠깐 `cinema-staging`에 생긴다(팀원 View로 읽힘). 팀 내부라 허용하되 실행 중에만 존재하게 `KEEP=1`은 쓰지 않는다

| 시드 Job 증상 | 원인 | 해결 |
| --- | --- | --- |
| flyway `RSA public key is not available client side (option serverRsaPublicKeyFile not set)` 재시도 후 실패 | flyway 11.7 이미지는 MariaDB Connector/J 2.7.x로 붙는다. 2.x는 `sslMode`(3.x 옵션)를 무시해 TLS가 켜지지 않고, TLS 없는 `caching_sha2_password` 인증이 실패 | `seed-load-job.yaml`의 `FLYWAY_URL`을 `...?useSsl=true&serverSslCert=/etc/rds-ca/rds-ca.pem&trustServerCertificate=false&disableSslHostnameVerification=false&sslMode=verify-full`로 + ConfigMap `rds-ca-bundle`을 `/etc/rds-ca`에 마운트(서버 인증서 검증). seed 컨테이너 mysql도 `--ssl-mode=VERIFY_IDENTITY --ssl-ca=/etc/rds-ca/rds-ca.pem` (10/24) |
| `timed out waiting for the condition` 까지 10분 대기 | run.sh가 Job 완료만 기다림(실패해도 끝까지 대기) | 다른 터미널에서 `kubectl -n cinema-staging get pods -l job-name=catalog-seed-load`로 바로 상태 확인 |

- [ ] `verify.sql` 15개 항목 일치 (극장 132 · 상영관 39 · 좌석 6,767 · 회차 8,360 · 영화 22, UTC 날짜 ≠ 영업일 228건)
- [ ] `kubectl -n cinema-staging get job,configmap | grep catalog-seed` → 없음

### 8-D. 비밀번호 교체·주의·문제

**비밀번호 교체** (유출 의심, tfstate가 밖으로 나갔을 때)

```bash
cd infra/terraform/envs/staging-k8s
terraform apply -var-file=staging-k8s.tfvars -replace='random_password.db["booking_app"]'   # 계정별. 전부면 8개 모두 -replace
# → Secret 갱신 + Job 해시 변경으로 ALTER USER 실행. 그 순간부터 이전 비밀번호로는 새 연결이 안 된다
```

- 앱 파드는 Secret을 기동 때만 읽는다 → 교체 직후 해당 서비스 재시작(ArgoCD에서 Restart, 또는 3.1.5 이후 gitops 커밋). 기존 커넥션 풀은 끊기기 전까지 동작하지만 새 연결은 실패하므로 **바로** 재시작한다. 데모 주간 금지

| 증상 | 원인 | 해결 |
| --- | --- | --- |
| apply가 Job 대기 중 timeout, 파드 `Pending` | 노드 0대 | `cluster-scale.sh up` 후 다시 apply (Job은 tainted → 재생성) |
| fetch-master `AccessDeniedException ... GetSecretValue` | Pod Identity 연결 없음(staging apply 전) / SA·namespace 이름 다름 | 8-A 1) staging apply, `aws eks list-pod-identity-associations --cluster-name cinema-staging-eks`에 `cinema-ops/db-bootstrap` |
| fetch-master `Unable to locate credentials` | eks-pod-identity-agent 애드온 없음·파드 기동 직후 | `kubectl get ds -n kube-system eks-pod-identity-agent`, Job 다시(`-replace=kubernetes_job_v1.db_bootstrap`) |
| fetch-master `FAIL: 마스터 Secret 형식` (**마스터 Secret 형식**) | Secret JSON에 `\\`(백슬래시) 외의 이스케이프(`\uXXXX` 등)가 있음. RDS for MySQL 비밀번호는 `/` `"` `@` 공백을 못 쓰므로 정상이면 생기지 않는다. 백슬래시는 그대로 지원 | 다음 자동 교체를 기다리거나 RDS로 즉시 교체(관리자): `aws rds modify-db-instance --db-instance-identifier cinema-staging-mysql --rotate-master-user-password --apply-immediately` → 1~2분 뒤 Job 다시. Secrets Manager `rotate-secret`은 쓰지 않는다(RDS 관리형 Secret은 RDS 명령으로 교체) |
| bootstrap `ERROR 2026 … certificate verify failed` | CA 번들이 RDS 인증서와 안 맞음(번들 갱신 전·지역 다름) / `DB_HOST`가 엔드포인트가 아닌 IP·별칭 | `terraform apply`로 `rds-ca-bundle` 갱신, `DB_HOST`는 RDS 엔드포인트 그대로 |
| bootstrap `ERROR 2003`/타임아웃 | 노드 SG → RDS 3306 규칙·서브넷 | staging `security_groups.tf`, 5-3 클러스터 안 접속 확인 |
| bootstrap `ERROR 1045` (master) | 마스터 Secret이 교체 중(rotation 진행) | 1~2분 뒤 Job 다시 |
| 앱 `Access denied for user '{svc}_app'` | Secret 교체 후 재시작 안 함 / Job 실패 | 위 교체 절차, Job 로그 PASS 확인 |
| 앱 `Connections using insecure transport are prohibited` | JDBC URL에 `sslMode=REQUIRED` 없음 | `{svc}-config`의 `SPRING_DATASOURCE_URL` 확인(service-runtime-contract 3장) |

- 이 폴더 destroy(11장) 때 계정·스키마는 RDS에 남는다(Job은 만들기만 함). staging까지 destroy하면 RDS와 함께 사라진다
- `cinema-ops`를 `developer_namespaces`에 넣으면 precondition이 막는다(입력 Secret 보호)


---

## 8-1. 가동 정책 — 노드 0대 스케줄·Budgets (3.1.14, OPS-13)

코드: `infra/terraform/envs/staging/cluster_schedule.tf`(EventBridge Scheduler, **정시 실행**) · `.github/workflows/cluster-schedule.yml`(백업·수동) · `infra/scripts/cluster-scale.sh` · `infra/terraform/global/{cluster_schedule,budgets}.tf`

> 10/27 변경: GitHub Actions schedule이 몇 시간씩 늦거나 빠졌다(10/23 끄기 4시간·10/26 켜기 6시간 지연, 10/26 끄기 누락). 정시 실행을 **EventBridge Scheduler**(KST 타임존, `eks:UpdateNodegroupConfig` 직접 호출)로 옮기고, 워크플로는 30분 뒤 백업으로 남겼다. 둘 다 멱등이라 같이 돌아도 안전하다

| 시각 (KST) | 동작 | 비고 |
| --- | --- | --- |
| 월~금 08:23 | **EventBridge** 노드그룹마다 desired 1 | 노드 Ready까지 2~3분, 파드 Ready까지 5~10분 더 |
| 월~금 08:53 | GitHub 백업 `up` (이미 켜져 있으면 no-op) | 월~금 07~10시(KST)에 도착한 실행만 동작 — 토요일 아침에 늦게 온 켜기는 건너뜀 |
| 월~금 23:07 | **EventBridge** desired 0 | 금요일 밤에 꺼져 월요일 아침까지 0대 |
| 월~금 23:37 · 다음 날 00:37 | GitHub 백업 `down` (no-op 또는 대신 끄기) | 월~금 22~23시·화~토 0~5시(KST)에 도착한 실행만 동작 — 늦게 온 끄기가 근무 시간에 끄지 않게 |
| 데모 주간 11/16~20 | 예약 끄기 멈춤 (24시간) | EventBridge 끄기는 `cluster_schedule_demo_start` 0시에 끝남(end_date), 백업도 날짜로 건너뜀. 발표 후 수동 `down` |
| 임시로 밤에도 켜 둠 | `envs/staging` tfvars `cluster_keep_on = true` → apply | EventBridge 끄기 DISABLED → 백업도 그 상태를 보고 건너뜀. 끝나면 `false` → apply. (예전 Variables `CLUSTER_KEEP_ON`은 쓰지 않음 — 지운다) |

### 8-1-1. global 적용 (역할·Budgets)

```bash
export AWS_PROFILE=cinema-admin
cd infra/terraform/global
cp budget.auto.tfvars.example budget.auto.tfvars    # 받을 메일 입력 (커밋 안 됨)
terraform plan -var-file=global.tfvars -out=plan.out
# 기대: 역할 cinema-gha-cluster-schedule 1 + 인라인 정책 1 + Budgets 2 (cinema-monthly·cinema-daily). destroy 0
terraform apply plan.out
terraform output github_secrets        # AWS_CLUSTER_SCHEDULE_ROLE_ARN → 레포 Actions Secrets에 등록
```

**EventBridge Scheduler (10/27 추가, envs/staging)** — global 다음(백업 워크플로가 `scheduler:ListSchedules`를 쓰므로 global 먼저)

```bash
# 터미널 B (0장 Terraform 자격 증명)
# 1) global — 반드시 apply까지 끝낸 뒤 staging으로 (안 하면 백업 워크플로가 scheduler:ListSchedules AccessDenied)
cd infra/terraform/global
terraform plan -var-file=global.tfvars -out=plan.out
# 기대: aws_iam_role_policy.gha_cluster_schedule 1 change (SchedulerRead 문 추가). add·destroy 0
terraform apply plan.out
rm -f plan.out

# 2) envs/staging
cd ../envs/staging
terraform plan -var-file=staging.tfvars -out=plan.out
# 기대: schedule group 1 · IAM 역할 1 · 인라인 정책 1 · 일정 4(ng-a·ng-c × up·down) = 7 add. destroy 0
#   노드그룹 2 change가 같이 보이면 release_version(AMI) 갱신 — 노드 0대(밤)일 때만 같이 apply, 낮이면 노드 교체가 생긴다
terraform apply plan.out
rm -f plan.out
terraform output cluster_schedules      # {<노드그룹>-up = ENABLED, <노드그룹>-down = ENABLED} × 2
```

- 레포 Variables `CLUSTER_KEEP_ON`이 남아 있으면 지운다(더 이상 읽지 않음)
- `cluster_schedule_demo_start`가 지난 뒤에는 끄기 일정의 end_date가 과거라 **새로 만들기가 거절될 수 있다**(재생성 리허설은 데모 주간 전에)

- Budgets 메일 주소마다 확인 메일은 오지 않는다(바로 발송). 스팸함 확인
- `budget_monthly_usd`·`budget_daily_usd`는 `global.tfvars`에서 팀과 정한 값으로. 평소 하루 비용(컨트롤 플레인·NAT·RDS·Valkey·ALB + 낮 시간 노드)보다 조금 높게 둔다

### 8-1-2. 사람이 직접 켜고 끄기 (워크플로와 같은 스크립트)

```bash
export AWS_PROFILE=cinema-admin
infra/scripts/cluster-scale.sh status
infra/scripts/cluster-scale.sh down
infra/scripts/cluster-scale.sh up        # 노드그룹당 1대
infra/scripts/cluster-scale.sh up 2      # 부하 테스트(OPS-10) 때 노드그룹당 2대 → 끝나면 up 1
```

### 8-1-3. 확인

- [ ] 워크플로가 main에 머지된 뒤 Actions → cluster-schedule → Run workflow `status` 성공 (역할·Secret 확인)
- [ ] 수동 `down` → Summary 표 desired 0 · InService 0, `kubectl get nodes` → No resources
- [ ] 수동 `up` → InService 1씩, `kubectl get nodes` 2대 Ready, ArgoCD 앱이 다시 Healthy
- [ ] EventBridge 실행 확인: 08:23·23:07 직후 `infra/scripts/cluster-scale.sh status`(desired) · CloudWatch 지표 `AWS/Scheduler` `InvocationAttemptCount`·`TargetErrorCount`(이미 같은 값이면 "No changes needed"로 오류 1건 = 정상)
- [ ] 백업 확인: Actions → cluster-schedule 예약 실행 Summary가 "이미 desired" 또는 "늦게 도착 — 건너뜀"
- [ ] `cluster_keep_on = true` apply → `terraform output cluster_schedules`의 `-down`이 DISABLED, 그날 밤 백업 Summary "DISABLED — 백업도 건너뜀" → `false`로 되돌림
- [ ] `aws budgets describe-budgets --account-id <계정ID>` 에 `cinema-monthly`·`cinema-daily`
- [ ] 두 번째 `terraform plan`(staging) No changes — desired 변경은 Terraform이 무시

| 증상 | 원인 | 해결 |
| --- | --- | --- |
| `Not authorized to perform sts:AssumeRoleWithWebIdentity` | main이 아닌 브랜치에서 실행 / Secret 미등록 | main에서 실행, `AWS_CLUSTER_SCHEDULE_ROLE_ARN` 등록 |
| `AccessDeniedException ... eks:UpdateNodegroupConfig` | 클러스터 이름이 `eks_cluster_name`과 다름 | global 변수 확인 후 apply |
| `up`이 10분 넘게 InService 0 | t3.large 용량 부족·SCP·서브넷 IP 부족 | 노드그룹 Health 이슈(`describe-nodegroup`)·ASG 활동 기록 확인 |
| `desired 1 → 0` 출력 후 `AccessDeniedException … DescribeUpdate` (❌, 노드는 꺼짐) | `eks:DescribeUpdate`는 **클러스터 ARN**이 필수 리소스인데 노드그룹 ARN만 허용했음 (10/24 수정) | global `cluster_schedule.tf` `UpdateRead` 문 확인 후 apply |
| 노드그룹 `DEGRADED` | 인스턴스 생성 실패·서브넷 IP 부족·IAM 등 건강 문제 | 끄기는 그대로 진행됨(경고만). 켜기는 중단 → 8-1-4 |
| 예약 실행이 안 보임·몇 시간 늦음 (GitHub) | GitHub schedule은 부하에 따라 늦거나 빠진다(10/23·10/26 실제 발생) / 60일간 레포 활동 없음 | 정시 실행은 EventBridge가 하므로 영향 없음. 늦게 온 백업은 시각 확인으로 건너뜀. 둘 다 안 돌았으면 수동 실행 |
| 08:23·23:07에 노드가 안 바뀜 (EventBridge) | 일정 DISABLED(`cluster_keep_on`)·end_date 지남·역할 권한·노드그룹 이름 바뀜(교체 후 apply 안 함) | `aws scheduler list-schedules --group-name cinema-staging-cluster`, CloudWatch `TargetErrorCount`, staging plan으로 일정 입력값 확인 |
| 백업 `EventBridge 끄기 일정 확인`에서 `AccessDenied scheduler:ListSchedules` | global 정책(SchedulerRead) apply 전 | global apply |
| staging apply `ValidationException: The execution role you provide must allow AWS EventBridge Scheduler to assume the role` | 실행 역할 trust 조건 `aws:SourceArn`이 **일정 그룹 ARN**과 다름(일정 ARN을 넣으면 거절, 10/27) | `cluster_schedule.tf` trust를 `aws_scheduler_schedule_group.cluster.arn`으로. 방금 바꿨으면 IAM 전파 10~20초 뒤 다시 apply |


### 8-1-4. 노드그룹 DEGRADED·변경 거부 복구

`cluster-scale.sh`는 DEGRADED일 때 **끄기(down)는 진행**하고(줄이는 건 문제를 키우지 않고, 밤 끄기가 막히면 비용이 계속 나감), **켜기(up)는 중단**한다(같은 원인으로 인스턴스가 뜨지 않음). EKS가 변경 요청 자체를 거부하면 둘 다 멈추고 이 절을 가리킨다.

```bash
export AWS_PROFILE=cinema-admin
NG=<노드그룹 이름>   # terraform output eks_node_group_names
aws eks describe-nodegroup --cluster-name cinema-staging-eks --nodegroup-name $NG \
  --query 'nodegroup.[status,health.issues]'
```

| health.issues code | 원인 | 조치 |
| --- | --- | --- |
| `AsgInstanceLaunchFailures` | t3.large 용량 부족, SCP, 시작 템플릿 문제 | ASG 활동 기록 확인(`aws autoscaling describe-scaling-activities --auto-scaling-group-name <ASG>`). 용량 부족이면 잠시 뒤 재시도 |
| `InsufficientFreeAddresses` | app 서브넷 IP 부족 | 남는 ENI·파드 정리, 서브넷 확인 |
| `Ec2SecurityGroupNotFound`·`IamInstanceProfileNotFound`·`IamNodeRoleNotFound` 등 | Terraform 밖에서 SG·IAM이 지워짐 | staging `terraform plan`·apply로 다시 만들기 → ACTIVE로 돌아오는지 확인 |
| `AccessDenied` | 노드 역할 정책 변경 | staging apply로 정책 복구 |

- 원인을 고치면 EKS가 상태를 ACTIVE로 되돌린다(수 분). 그 뒤 스크립트를 다시 실행한다
- **밤 끄기가 거부됐는데 바로 못 고칠 때**(비용만 막는 임시 조치): `aws autoscaling update-auto-scaling-group --auto-scaling-group-name <ASG> --min-size 0 --desired-capacity 0`. EKS가 다음 노드그룹 변경 때 원래 값으로 되돌릴 수 있으므로, 다음 날 원인을 고치고 `cluster-scale.sh down`·`up`으로 정상화한다. 실행했다면 14장 실행 기록에 남긴다
- 그래도 안 되면 노드그룹 교체: `terraform apply -replace='module.eks.module.eks_managed_node_group["ng-a"].aws_eks_node_group.this[0]'` (데모 주간 금지, 파드 재배치됨)

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
cd infra/terraform/envs/staging-k8s
# 1) Application 먼저: finalizer로 ArgoCD가 만든 리소스(gateway Ingress → ALB 포함)를 지운다. 컨트롤러가 살아 있어야 한다
terraform destroy -var-file=staging-k8s.tfvars -target=helm_release.argocd_apps
kubectl -n argocd get applications                       # 모두 사라질 때까지 (수 분)
aws elbv2 describe-load-balancers --names cinema-staging-gateway   # LoadBalancerNotFound면 ALB 정리 완료
# 2) 나머지 (ArgoCD·LB Controller·Secret·namespace)
terraform destroy -var-file=staging-k8s.tfvars           # (4-5)
cd ../staging
terraform plan -destroy -var-file=staging.tfvars     # 지워질 목록 확인
terraform destroy -var-file=staging.tfvars
```

- 순서: ArgoCD Application(-target) → staging-k8s 나머지 → staging. 1)을 건너뛰면 ArgoCD가 먼저 지워져 Application의 finalizer가 풀리지 않고(`argocd` namespace가 Terminating에 멈춤) ALB가 남아 staging VPC 삭제가 막힌다
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
| `init`·`plan`에서 `assume role with MFA enabled, but AssumeRoleTokenProvider session option not set` | `AWS_PROFILE`(MFA 프로필)을 Terraform이 직접 쓰려 함. Terraform은 MFA 입력을 못 받는다 | 2-5 "Terraform 실행 전": `export-credentials`로 환경변수 설정 → `unset AWS_PROFILE` |
| `ExpiredToken`·`The security token included in the request is expired` | 위에서 넘긴 임시 키가 8시간 지나 만료 | `export-credentials` 다시 실행 |
| `init`에서 `bucket - The name of the S3 bucket` 입력 요구 | `-backend-config=backend.hcl` 누락, `backend.hcl` 없음(커밋하지 않는 파일 — clone·pull 직후) 또는 bootstrap 전 | 1장 "다음 단계 준비"로 `backend.hcl` 만들기 → `init -backend-config=backend.hcl` |
| `CreateOpenIDConnectProvider ... 409 EntityAlreadyExists` | 계정에 GitHub OIDC 공급자가 이미 있음 | 2-2 표대로 import 또는 data 참조 |
| `Resource already managed by Terraform` (import 시) | 그 리소스를 이미 다른(또는 같은) state가 관리 중 | `terraform state list`로 어느 폴더 state인지 확인 → 12장 |
| `AccessDenied ... sts:AssumeRole` (팀원) | `global.tfvars` 사용자 목록에 없음 / MFA 없이 시도 / 이름 대소문자 | tfvars 수정 후 apply, `mfa_serial` 확인 |
| Actions `Not authorized to perform sts:AssumeRoleWithWebIdentity` | `github_owner`·`github_repo` 대소문자 불일치, 이벤트(PR·main) 불일치 | GitHub 표기와 맞춰 global apply |
| Actions `Could not load credentials from any providers` | Secret 미등록 또는 `permissions: id-token: write` 없음 | 2-4 Secrets 등록, 워크플로 permissions 확인 |
| `explicit deny ... service control policy` | 학원 SCP | 학원에 문의 |
| NAT를 지웠는데 EIP가 남음 | Terraform 밖 EIP(EC2 연결)일 가능성 | `aws ec2 describe-addresses`로 태그·연결 대상 확인 |
| `kubectl` `You must be logged in to the server (Unauthorized)` | EKS를 `cinema-admin`이 아닌 주체로 만들었거나 kubeconfig 프로필이 다름 | `aws sts get-caller-identity`로 역할 확인, `update-kubeconfig --profile cinema-admin` 다시. Access Entry 누락이면 staging apply |
| staging-k8s plan `Error: Kubernetes cluster unreachable` | 노드·클러스터 없음 또는 `AWS_PROFILE` 미설정(get-token 실패) | `export AWS_PROFILE=cinema-admin`, 클러스터 존재 확인 |
| LB Controller 로그 `AccessDenied`·`no EC2 IMDS role found` | Pod Identity 연결 없음 / ServiceAccount 이름 다름 / pod-identity-agent 없음 | 4-4의 `list-pod-identity-associations`, SA 이름 `aws-load-balancer-controller` 확인 후 파드 재시작 |
| `Reference to undeclared input variable "budget_emails"` (global) | 3.1.14 변수가 없는 브랜치에서 plan | `git merge origin/main` (5-0) |
| staging plan `no matching SNS topic found` | global(`cinema-alerts`)을 먼저 apply하지 않음 | 5-1 global apply 후 다시 |
| 경보 메일이 안 옴 | SNS 구독 미확인(`PendingConfirmation`) | 확인 메일의 Confirm 클릭, 스팸함 확인 (5-1) |
| 클러스터에서 MySQL `ERROR 2003`·Valkey 타임아웃 | SG(노드 SG → 3306·6379) 또는 Valkey에 `--tls` 누락 | `security_groups.tf` 규칙 확인, `--tls` 붙여 다시 |
| 컨텍스트가 2개인데 둘 다 같은 역할로 접속 (`get-contexts`의 AUTHINFO가 같음) | `--user-alias` 없이 `update-kubeconfig`를 두 번 실행해 사용자 항목이 덮어써짐 | 컨텍스트·사용자 삭제 후 `--alias`·`--user-alias`를 `cinema-dev`/`cinema-admin`으로 다시 (4-2·4-4). `kubectl auth whoami`로 확인 |
| `aws eks update-kubeconfig` `Unknown options: ... kubectl` | 여러 명령을 한 줄로 붙여 넣음(`\` 뒤 줄바꿈 없음) | 한 줄씩 따로 실행 (4-4) |
| `kubectl` `dial tcp ...:443: i/o timeout` | 내 공인 IP가 EKS API 허용 목록에 없음 | 4-6 IP 추가 후 apply |
| `kubectl` `exec plugin` 오류·멈춤 | `aws eks get-token`이 MFA를 묻지 못함 | `aws sts get-caller-identity --profile cinema-dev`로 MFA 세션 먼저 |
| `build-push` `main에서만 실행한다` | main이 아닌 브랜치에서 Run workflow | 브랜치를 main으로 골라 다시 실행 |
| `build-push` gitops 단계 `GITOPS_APP_ID 또는 GITOPS_APP_PRIVATE_KEY 가 없어 … 건너뛴다` | Variable·Secret 미등록, 또는 키를 **Variable**에 넣음(Secret에서만 읽는다) | 6-A-2. Variable에 넣었던 키는 지우고 앱에서 새 키로 교체 |
| `create-github-app-token` `Not Found`·`Integration not found`·`A JSON web token could not be decoded` | App ID 대신 Client ID를 넣음, 키 일부만 복사, 앱이 SeatSAC-gitops에 설치 안 됨 | App ID(숫자) 확인, `.pem` 전체 다시 붙여 넣기, 6-A-3의 3 |
| `build-push` `gitops push 실패` · `GH013: Repository rule violations` · `403 Resource not accessible by integration` | gitops main ruleset 우회 목록에 앱 없음, 또는 앱 권한이 Contents 읽기만 | 6-A-3의 6, 앱 Permissions → Contents: Read and write(바꾸면 설치 화면에서 권한 변경 승인) |
| `build-push` `images에 name: cinema/{svc} 항목이 없다` | gitops overlay `images:`의 `name`이 base 매니페스트 `image:`와 다름 | overlay를 `name: cinema/{svc}`로 고치는 gitops PR |
| `build-push` Trivy 단계 실패 | CRITICAL + 수정 버전 있는 취약점 | 6-A-6 |
| PR `image` 실패, `image-build` 로그에 `COPY failed`·`not found` | Dockerfile 경로 오류, 줄 끝 주석(`COPY a b # 주석`) | 런타임 계약 4장 템플릿대로. 주석은 따로 한 줄 |
| PR `image` 실패 `Dockerfile USER가 숫자 UID(0 아님)가 아니다` | `USER app`처럼 이름이거나 USER 없음 | `USER 10001` (K8s `runAsNonRoot`는 숫자 UID여야 확인 가능) |
| ruleset에서 필수 검사 `image`가 목록에 없음 | 아직 한 번도 실행되지 않음 | 아무 PR에서 `pr-check`가 한 번 돈 뒤 추가 |
| `tag invalid: The image tag ... already exists ... immutable` | 같은 태그로 다시 push (보통 일어나지 않음: 있으면 건너뜀) | 같은 SHA면 정상 — 새 커밋으로 |
| `build-push` `image-tag/kustomization.yaml 이 없다`·`newName이 있다` | gitops가 이전 구조(overlay `images`에 `newName`·`newTag`) | gitops에 Public 구조 PR(`chore/3.1.4-gitops-public`) 머지 |
| 빌드 `Could not find org.apache.tomcat.embed:…:10.1.58` 등 | Trivy가 알려 준 수정 버전이 실제로는 배포되지 않음(릴리스 투표 실패) | Maven Central에 있는 다음 버전으로 (6-A-6) |
| 파드 기동 실패 `Could not resolve placeholder 'DB_HOST'` | ConfigMap `cinema-infra-endpoints`가 없거나 envFrom에서 빠짐 | 6-C-1 apply, gitops `base/{svc}` envFrom 확인 |
| 파드 `CreateContainerConfigError` `configmap "cinema-infra-endpoints" not found` | 위와 같음(Flyway Job 포함) | 6-C-1 |
| 파드 `ImagePullBackOff` 이미지 `cinema/{svc}:…`(레지스트리 없음) | ArgoCD Application에 `kustomize.images`가 없음 — 서비스 앱을 gitops나 손으로 만듦 | gitops `apps/staging`에서 서비스 앱 제거, 6-C-2 Terraform으로 |
| 파드 `ImagePullBackOff` 태그 `CHANGE_ME` | build-push가 아직 그 서비스 태그를 커밋하지 않음 | `build-push` 수동 실행 `services={svc}` |
| staging-k8s apply `job: cinema-ops/db-bootstrap-… is not in complete state`, describe에 `DeadlineExceeded`, 파드 로그 없음 | 노드 0대(23:07 끄기 이후)라 파드가 Pending으로 600초를 넘김 | `cluster-scale.sh up` → 노드 Ready → 다시 apply (Job은 tainted로 교체) |
| `helm_release.argocd` `context deadline exceeded`·`timed out waiting for the condition` | 노드 0대 / 이미지 pull 실패 / 노드 메모리 부족 | `kubectl -n argocd get pods`·`describe pod`. 노드가 있으면 다시 apply |
| Helm `chart "…" version "…" not found` | tfvars·gitops 차트 버전이 실제로 없음 | `helm search repo … --versions`로 있는 버전 → PR |
| 플랫폼 앱 `InvalidSpecError … project cinema … does not exist` | AppProject가 늦게 생김 | 정상이면 몇 초 뒤 사라진다. 계속되면 gitops `project.yaml` sync-wave `-20` 확인 |
| 서비스 앱 `no matches for kind "Rollout"`·`"PodMonitor"`, `Retrying attempt #…` | 플랫폼 CRD 설치 전 | 재시도로 맞춘다(최대 약 20분). 10회 다 쓰면 관리자가 화면에서 Sync |
| `{svc}-flyway` Job 실패 → 앱 sync 중단 | `{svc}-migrator-secrets`·`rds-ca-bundle`·`cinema-infra-endpoints` 없음 / 마이그레이션 SQL 오류 | `kubectl -n cinema-staging logs job/{svc}-flyway -c flyway`. 고친 SQL은 새 V 번호로 |
| gateway Ingress ADDRESS가 비어 있음 | LB Controller 권한 / SG 이름 `cinema-staging-alb`을 못 찾음 / 서브넷 태그 | `kubectl -n kube-system logs deploy/aws-load-balancer-controller \| tail`, `kubectl -n cinema-staging describe ingress gateway` |
| `argocd` namespace가 Terminating에서 멈춤 | 11장 1)을 건너뛰어 Application finalizer가 남음 | 관리자: ArgoCD를 다시 apply해 Application을 정상 삭제하거나, 마지막 수단으로 Application의 finalizer 제거 |
| plan에 `-/+ replace`가 나옴 | 교체가 필요한 값 변경(서브넷 CIDR, SG description 등) | 멈추고 원인 확인. 의존 리소스까지 다시 만들어질 수 있음 |

---

## 14. 실행 기록

> 시각은 KST. "PR"은 그 단계 코드가 main에 들어간 PR. 소요는 apply 시작~확인 끝(미측정은 다음 실행 때 채운다)

| 날짜 | 실행자 | 단계 (PR) | 소요 | 문제 | 런북 수정 |
| --- | --- | --- | --- | --- | --- |
| 10/20 | 김종식 | 1 bootstrap (state 버킷) | 5초 | `init`에서 버킷 이름 입력 요구 | 1장 "다음 단계 준비"(backend.hcl) 추가 |
| 10/20 | 김종식 | 3 네트워크 — VPC·서브넷·SG (#1, 10/20 22:19) | 미측정 | 없음 | — |
| 10/21 | 김종식 | 2 global — 팀 역할·GitHub OIDC·CI 역할 (#2, 10/21 14:58) | 미측정 | ① `CreateOpenIDConnectProvider 409 EntityAlreadyExists`(계정에 OIDC 공급자가 이미 있음) ② import 시 `Resource already managed by Terraform` ③ (추정) Terraform이 MFA 프로필을 직접 써서 `AssumeRoleTokenProvider session option not set` | 2-2(OIDC 공급자 확인 표)·12장(state 이동)·13장 추가, 0장·2-5에 "Terraform 실행 전 `export-credentials` → `unset AWS_PROFILE`" |
| 10/22 | 김종식 | 4 EKS — staging(EKS·노드그룹·Access Entry) · staging-k8s(namespace·RBAC·LB Controller) (#13, 10/22 20:25) | 미측정 | (추정) API 공개 엔드포인트에 관리자 공인 IP 필요 | 4-6(허용 IP는 tfvars·Secret으로, 커밋 금지) |
| 10/23 | 김종식 | 8-1 가동 정책 — global(cluster-schedule 역할·Budgets) (#17, 10/23 11:42) | 미측정 | 없음 | — |
| 10/23 | 김종식 | 5 RDS·Valkey — global SNS → staging (#24, 10/23 15:08) | 미측정 | 없음 | — |
| 10/23 | 김종식 | 7-1~7-3 CloudFront·S3·frontend-deploy 1차 (#25, 10/23 15:47) | 미측정 | 없음 (7-4 ALB 원본은 gateway·Cognito 뒤로 보류) | — |
| 10/24 01:59~02:05 | 김종식 | 8-1-3 cluster-schedule 수동 `status`·`down`·`up` (Actions #1~#3) | 수 분 | `down`에서 desired 1→0 후 `AccessDeniedException … eks:DescribeUpdate`(노드는 꺼짐). `DescribeUpdate`는 클러스터 ARN이 필수 리소스 | 8-1 문제 해결 표에 행 추가 → #41(10/26 14:17)로 정책 수정 후 global apply |
| 10/24~25 | 김종식 | 8-A·8-B DB 스키마·계정·Secret·RDS CA — staging-k8s (#35, 10/24 22:13) | 미측정 (Job Complete 10/25 20:36) | ① `job: cinema-ops/db-bootstrap-… is not in complete state` / `DeadlineExceeded` — 노드 0대(23:07 끄기 이후) ② 재apply 때 plan이 `infra_endpoints` ConfigMap destroy — 로컬 main이 #35에 멈춰 #36 미반영 ③ kubernetes 프로바이더 `Unauthorized` — export한 자격 증명 만료 ④ Secret 확인 명령 오타(`grep 'secrets$'` → `grep -- '-secrets$'`) | 13장: "Job not in complete state → `cluster-scale.sh up` 후 재apply", "`Unauthorized` = 자격 증명 만료 → 다시 export", plan 전 `git pull`·`git log -1` 확인. 8-B 확인 명령 수정 |
| 10/25 | 김종식 · 한채연 | 8-C 시드 적재 (lotte-seed `load/run.sh`) | 미측정 | `RDS_HOST 필요` — 환경변수 없이 실행 | 8-C에 `RDS_HOST=$(kubectl … get cm cinema-infra-endpoints -o jsonpath='{.data.DB_HOST}') REPO=… bash run.sh` 명령. **PASS: 시드 검증 통과 (극장 132 · 상영관 39 · 좌석 6,767 · 회차 8,360 · 영화 22)** |
| 10/26 | 김종식 | 6-A ECR·build-push (#36, 10/26 11:42) · 6-C-1 엔드포인트 ConfigMap | 미측정 | ① build-push `image-tag/kustomization.yaml 이 없다` — gitops PR #2(Public 대응)가 아직 미머지 ② (사전) 의존성 Trivy CRITICAL·Maven에 없는 tomcat 10.1.58·OrbStack 꺼짐 | 6-A-4·6-A-6: gitops PR 먼저 머지, Maven Central에서 버전 확인, Docker 없이 `initiate-layer-upload`로 push 거부 확인 |
| 10/26 | 김종식 | 8-1 global — `DescribeUpdate` 권한 수정 (#41, 10/26 14:17) | 미측정 | 첫 global plan에 `aws_sns_topic_subscription.alerts_email[0]` add — 다른 브랜치(ci/3.1.4)에서 plan함 | 13장: "plan은 최신 main(또는 해당 PR 브랜치)에서" |
| 10/26 | 김종식 | 6-C-2 ArgoCD·root 앱·서비스 Application — staging-k8s 한 번의 apply (#42, 10/26 16:15 · gitops #3) | apply 미측정 · sync 약 20분 | ① 앱 6개 `InvalidSpecError: project cinema which does not exist`, root OutOfSync — root가 gitops #3 머지 전 리비전으로 sync를 시작해 교착 ② `cinema-catalog` OutOfSync·파드 2분마다 재생성 — `replicas: 1`(selfHeal) ↔ HPA, JVM 기동 CPU가 HPA를 다시 4로 ③ CodeRabbit: 6-C-1대로 apply하면 ArgoCD까지 생성 | 13장: root 교착 → ArgoCD 화면 Terminate·Hard Refresh·Sync. 6-C-1을 확인 전용으로. gitops #4(HPA 대상 `replicas` 제거·scaleUp 1분 1개), 11장 destroy 순서(`-target=helm_release.argocd_apps` 먼저) |
| 10/27 02:45 | 김종식 | 8-1-2 수동 `down` | 수 분 | 예약 끄기(10/26 23:07) **실행 자체가 없음**. 예약 실행 지연: 10/23 끄기 약 4시간, 10/26 켜기 약 2~6시간 | 8-1 상단에 변경 경위, 문제 해결 표 "예약 실행이 안 보임·몇 시간 늦음" |
| 10/27 | 김종식 | 8-1-1 EventBridge Scheduler — global(SchedulerRead) → staging (#49) | 미측정 | ① staging plan에 노드그룹 2 change — AMI `release_version` 1.36.5-20261019 → 20261023(모듈의 최신 AMI 조회), 노드 0대라 그대로 적용 ② `ValidationException: The execution role you provide must allow AWS EventBridge Scheduler to assume the role` — trust `aws:SourceArn`에 일정 ARN을 넣음(일정 그룹 ARN이어야 함) ③ 끄기 일정 `end_date` 영구 diff(`…T15:00:00Z` ↔ `…T00:00:00+09:00`) | 8-1-1 EventBridge 절차(global apply 후 staging), 문제 해결 표 `ValidationException` 행. 코드: trust = 일정 그룹 ARN, `end_date`는 UTC(Z)로 |
| ⬜ | 김종식 | 6-D 블루/그린 — 6-D-0 분석 회차 ID · 6-D-2 정상 배포 · 6-D-3 실패 버전 시연 | | | |
| ⬜ | 김종식 | 6-B SQS·Lambda (3.1.8·3.1.9) · 7 Cognito · 7-4 CloudFront `/api` | | | |
| ⬜ | 김종식 | 11·12 삭제·복구 리허설 (3.1.15, 11/11~12) — **전체 재생성 소요 측정** | | | |
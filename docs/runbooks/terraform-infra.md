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
            └─ 6) DB 스키마·계정·K8s Secret (3.1.12, staging-k8s) → 시드 적재 → ArgoCD 부트스트랩(3.1.5)
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
docker compose -f infra/compose/docker-compose.yml exec mysql mysql -uroot -proot -e "SELECT @@global.time_zone, NOW(), UTC_TIMESTAMP();"
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

## 6. staging 4) ECR·SQS·Lambda (3.1.4·3.1.8·3.1.9) — ⬜

- [ ] ECR 저장소 `cinema/{service}`: 태그 불변(`IMMUTABLE`) + 저장소 정책으로 `cinema-gha-ecr-push`만 push

## 7. staging 5) Cognito·CloudFront·S3 (3.1.11) — ⬜

- [ ] `cinema-gha-frontend-deploy`의 CloudFront 무효화 권한을 배포 ARN으로 좁힘 (global 수정)

## 8. staging 6) DB 스키마·계정·K8s Secret·시드 (3.1.12)

코드: `envs/staging/db_bootstrap.tf`(Job Pod Identity) + `envs/staging-k8s/db_bootstrap.tf`(비밀번호·Secret·Job) + `envs/staging-k8s/scripts/db-bootstrap/` · Flyway 훅 틀: gitops `base/{svc}/flyway-job.yaml`(3.1.5에서 연결) · 시드: 비공개 `lotte-seed/load/`

| 항목 | 값 |
| --- | --- |
| 스키마 | `catalog_db`·`booking_db`·`user_db`·`payment_db` (utf8mb4 / utf8mb4_0900_ai_ci) |
| 계정 | `{svc}_app`(자기 스키마 SELECT·INSERT·UPDATE·DELETE) · `{svc}_migrator`(자기 스키마 ALL), 모두 `@'%'`·`REQUIRE SSL` |
| 비밀번호 | `random_password` 영숫자 32자 × 8 → **`staging-k8s` tfstate에만** 평문 (2-6 tfstate 거부가 중요한 이유) |
| 앱 Secret (`cinema-staging`) | `{svc}-secrets`: `SPRING_DATASOURCE_PASSWORD` · `{svc}-migrator-secrets`: `FLYWAY_USER`·`FLYWAY_PASSWORD` (팀원은 목록도 볼 수 없음 — 이름은 이 표·문서로 확인) |
| 계정 생성 Job (`cinema-ops`) | `db-bootstrap-<해시8>`: initContainer(aws-cli)가 Pod Identity `cinema-staging-db-bootstrap-pod`로 마스터 Secret 읽기 → mysql 컨테이너가 멱등 SQL 실행·권한 검증. 입력 Secret `db-bootstrap-accounts`. `cinema-ops`는 팀원 조회 범위 밖 |
| 다시 도는 조건 | 스크립트·비밀번호·RDS 주소·이미지가 바뀌면 Job 이름(해시)이 바뀌어 새로 실행. 마스터 비밀번호 자동 교체(7일)와는 무관 |

### 8-A. 적용

전제: 3.1.3(RDS available, `time_zone=UTC`) 완료 · staging-k8s 3.1.2 적용됨 · **노드가 켜져 있음**(Job 실행, `infra/scripts/cluster-scale.sh up`) · Terraform 자격 증명(0장 `export-credentials`)

```bash
# 1) staging: Job용 Pod Identity (역할 1 + 정책 1 + 연결 1)
cd infra/terraform/envs/staging
terraform plan -var-file=staging.tfvars -out=plan.out    # 기대: module.db_bootstrap_pod_identity 3개 add. destroy 0
terraform apply plan.out

# 2) staging-k8s: random provider 추가 → init 한 번
cd ../staging-k8s
terraform init -upgrade -backend-config=backend.hcl
terraform fmt -recursive && terraform validate
terraform plan -var-file=staging-k8s.tfvars -out=plan.out
# 기대: namespace 1(cinema-ops) · random_password 8 · Secret 9(app 4·migrator 4·db-bootstrap-accounts 1)
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

- Job 이름은 `terraform output db_bootstrap_job`으로도 볼 수 있지만, 그때는 0장 Terraform 자격 증명(`export-credentials`)이 필요하다. `AWS_PROFILE=cinema-admin`만 걸린 터미널에서 `terraform output`을 쓰면 `AssumeRoleTokenProvider session option not set` 오류
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

- `RDS_HOST`는 `aws rds`로 구한다. `terraform output`은 이 터미널(`AWS_PROFILE`)에서 MFA 오류가 난다(0장)
- `REPO`는 PC 어디에 클론했든 그 **레포 루트**면 된다. 실행 전 `git -C "$REPO" switch main && git -C "$REPO" pull`

- 일회성 `kubectl apply`(Job·ConfigMap)는 GitOps 예외로 이 절차만 허용한다. 성공하면 run.sh가 Job·ConfigMap을 지운다(실패 시 Job은 남겨 둠 → 로그 확인 후 `kubectl -n cinema-staging delete job catalog-seed-load`)
- Job의 initContainer가 catalog Flyway를 먼저 적용한다. 나중에 3.1.5 Flyway 훅 Job은 이력을 보고 그대로 넘어간다(같은 Flyway 11.7.2)
- 재실행 안전: 이미 들어 있으면 검증만 한다. 다른 데이터가 있으면 손대지 않고 실패한다
- 시드 ConfigMap이 잠깐 `cinema-staging`에 생긴다(팀원 View로 읽힘). 팀 내부라 허용하되 실행 중에만 존재하게 `KEEP=1`은 쓰지 않는다

| 시드 Job 증상 | 원인 | 해결 |
| --- | --- | --- |
| flyway `RSA public key is not available client side (option serverRsaPublicKeyFile not set)` 재시도 후 실패 | flyway 이미지는 MariaDB 드라이버로 붙는데 URL의 `sslMode=REQUIRED`(MySQL 드라이버 값)를 모름 → TLS 없이 접속 → `caching_sha2_password` 실패 | `seed-load-job.yaml`의 `FLYWAY_URL`을 `...?sslMode=trust&connectionTimeZone=UTC`로 (10/24) |
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
| fetch-master `FAIL: 마스터 Secret 형식` (**마스터 Secret 형식**) | 자동 교체된 마스터 비밀번호에 `"`·`\`가 섞임(스크립트가 안전하게 거부) | 다음 자동 교체를 기다리거나 `aws secretsmanager rotate-secret --secret-id <rds_master_secret_arn>`(관리자) 후 Job 다시 |
| bootstrap `ERROR 2003`/타임아웃 | 노드 SG → RDS 3306 규칙·서브넷 | staging `security_groups.tf`, 5-3 클러스터 안 접속 확인 |
| bootstrap `ERROR 1045` (master) | 마스터 Secret이 교체 중(rotation 진행) | 1~2분 뒤 Job 다시 |
| 앱 `Access denied for user '{svc}_app'` | Secret 교체 후 재시작 안 함 / Job 실패 | 위 교체 절차, Job 로그 PASS 확인 |
| 앱 `Connections using insecure transport are prohibited` | JDBC URL에 `sslMode=REQUIRED` 없음 | `{svc}-config`의 `SPRING_DATASOURCE_URL` 확인(service-runtime-contract 3장) |

- 이 폴더 destroy(11장) 때 계정·스키마는 RDS에 남는다(Job은 만들기만 함). staging까지 destroy하면 RDS와 함께 사라진다
- `cinema-ops`를 `developer_namespaces`에 넣으면 precondition이 막는다(입력 Secret 보호)


---

## 8-1. 가동 정책 — 노드 0대 스케줄·Budgets (3.1.14, OPS-13)

코드: `.github/workflows/cluster-schedule.yml` · `infra/scripts/cluster-scale.sh` · `infra/terraform/global/{cluster_schedule,budgets}.tf`

| 시각 (KST) | 동작 | 비고 |
| --- | --- | --- |
| 월~금 08:23 | 노드그룹마다 desired 1 (`up`) | 인스턴스 InService까지 대기(최대 10분) → 파드 Ready까지 5~10분 더 |
| 월~금 23:07 | desired 0 (`down`) | 금요일 밤에 꺼져 월요일 아침까지 0대 |
| 데모 주간 11/16~20 | 예약 끄기 건너뜀 (24시간) | 발표 후 수동 `down` |
| 임시로 밤에도 켜 둠 | 레포 Variables `CLUSTER_KEEP_ON=true` | 예약 끄기만 건너뜀. 끝나면 `false` |

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
- [ ] 다음 날 아침 예약 `up`이 실행됐는지 Actions 기록 확인 (schedule은 수~십수 분 늦을 수 있음)
- [ ] `aws budgets describe-budgets --account-id <계정ID>` 에 `cinema-monthly`·`cinema-daily`
- [ ] 두 번째 `terraform plan`(staging) No changes — desired 변경은 Terraform이 무시

| 증상 | 원인 | 해결 |
| --- | --- | --- |
| `Not authorized to perform sts:AssumeRoleWithWebIdentity` | main이 아닌 브랜치에서 실행 / Secret 미등록 | main에서 실행, `AWS_CLUSTER_SCHEDULE_ROLE_ARN` 등록 |
| `AccessDeniedException ... eks:UpdateNodegroupConfig` | 클러스터 이름이 `eks_cluster_name`과 다름 | global 변수 확인 후 apply |
| `up`이 10분 넘게 InService 0 | t3.large 용량 부족·SCP·서브넷 IP 부족 | 노드그룹 Health 이슈(`describe-nodegroup`)·ASG 활동 기록 확인 |
| `desired 1 → 0` 출력 후 `AccessDeniedException … DescribeUpdate` (❌, 노드는 꺼짐) | `eks:DescribeUpdate`는 **클러스터 ARN**이 필수 리소스인데 노드그룹 ARN만 허용했음 (10/24 수정) | global `cluster_schedule.tf` `UpdateRead` 문 확인 후 apply |
| 노드그룹 `DEGRADED` | 인스턴스 생성 실패·서브넷 IP 부족·IAM 등 건강 문제 | 끄기는 그대로 진행됨(경고만). 켜기는 중단 → 8-1-4 |
| 예약 실행이 안 보임 | 워크플로가 main에 없음 / 60일간 레포 활동 없음(GitHub가 schedule 중지) | main 머지 확인, Actions에서 워크플로 Enable |


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
cd infra/terraform/envs/staging-k8s && terraform destroy -var-file=staging-k8s.tfvars   # 먼저 (4-5)
cd ../staging
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
| `init`·`plan`에서 `assume role with MFA enabled, but AssumeRoleTokenProvider session option not set` | `AWS_PROFILE`(MFA 프로필)을 Terraform이 직접 쓰려 함. Terraform은 MFA 입력을 못 받는다 | 2-5 "Terraform 실행 전": `export-credentials`로 환경변수 설정 → `unset AWS_PROFILE` |
| `ExpiredToken`·`The security token included in the request is expired` | 위에서 넘긴 임시 키가 8시간 지나 만료 | `export-credentials` 다시 실행 |
| `init`에서 `bucket - The name of the S3 bucket` 입력 요구 | `-backend-config=backend.hcl` 누락 또는 bootstrap 전 | 1장 → `backend.hcl` 채우기 → `init -backend-config=backend.hcl` |
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
| plan에 `-/+ replace`가 나옴 | 교체가 필요한 값 변경(서브넷 CIDR, SG description 등) | 멈추고 원인 확인. 의존 리소스까지 다시 만들어질 수 있음 |

---

## 14. 실행 기록

| 날짜 | 실행자 | 단계 | 소요 | 문제 | 런북 수정 |
| --- | --- | --- | --- | --- | --- |
| 10/20 | 김종식 | 1 bootstrap · 3 네트워크 | ⬜ | init 때 버킷 입력 요구 | 1장 "다음 단계 준비" 추가 |
| 10/21 | 김종식 | 2 global | ⬜ | OIDC 공급자 409, import 시 already managed | 2-2·12장·13장 추가 |
| ⬜ | 김종식 | 4 EKS (staging · staging-k8s) | ⬜ | | |
| ⬜ | 김종식 | 5 RDS·Valkey (global SNS · staging) | ⬜ | | |
| ⬜ | 김종식 · 한채연 | 8 DB 계정·Secret · 시드 | ⬜ | | |

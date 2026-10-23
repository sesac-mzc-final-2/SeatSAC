# [런북] Terraform 인프라 (bootstrap · global · staging)

| 항목 | 내용 |
| --- | --- |
| 목적 | 빈 상태에서 SeatSAC AWS 인프라를 같은 순서로 다시 만들고, 평소 변경·복구를 같은 절차로 한다 |
| 언제 | 최초 구축, 재생성 리허설(OPS-14, 11/11~12), 계정 초기화 후, 평소 인프라 변경 |
| 담당 / 권한 | 김종식 (백업: 백업 관리자) / 최초 1회는 IAM 사용자, 이후 `cinema-admin` 역할 (MFA) |
| 코드 | `infra/terraform/{bootstrap,global,envs/staging,envs/staging-k8s}` |
| 예상 소요 | bootstrap 2분 · global 3분 · staging 네트워크 5분 · EKS 15~20분 + staging-k8s 3분 (이후 단계는 실측 후 기록) |
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
            │    └─ staging-k8s (namespace·RBAC·LB Controller) ← 클러스터가 있어야 함
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
aws eks update-kubeconfig --region ap-northeast-2 --name cinema-staging-eks --profile cinema-admin
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

팀원(각자, `cinema-dev`):

```bash
aws eks update-kubeconfig --region ap-northeast-2 --name cinema-staging-eks --profile cinema-dev
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

코드: `envs/staging/{rds,valkey,alerts}.tf` + `global/alerts.tf`(SNS `cinema-alerts`)

| 항목 | 값 |
| --- | --- |
| RDS | `cinema-staging-mysql`, MySQL 8.4, `db.t4g.small`, gp3 20GB(자동 확장 50GB), 암호화, Single-AZ(AZ a), 퍼블릭 접근 없음, 백업 7일(03:00 KST), 마이너 자동 업그레이드 끔 |
| RDS 파라미터 | `time_zone=UTC`, `character_set_server=utf8mb4`, `collation_server=utf8mb4_0900_ai_ci`, `require_secure_transport=1`, 느린 쿼리 0.5초 → CloudWatch Logs 7일 |
| 마스터 비밀번호 | RDS 관리(Secrets Manager, 7일 자동 교체). tfstate·tfvars에 없음. 앱·Flyway는 사용 안 함 |
| Valkey | `cinema-staging-valkey`, 8.2, `cache.t4g.micro` 1노드(AZ a), TLS 필수·저장 암호화, AUTH 없음, 스냅샷 없음 |
| Valkey 파라미터 | `maxmemory-policy=noeviction`, `notify-keyspace-events=Ex` |
| 경보 → `cinema-alerts` | Valkey 메모리 80%·Evictions > 0, RDS CPU 80%(15분)·연결 130·남은 저장소 2GB |

### 5-1. global 먼저 (SNS 주제)

```bash
export AWS_PROFILE=cinema-admin
cd infra/terraform/global
terraform plan -var-file=global.tfvars -out=plan.out   # 기대: SNS 주제 1 + 구독(메일 수). destroy 0
terraform apply plan.out
```

- 메일마다 "AWS Notification - Subscription Confirmation"이 온다 → **Confirm subscription**. 안 누르면 경보가 안 온다

### 5-2. staging

```bash
cd ../envs/staging
terraform plan -var-file=staging.tfvars -out=plan.out
# 기대: DB 파라미터 그룹 1 · RDS 1 · 로그 그룹 2 · ElastiCache 서브넷 그룹 1 · 파라미터 그룹 1 · 복제 그룹 1 · 경보 5. destroy 0
terraform apply plan.out      # RDS 10~15분, Valkey 10~15분 (동시에 만들어짐)
terraform output rds_endpoint valkey_primary_endpoint
```

### 5-3. 확인

```bash
aws rds describe-db-instances --db-instance-identifier cinema-staging-mysql \
  --query 'DBInstances[0].[DBInstanceStatus,EngineVersion,MultiAZ,PubliclyAccessible,DBParameterGroups[0].ParameterApplyStatus]'
#   available · 8.4.x · False · False · in-sync
aws elasticache describe-replication-groups --replication-group-id cinema-staging-valkey \
  --query 'ReplicationGroups[0].[Status,TransitEncryptionEnabled,AtRestEncryptionEnabled]'
#   available · True · True
aws cloudwatch describe-alarms --alarm-name-prefix cinema-staging- --query 'MetricAlarms[].[AlarmName,StateValue]' --output table
```

클러스터 안에서 접속 확인 (노드가 켜져 있을 때, 관리자만 — 확인 후 바로 지운다):

```bash
# Valkey: TLS로 PING → PONG, 파라미터 확인
kubectl run valkey-check -n default --rm -it --restart=Never --image=valkey/valkey:8.2 -- \
  sh -c 'valkey-cli -h <valkey_primary_endpoint> --tls PING && valkey-cli -h <valkey_primary_endpoint> --tls CONFIG GET maxmemory-policy'
#   CONFIG는 ElastiCache에서 막혀 있을 수 있다 → 그 경우 콘솔 파라미터 그룹으로 확인

# MySQL: TLS 강제 확인 (비밀번호 없이 접속 시도 → Access denied면 네트워크·TLS는 정상)
kubectl run mysql-check -n default --rm -it --restart=Never --image=mysql:8.4 -- \
  mysql -h <rds_endpoint> -u nobody --ssl-mode=REQUIRED -e 'select 1'
```

- [ ] RDS available·8.4·Single-AZ·파라미터 in-sync, 퍼블릭 접근 False
- [ ] Valkey available·TLS·암호화
- [ ] 경보 5개 `OK`(데이터가 없으면 `INSUFFICIENT_DATA`도 정상), 구독 메일 Confirm
- [ ] 클러스터에서 Valkey PING, MySQL `Access denied`(연결 자체는 됨)
- [ ] 두 번째 plan No changes

### 5-4. 주의

- 마스터 비밀번호는 **3.1.12 DB 계정 생성 Job만** 읽는다(Secrets Manager `rds_master_secret_arn`). RDS는 VPC 밖(노트북)에서 닿지 않으므로 계정·스키마는 클러스터 안에서 만든다
- 인스턴스 클래스·파라미터 변경은 `apply_immediately = true`라 바로 적용되고, 일부(클래스 변경·static 파라미터)는 **재시작**된다. 데모 주간 금지
- destroy 시 최종 스냅샷은 만들지 않는다(`skip_final_snapshot`). 자동 백업은 보존 기간(7일) 동안 남는다 — 복원: `aws rds restore-db-instance-to-point-in-time`
- Valkey 메모리가 부하 테스트에서 70%를 넘으면 `valkey_node_type = "cache.t4g.small"` (재생성 없이 변경, 잠깐 끊김)


## 6. staging 4) ECR·SQS·Lambda (3.1.4·3.1.8·3.1.9) — ⬜

- [ ] ECR 저장소 `cinema/{service}`: 태그 불변(`IMMUTABLE`) + 저장소 정책으로 `cinema-gha-ecr-push`만 push

## 7. staging 5) Cognito·CloudFront·S3 (3.1.11) — ⬜

코드: `envs/staging/cloudfront.tf` + `envs/staging/cloudfront/spa-rewrite.js`(CloudFront Function) · `global/github_oidc.tf`(무효화 권한) · `.github/workflows/frontend-deploy.yml`. Cognito(3.3.x)는 `cognito.tf`에 따로 온다.

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
| `VITE_COGNITO_AUTHORITY` · `VITE_COGNITO_CLIENT_ID` · `VITE_COGNITO_DOMAIN` | `cognito.tf`(3.3.x) 출력 |

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

## 8. staging 6) DB 계정·K8s Secret·ArgoCD·시드 (3.1.12·3.1.5) — ⬜

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
| plan에 `-/+ replace`가 나옴 | 교체가 필요한 값 변경(서브넷 CIDR, SG description 등) | 멈추고 원인 확인. 의존 리소스까지 다시 만들어질 수 있음 |

---

## 14. 실행 기록

| 날짜 | 실행자 | 단계 | 소요 | 문제 | 런북 수정 |
| --- | --- | --- | --- | --- | --- |
| 10/20 | 김종식 | 1 bootstrap · 3 네트워크 | ⬜ | init 때 버킷 입력 요구 | 1장 "다음 단계 준비" 추가 |
| 10/21 | 김종식 | 2 global | ⬜ | OIDC 공급자 409, import 시 already managed | 2-2·12장·13장 추가 |
| ⬜ | 김종식 | 4 EKS (staging · staging-k8s) | ⬜ | | |

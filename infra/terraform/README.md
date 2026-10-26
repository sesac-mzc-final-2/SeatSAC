# infra/terraform

| 디렉터리 | 역할 | state |
| --- | --- | --- |
| `bootstrap/` | tfstate 버킷 1회 생성 | 로컬 (커밋하지 않음, destroy 금지) |
| `global/` | 계정 수준: 팀 역할(`cinema-admin`·`cinema-developer`)·GitHub OIDC·CI 역할·Budgets·SNS (3.1.10·3.1.14) | S3 `global/terraform.tfstate` (destroy 금지) |
| `envs/staging/` | AWS 리소스: VPC → EKS → RDS·Valkey → ECR → (SQS·Lambda → Cognito) → CloudFront·S3 | S3 `staging/terraform.tfstate` |
| `envs/staging-k8s/` | 클러스터 안: namespace·RBAC·LB Controller·DB 계정·Secret·엔드포인트 ConfigMap·ArgoCD·Application (ADR-69) | S3 `staging-k8s/terraform.tfstate` |

파일은 관심사별로 나눈다. 예: `envs/staging` — `network.tf`·`security_groups.tf`(3.1.1) · `eks.tf`(3.1.2) · `rds.tf`·`valkey.tf`·`alerts.tf`(3.1.3) · `ecr.tf`(3.1.4) · `cloudfront.tf`(3.1.11) · `sqs.tf`·`lambda.tf`(3.1.8·9, 예정) · `cognito.tf`(예정) / `envs/staging-k8s` — `lb_controller.tf` · `db_bootstrap.tf`·`rds_ca.tf`(3.1.12) · `infra_endpoints.tf`(3.1.4) · `argocd.tf`·`argocd_apps.tf`(3.1.5)

순서·확인·문제 해결은 런북 `docs/runbooks/terraform-infra.md`가 기준이다(이 파일은 요약).

## 자격 증명 (IAM 역할 + MFA, ADR-47)

IAM Identity Center는 쓰지 않는다(학원 조직의 멤버 계정이라 계정 접근 불가, 런북 0장). IAM 사용자 키(`default` 프로필)로 MFA를 거쳐 역할을 assume한다. 프로필은 `global` 출력 `aws_config_profiles`(런북 2-5).

```bash
# CLI(aws·kubectl·스크립트)
export AWS_PROFILE=cinema-admin          # MFA 코드 입력, 8시간 캐시

# Terraform은 MFA 코드를 묻지 못한다 → CLI가 만든 임시 키를 환경변수로 넘긴다 (터미널 B)
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN   # 이전에 넘긴 키가 남아 있으면 그것이 먼저 쓰인다
eval "$(aws configure export-credentials --profile cinema-admin --format env)"
unset AWS_PROFILE
aws sts get-caller-identity --query Arn --output text
# arn:aws:sts::<계정ID>:assumed-role/cinema-admin/... 이고 계정이 SeatSAC 계정일 때만 terraform 실행
# 만료되면 위 4줄 다시 (Unauthorized·ExpiredToken)
```

- `bootstrap`·처음 `global`은 역할이 아직 없으므로 **IAM 사용자 자격 증명**으로 실행한다(런북 1·2장)
- apply는 `cinema-admin`(김종식 · 백업 관리자 남궁일)만, CI는 plan만

## 처음 한 번

```bash
# IAM 사용자 자격 증명(default 프로필)만 쓰도록 남은 환경변수를 지운다
unset AWS_PROFILE AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
aws sts get-caller-identity --query Arn --output text    # arn:aws:iam::<계정ID>:user/<내 이름> 일 때만 진행

cd infra/terraform/bootstrap
terraform init && terraform apply          # tfstate 버킷

# backend.hcl은 커밋하지 않는다(버킷 이름에 계정 ID) — 세 폴더에 만든다 (런북 1장)
cd ..
B="cinema-tfstate-$(aws sts get-caller-identity --query Account --output text)"
for d in global envs/staging envs/staging-k8s; do echo "bucket = \"$B\"" > "$d/backend.hcl"; done
```

## 매번

| 디렉터리 | `-var-file` |
| --- | --- |
| `global/` | `global.tfvars` |
| `envs/staging/` | `staging.tfvars` |
| `envs/staging-k8s/` | `staging-k8s.tfvars` |

```bash
cd infra/terraform/envs/staging                         # 또는 global · envs/staging-k8s
VARS=staging.tfvars                                     # 위 표대로 (global.tfvars · staging-k8s.tfvars)
git pull && git log -1 --oneline                        # 최신 main에서 (오래된 코드로 plan하면 destroy가 보인다)
terraform init -backend-config=backend.hcl
terraform fmt -recursive && terraform validate
terraform plan -var-file="$VARS" -out=plan.out          # 바뀌는 것 확인
terraform apply plan.out
rm -f plan.out                                          # 저장된 plan에는 비밀 값이 평문으로 있다 — 공유·첨부 금지
terraform output                                        # 런북 14장 실행 기록에
```

- **PR·노션에는 plan 원문이나 `plan.out`을 붙이지 않는다.** 계정 ID를 가려도 plan에는 비밀 값(비밀번호·키)이 평문으로 나올 수 있다. 붙일 것은 요약만: `Plan: N to add, N to change, N to destroy`와 바뀌는 리소스 주소(예: `aws_scheduler_schedule.cluster["ng-a-up"]`), 그리고 바뀐 속성 이름
- `envs/staging` PR은 CI `infra-plan`이 계정 ID를 가린 plan을 코멘트로 단다(비밀 값은 `sensitive`로 가려진다). 로컬 plan을 따로 붙일 필요 없다

## 규칙

- 콘솔에서 만들거나 고치지 않는다. 예외: 긴급 조치 후 **같은 날** Terraform에 반영하고 `plan`이 비어 있는지 확인한다.
- 노드그룹 `desired_size`는 Terraform 밖(EventBridge Scheduler·`cluster-schedule` 워크플로·`cluster-scale.sh`)에서 바뀐다. EKS 모듈이 `desired_size` 변경을 무시하므로 drift가 아니다(3.1.2·3.1.14).
- 비밀 값은 코드·tfvars·state 출력에 넣지 않는다. `random_password` → K8s Secret (ADR-10). state 파일 자체에는 평문이 남으므로 버킷은 비공개·암호화·버전 관리, 개발자 역할은 state 읽기 거부.
- `terraform destroy`는 재생성 리허설(OPS-14, 11/12)과 발표 후에만. `bootstrap`·`global`은 destroy 하지 않는다. staging-k8s는 `-target=helm_release.argocd_apps` 먼저(런북 11장).

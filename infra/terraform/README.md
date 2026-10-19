# infra/terraform

| 디렉터리 | 역할 | state |
| --- | --- | --- |
| `bootstrap/` | tfstate 버킷 1회 생성 | 로컬 (커밋하지 않음) |
| `envs/staging/` | staging 전체 (VPC → EKS → RDS·Valkey → SQS·Lambda → Cognito → CloudFront·S3) | S3 `staging/terraform.tfstate` |

파일은 관심사별로 나눈다: `network.tf`(3.1.1) · `security_groups.tf`(3.1.1) · `eks.tf`(3.1.2) · `rds.tf`·`valkey.tf`(3.1.3) · `sqs.tf`·`lambda.tf`(3.1.8·9) · `cognito.tf`(3.3.x) · `cloudfront.tf`(3.1.11) · `secrets.tf`(3.1.12) …

## 처음 한 번

```bash
aws configure sso            # 프로필 이름: cinema-admin (IAM Identity Center, 3.1.10)
aws sso login --profile cinema-admin
export AWS_PROFILE=cinema-admin   # Terraform은 이 환경변수로 자격 증명을 찾는다 (코드에 프로필 없음)

cd infra/terraform/bootstrap
terraform init && terraform apply          # tfstate 버킷
# 출력 tfstate_bucket 값을 ../envs/staging/backend.hcl 에 적는다
```

## 매번

```bash
cd infra/terraform/envs/staging
terraform init -backend-config=backend.hcl
terraform fmt -recursive && terraform validate
terraform plan -var-file=staging.tfvars -out=plan.out   # 바뀌는 것 확인 (PR에 첨부)
terraform apply plan.out                                # apply는 김종식만 (03b)
terraform output                                        # 런북에 기록
```

## 규칙

- 콘솔에서 만들거나 고치지 않는다. 예외: 긴급 조치 후 **같은 날** Terraform에 반영하고 `plan`이 비어 있는지 확인한다.
- 노드그룹 `desired_size`는 `cluster-schedule` 워크플로가 바꾸므로 EKS 코드에서 `lifecycle { ignore_changes = [scaling_config[0].desired_size] }`.
- 비밀 값은 코드·tfvars·state 출력에 넣지 않는다. `random_password` → K8s Secret (ADR-10). state 파일 자체에는 평문이 남으므로 버킷은 비공개·암호화·버전 관리.
- `terraform destroy`는 재생성 리허설(OPS-14, 11/12)과 발표 후에만. `bootstrap`은 destroy 하지 않는다.

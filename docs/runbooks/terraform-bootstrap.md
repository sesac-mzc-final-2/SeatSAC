# [런북] 계정에서 최초 1회 (state 버킷)

| 항목 | 내용 |
| --- | --- |
| 목적 | 최초 1회 bootstrap으로 state 버킷 생성 |
| 언제 | 최초 시작 시 |
| 담당 / 권한 | 김종식 / cinema-admin 역할 (MFA) |
| 예상 소요 | 약 10초 (실측 기록 아래) |
| 마지막 검증 | 2026-10-20, 소요 5초 |

```bash
aws configure sso            # 프로필 이름: cinema-admin (IAM Identity Center, 3.1.10)
aws sso login --profile cinema-admin
export AWS_PROFILE=cinema-admin   # Terraform은 이 환경변수로 자격 증명을 찾는다 (코드에 프로필 없음)

cd infra/terraform/bootstrap
terraform init && terraform apply          # tfstate 버킷
# 출력 tfstate_bucket 값을 ../envs/staging/backend.hcl 에 적는다
```

## 되돌리기

## 자주 막히는 곳
| 증상 | 원인 | 해결 |

## 실행 기록
| 날짜 | 실행자 | 소요 | 문제 | 런북 수정 |
| --- | --- | --- | --- | --- |
| 2026-10-20 | 김종식 | 5초 | 없음 | 없음
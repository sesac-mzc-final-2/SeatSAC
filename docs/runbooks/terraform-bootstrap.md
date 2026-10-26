# [런북] 계정에서 최초 1회 (state 버킷)

| 항목 | 내용 |
| --- | --- |
| 목적 | 최초 1회 bootstrap으로 state 버킷 생성 |
| 언제 | 최초 시작 시 |
| 담당 / 권한 | 김종식 / IAM 사용자 자격 증명 (역할은 global 이후) |
| 예상 소요 | 약 10초 (실측 기록 아래) |
| 마지막 검증 | 2026-10-20, 소요 5초 |

```bash
# 자격 증명: IAM 사용자 키(default 프로필). cinema-admin 역할은 다음 단계(global)에서 생기므로 여기서는 쓰지 않는다
#   IAM Identity Center는 쓰지 않는다(학원 조직의 멤버 계정, ADR-47·런북 terraform-infra 0장)
unset AWS_PROFILE
aws sts get-caller-identity       # arn:aws:iam::<계정ID>:user/... 이면 진행

cd infra/terraform/bootstrap
terraform init && terraform apply          # tfstate 버킷
# 출력 tfstate_bucket 값으로 global·envs/staging·envs/staging-k8s 에 backend.hcl 을 만든다(커밋 안 함, backend.hcl.example 참고)
```

## 되돌리기

## 자주 막히는 곳
| 증상 | 원인 | 해결 |

## 실행 기록
| 날짜 | 실행자 | 소요 | 문제 | 런북 수정 |
| --- | --- | --- | --- | --- |
| 2026-10-20 | 김종식 | 5초 | 없음 | 없음
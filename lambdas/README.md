# lambdas — ArgoCD 밖 배포 경로 (Terraform apply, `cinema-admin` 로컬)

| 함수 | 트리거 | 하는 일 | VPC |
| --- | --- | --- | --- |
| `notification/` | SQS `cinema-staging-notification-events` (배치 10, 메시지 단위 실패 보고, 3회 실패 → DLQ) | 예매 완료 메시지를 계약 필수 필드 확인 후 JSON 로그 한 줄로 기록 (OPS-07). **배포는 Terraform**(`envs/staging/lambda.tf`가 zip으로 올림, 3.1.8). 테스트: `python3 -m unittest discover -s lambdas/notification -v` | 밖 |
| `cognito-presignup/` | Cognito Pre sign-up | Hosted UI 가입만 `autoConfirmUser`·`autoVerifyEmail` (이메일 실발송 없음). **배포는 Terraform**(`envs/staging/cognito.tf`가 zip으로 올림, 3.1.16) | 밖 |

언어: Python 3.12 (10/22 결정). OPS-08 일별 집계는 범위 제외(10/22). 별칭(alias)은 아직 쓰지 않는다(아래 참고).

> 현재 두 함수 모두 Terraform(`archive_file`)으로 배포한다 — apply는 `cinema-admin` 로컬(CI는 plan만). `docs/repo-workflow.md` 4장의 `lambda-deploy` 워크플로(main 머지 → 패키징·배포·별칭)는 아직 없다. 별칭도 아직 쓰지 않는다.

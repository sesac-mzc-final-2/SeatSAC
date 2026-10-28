# lambdas — ArgoCD 밖 배포 경로 (`lambda-deploy` 워크플로)

| 함수 | 트리거 | 하는 일 | VPC |
| --- | --- | --- | --- |
| `notification/` | SQS `cinema-staging-notification-events` (배치 10, 같은 메시지가 3번 수신됐는데 삭제되지 않으면 → DLQ) | 예매 완료 메시지를 로그로 기록 (OPS-07) | 밖 |
| `cognito-presignup/` | Cognito Pre sign-up | `autoConfirmUser`·`autoVerifyEmail` (이메일 실발송 없음) | 밖 |

언어: Python 3.12 (10/22 결정). OPS-08 일별 집계는 범위 제외(10/22). 버전은 별칭(alias)으로 관리.

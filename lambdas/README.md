# lambdas — ArgoCD 밖 배포 경로 (`lambda-deploy` 워크플로)

| 함수 | 트리거 | 하는 일 | VPC |
| --- | --- | --- | --- |
| `notification/` | SQS `cinema-staging-notification-events` (배치 10, 3회 실패 → DLQ) | 예매 완료 메시지를 로그로 기록 (OPS-07) | 밖 |`v_booking_report`·`v_screening_report` → `stats_db.daily_booking_stat` upsert (OPS-08) | 안 (SG → RDS 3306) |
| `cognito-presignup/` | Cognito Pre sign-up | `autoConfirmUser`·`autoVerifyEmail` (이메일 실발송 없음) | 밖 |

언어: Python 3.12 (제안). 버전은 별칭(alias)으로 관리.

# runbook — 재생성 (OPS-14, 11/12 리허설)

1. `infra/terraform/bootstrap` → tfstate 버킷 (최초 1회)
2. `infra/terraform/envs/staging` → `terraform apply` (VPC → EKS → RDS·Valkey → SQS·Lambda → Cognito → CloudFront·S3)
3. `infra/terraform/envs/staging-k8s` apply 한 번 → DB 계정·Secret + ArgoCD + root 앱(SeatSAC-gitops App of Apps) + 서비스 Application (gateway는 `cognito_enabled = true`일 때만). 그 전에 `build-push` 수동 실행 `services=all`로 이미지·태그 (`docs/runbooks/terraform-infra.md` 6-A-4·6-C-2). 삭제는 11장 순서(Application 먼저)
4. 시드 적재: lotte-seed `seed_layout.sql` → `seed_schedule.sql` (catalog_migrator)
5. 확인: CloudFront 도메인 화면·API, 39개 관 좌석 수, Grafana
소요 시간 기록: __분

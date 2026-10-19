# runbook — 재생성 (OPS-14, 11/12 리허설)

1. `infra/terraform/bootstrap` → tfstate 버킷 (최초 1회)
2. `infra/terraform/envs/staging` → `terraform apply` (VPC → EKS → RDS·Valkey → SQS·Lambda → Cognito → CloudFront·S3)
3. ArgoCD 부트스트랩 (cinema-gitops App of Apps)
4. 시드 적재: lotte-seed `seed_layout.sql` → `seed_schedule.sql` (catalog_migrator)
5. 확인: CloudFront 도메인 화면·API, 39개 관 좌석 수, Grafana
소요 시간 기록: __분

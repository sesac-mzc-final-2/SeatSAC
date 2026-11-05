# runbook — 재생성 (OPS-14, 11/12 리허설)

1. `infra/terraform/bootstrap` → tfstate 버킷 (최초 1회)
2. `infra/terraform/envs/staging` → `terraform apply` (VPC → EKS → RDS·Valkey → SQS·Lambda → Cognito → CloudFront·S3)
3. `infra/terraform/envs/staging-k8s` apply 한 번 → DB 계정·Secret + ArgoCD + root 앱(SeatSAC-gitops App of Apps) + 서비스 Application (gateway는 `cognito_enabled = true`일 때만). 그 전에 `build-push` 수동 실행 `services=all`로 이미지·태그 (`docs/runbooks/terraform-infra.md` 6-A-4·6-C-2). 삭제는 11장 순서(Application 먼저)
4. 시드 적재: lotte-seed `seed_layout.sql` → `seed_schedule.sql` (catalog_migrator)
5. 확인: CloudFront 도메인 화면·API, 39개 관 좌석 수, Grafana
6. ⚠️AI ai(ADR-82~87): `envs/staging`의 ai Valkey·ECR `cinema/ai`·`ng-ai`(desired 0) → `envs/staging-k8s`의 `ai-secrets`·`cinema-infra-endpoints`(AI 키 2개)·Application `cinema-ai` (선행: `argocd_apps.tf`의 `argocd_services`에 `ai`를 추가한 PR이 머지돼 있어야 Application이 생긴다 — 6-G-1 3번) → 확인은 `docs/runbooks/terraform-infra.md` 6-G. 재생성 리허설은 `ng-ai` 0·플래그 off 상태로 기록
소요 시간 기록: __분

# 서비스 실행 계약 (앱 ↔ 인프라)

> 기준 2026-10-21. 개발(서비스 코드)과 인프라/데브옵스(Terraform·gitops·CI)가 **맞닿는 모든 약속**을 한곳에 둔다. 이름 규칙은 `docs/conventions.md`, API는 `contracts/`.
> 이 표대로 만들면 서로 회의 없이 붙는다. 표를 바꿀 때는 PR에 양쪽(서비스 담당 + 김종식) 승인을 받는다.
> 범위: 노드 t3.xlarge 2대(ng-a 1 · ng-c 1, ADR-7·34 — 11/14 t3.large에서 변경) + ⚠️AI ai 전용 `ng-ai` t3.large 1대(평소 0대, ADR-86 — 변수 `ai_node_instance_type`으로 따로), 환경 staging 하나. 값은 10/22 확정(ADR-57·58·59·63), ⚠️AI 11/5 AI 재도입(ADR-82~87)은 각 장의 「ai」 항목과 9장.
> 🆕 11/23 MSA 고도화(노션 ADR-114~125): 서비스별 RDS 인스턴스 6개 · NAT AZ별 · Multi-AZ · 핵심 서비스 replicas 최소 2 · Karpenter · External Secrets · SNS/SQS · `coupon`(Java)·`ops-api`(Python) 추가는 각 장의 🆕 항목과 **10장**. 적용 순서는 노션 「MSA 고도화 (WBS)」 「순서」 열.

---

## 1. 모든 서비스 공통 (Java 5개 + 🆕 coupon — ⚠️AI Python `ai`는 9장, 🆕 Python `ops-api`는 10장)

| 항목 | 앱이 할 일 | 인프라가 할 일 |
| --- | --- | --- |
| 포트 | 8080 하나 (앱·actuator 같은 포트) | Service `{svc}:8080`, 포트 이름 `http` |
| 헬스 | `management.endpoint.health.probes.enabled=true`. readiness 그룹 = `readinessState,db` (gateway는 `readinessState`만). liveness에 외부 의존 넣지 않음 | 프로브(아래 2장) |
| 메트릭 | `micrometer-registry-prometheus`, `/actuator/prometheus` 노출, 공통 태그 `application={svc}`. `http.server.requests` 히스토그램 켜기(`management.metrics.distribution.percentiles-histogram.http.server.requests=true`) | PodMonitor `{svc}`(라벨 `app.kubernetes.io/name`, 포트 `http`, 15초, 파드 라벨 `rollouts-pod-template-hash`를 메트릭에 붙임). ServiceMonitor는 active·preview Service가 같은 파드를 두 번 수집해서 쓰지 않는다 |
| 종료 | `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=20s`. booking은 SIGTERM 때 SSE 연결을 정상 종료 | `preStop: sleep 5`, `terminationGracePeriodSeconds: 35` |
| 로그 | stdout JSON 한 줄(`libs/common-web` logback). 파일 로그 금지 | `kubectl logs`로 traceId 검색. 3.1.24(ADR-113 가번호, 런북 6-J) 적용 뒤에는 Grafana Explore(Loki)에서 `{namespace="cinema-staging"} \| json \| traceId="…"` 한 줄로 모든 서비스 로그를 시각순으로 본다(Alloy 수집, S3 7일 보존, 지워진 파드 로그도 남음, `clientIp`는 가림) |
| 공통 코드 | `libs/common-core`·`common-web` 의존 → 오류 형식·traceId·`@CurrentUser`·내부 호출 클라이언트 자동 적용 | `libs/**` 변경 시 `pr-check`가 5개 전부 테스트, 배포는 안 함 |
| 시간 | JDBC URL에 `connectionTimeZone=UTC`, `Clock.systemUTC()` 주입 | RDS 파라미터 그룹 `time_zone=UTC` |
| 설정 주입 | 아래 3장 이름만 읽는다. 기본값은 `application-local.yml`에만. `${DB_HOST}` 같은 자리 표시는 Spring이 기동 때 치환한다(앱 코드 변경 없음) | `envFrom`: ConfigMap `cinema-infra-endpoints`(Terraform, 엔드포인트) + `{svc}-config`(gitops) + Secret `{svc}-secrets` |
| 이미지 | `bootJar` 결과 `build/libs/app.jar`, Dockerfile은 4장 템플릿 | `build-push`가 x86 빌드 → ECR `cinema/{svc}:{sha7}` → gitops에는 태그만, 레지스트리는 Terraform ArgoCD Application `kustomize.images` |
| 비루트 | 쓰기 경로 `/tmp`만 | `runAsNonRoot: true`, `readOnlyRootFilesystem: true` + `/tmp` emptyDir |

## 2. 서비스별 배포 방식·자원

| 서비스 | 배포 | replicas (HPA) | requests cpu / mem | limits mem | Valkey | RDS 스키마 | Pod Identity |
| --- | --- | --- | --- | --- | --- | --- | --- |
| gateway | **블루/그린** (Rollout) | 1 (HPA 끔, 블루/그린 중 2) | 250m / 512Mi | 768Mi | — | — | 없음 |
| booking | **블루/그린** (Rollout) | 1 (1~2, CPU 70% — 11/12 최대 4 → 2, 아래 블루/그린 파드 수·3장 연결 수) | 250m / 512Mi | 768Mi | ✅ | `booking_db` | `cinema-staging-booking-pod`: `sqs:SendMessage`·`GetQueueUrl` on 알림 큐 |
| catalog | 롤링 (maxUnavailable 0, maxSurge 1) | 1 (1~4) | 200m / 448Mi | 640Mi | ✅ | `catalog_db` | 없음 |
| user | 롤링 | 1 (1~2) | 150m / 384Mi | 576Mi | — | `user_db` | 없음 (`GetUser`는 Access Token으로 호출, IAM 불필요) |
| payment | 롤링 | 1 (고정) | 150m / 384Mi | 576Mi | — | `payment_db` | 없음 |
| ⚠️AI ai | 롤링 (maxUnavailable 0, maxSurge 1) | 1 (고정, HPA 없음) | 250m / 1Gi (임베딩 모델 포함) | 1.5Gi | **ai Valkey**(별도) | ~~없음 (SQLite 메모리 스냅샷)~~ 🆕 `ai_db`(`APP_AI_DATA_SOURCE=db`일 때) | 없음 → 🆕 `cinema-staging-ai-pod`(SQS `ai-catalog-events` 수신) |
| 🆕 coupon | 롤링 | 2 (2~4, CPU 70%) | 200m / 448Mi | 640Mi | ✅ (`coupon:*`) | `coupon_db` | `cinema-staging-coupon-pod`: `coupon-commands` 수신, `booking-replies` 발송 |
| 🆕 ops-api | 롤링 | 1 (고정) | 100m / 256Mi | 384Mi | — | — | `cinema-staging-ops-api-pod`: Athena·Glue 조회, S3 `cinema-staging-reports-*` 읽기·쓰기 |

- 🆕 11/23(ADR-115·117·122): **RDS 스키마 열은 이제 각자의 인스턴스**(`cinema-staging-{svc}-db`). gateway·catalog·booking·user·payment·coupon은 **replicas 최소 2** + `topologySpreadConstraints`(`topology.kubernetes.io/zone`, `whenUnsatisfiable: ScheduleAnyway`) — 두 AZ에 자리가 있으면 파드를 AZ마다 나눠 AZ 하나가 죽어도 남게 한다. **보장은 아니다**: `ScheduleAnyway`는 한 AZ에 자리가 없으면 같은 AZ에 둘 다 올린다(그때 그 AZ가 죽으면 다시 뜰 때까지 0개). 배포 뒤 `kubectl get pods -o wide`로 AZ가 나뉘었는지 확인하고, 강제(`DoNotSchedule`)는 AZ별 노드 용량이 확보되는 Karpenter(6.1.6) 뒤에 정한다 — `DoNotSchedule`은 자리가 없으면 파드가 Pending이라 블루/그린 preview가 멈춘다. booking HPA 2~4(자기 인스턴스라 블루/그린 순간 8 × 15 = 120 < 경보 130), gateway 2(블루/그린 중 4). 위 표의 replicas 열도 최소가 2로 바뀐다 — catalog 2~4, coupon 2~4, user 2(최대 2 그대로라 고정과 같다), payment 2(고정) — data-model 7-1의 연결 수 계산과 같은 값. Pod Identity 추가: booking(명령 발송·`booking-replies` 수신·SNS 발행), payment(`payment-commands` 수신·`booking-replies` 발송), user(`user-booking-events` 수신), catalog(SNS `catalog-events` 발행)
- JVM: `-XX:MaxRAMPercentage=75` (Dockerfile). limits cpu는 두지 않는다(스로틀로 p95 악화)
- ⚠️AI ai는 `ng-ai` 노드에만 뜬다(`nodeSelector cinema/workload=ai` + toleration). 기존 5개 서비스는 taint 때문에 `ng-ai`에 올라가지 않는다. `ng-ai` desired 0이면 ai 파드는 Pending(정상) — 나머지 서비스·데모에 영향 없음
- 두 서비스 동시 블루/그린 금지(노드 2대). 공통 모듈 급한 반영도 한 번에 한 서비스씩: catalog·user·payment → booking → gateway
- 프로브: `startupProbe` readiness 경로 5초 × 30회, `readinessProbe` 5초 / 실패 3, `livenessProbe` 10초 / 실패 3
- 블루/그린: active Service `{svc}`, preview Service `{svc}-preview`, `prePromotionAnalysis` 통과 후 전환, `scaleDownDelaySeconds: 60`. gateway는 ALB 대상 그룹 확인(ADR-35)
- 블루/그린 파드 수: 분석 중 preview는 **1개**(`previewReplicaCount: 1`, active는 HPA 그대로). 전환 직전 preview를 active 수까지 늘리므로 **전환 순간~60초만 2배**(booking HPA 최대 2 → 4개, requests 1 vCPU) → 노드 2대에서 preview가 Pending이면 전환이 멈추고 10분 뒤 abort. booking `maxReplicas`는 **2**(11/12 결정 — 4면 전환 순간 8개 × 풀 15로 RDS 연결이 경보를 넘는다, 3장). 4.2.1(11/11)은 최대 4에서 쟀으므로 이 변경을 머지한 직후(rc2 전) 최대 2로 contention·deploy를 한 번 다시 재서 선점 p95·CPU를 확인한다. 선점 p95가 300ms를 넘으면 원인을 가른다 — DB 연결 대기(`hikaricp_connections_acquire_seconds_max` 큼, CPU 여유)면 파드를 늘려도 안 풀리므로 근본 수정 A(진 요청 사유 판정을 DB 없이), CPU 부족이면 「풀 10 · 최소 유휴 10 · 최대 4」(전환 순간 8 × 10 + 35 = 115)를 수정 A와 함께. **최대 4로만 되돌리지 않는다**(전환 순간 155). 1차 데모(11/16) 전에 끝낸다(노션 ADR-96)
- booking 만료 스캔·아웃박스 릴레이는 **preview 파드에서도 돈다** → 만료·릴레이 로직 변경은 이전 버전과 호환되게(조건부 UPDATE로 한 번만 처리)

## 3. 설정·Secret (서비스가 읽는 이름)

값은 예시. **SeatSAC-gitops가 Public이라(ADR-74) 엔드포인트·계정 ID는 gitops에 쓰지 않는다**: `{svc}-config`(gitops)에는 `${DB_HOST}` 같은 자리만 두고, 실제 값은 Terraform ConfigMap `cinema-infra-endpoints`(`envs/staging-k8s/infra_endpoints.tf`)에서, 비밀번호는 Secret에서 온다.

#### Terraform ConfigMap `cinema-infra-endpoints` (`cinema-staging`, 모든 서비스·Flyway Job이 받는다)

| 키 | 값 | 쓰는 곳 |
| --- | --- | --- |
| `DB_HOST` | RDS 주소 (`aws_db_instance.address`) — 🆕 인스턴스 분리 뒤에는 쓰지 않는다(옮기는 동안만 남김) | `SPRING_DATASOURCE_URL`, Flyway Job `FLYWAY_URL` |
| 🆕 `DB_HOST_{BOOKING,PAYMENT,USER,CATALOG,COUPON,AI}` | 서비스별 RDS 주소(ADR-115) | 각 서비스 `SPRING_DATASOURCE_URL`·Flyway Job. ai는 이름 그대로 `DB_HOST_AI` |
| 🆕 `SNS_{BOOKING,CATALOG}_EVENTS_ARN` · `SQS_{COUPON,PAYMENT}_COMMANDS_URL` · `SQS_BOOKING_REPLIES_URL` · `SQS_{USER_BOOKING,AI_CATALOG}_EVENTS_URL` | 메시징 주소(ADR-117, 계정 ID가 들어가 Terraform에만) | 각 서비스 `APP_MESSAGING_*` |
| 🆕 `REPORTS_BUCKET` · `ATHENA_WORKGROUP` · `PROMETHEUS_URL` · `LOKI_URL` | 리포트 버킷·작업 그룹·클러스터 안 주소 | ops-api(이름 그대로) |
| `VALKEY_HOST` | Valkey primary 엔드포인트 | `SPRING_DATA_REDIS_HOST` |
| `SQS_NOTIFICATION_URL` | `https://sqs.ap-northeast-2.amazonaws.com/{계정ID}/cinema-staging-notification-events` | booking `APP_SQS_NOTIFICATION_QUEUE_URL` |
| `COGNITO_ISSUER_URI` | `https://cognito-idp.ap-northeast-2.amazonaws.com/{userPoolId}` (Cognito 전에는 빈 값) | gateway `APP_COGNITO_ISSUER_URI` |
| `COGNITO_CLIENT_ID` | 앱 클라이언트 ID (Cognito 전에는 빈 값) | gateway `APP_COGNITO_CLIENT_ID` |

| ⚠️AI `AI_VALKEY_HOST` | ai Valkey primary 엔드포인트(`cinema-staging-ai-valkey`) | ai (이름 그대로 읽음) |
| ⚠️AI `PUBLIC_API_BASE_URL` | `https://{배포ID}.cloudfront.net/api` (CloudFront 기본 도메인, Terraform 출력) | ai — 스냅샷 적재·"내 예매" 호출·좌석 잡기(좌석 현황·배치도·확인 뒤 선점 — 플래그 `APP_AI_SEAT_HOLD_ENABLED` 기본 켜짐) |
| `PORTONE_STORE_ID` | 포트원 Store ID (공개 값 — 결제창에 노출, Terraform 변수 `portone_store_id`, ADR-112) | payment `APP_PORTONE_STORE_ID=${PORTONE_STORE_ID}` |
| `PORTONE_CHANNEL_KEY_TOSSPAY` | 포트원 토스페이 채널 키 (공개 값, Terraform 변수 `portone_channel_key_tosspay`) | payment `APP_PORTONE_CHANNEL_KEY_TOSSPAY=${PORTONE_CHANNEL_KEY_TOSSPAY}` — gitops가 Public이라 외부 계정 식별자도 gitops에 쓰지 않는다 |

새 엔드포인트가 생기면 이 표와 `infra_endpoints.tf`에 먼저 추가하고, gitops에는 `${키}`만 쓴다. ⚠️AI Python은 `${키}` 치환을 하지 않으므로 ai는 이 ConfigMap 키를 **이름 그대로** 읽는다.

### 공통 (ConfigMap `{svc}-config`)

| 키 | 값 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `staging` |
| `AWS_REGION` | `ap-northeast-2` |
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://${DB_HOST}:3306/{svc}_db?connectionTimeZone=UTC&sslMode=REQUIRED` → 🆕 `${DB_HOST_{SVC}}`(서비스마다 자기 인스턴스) |
| `SPRING_DATASOURCE_USERNAME` | `{svc}_app` |
| (`FLYWAY_URL`) | ⚠️ ConfigMap이 아니라 **Flyway Job의 env**(gitops `base/{svc}/flyway-job.yaml`): `DB_HOST`(🆕 인스턴스 분리 뒤에는 `DB_HOST_{SVC}` — 앱 `SPRING_DATASOURCE_URL`과 **같은 gitops PR에서** 바꾼다)를 `cinema-infra-endpoints`에서 받아 `jdbc:mysql://$(DB_HOST):3306/{svc}_db?useSsl=true&serverSslCert=/etc/rds-ca/rds-ca.pem&trustServerCertificate=false&disableSslHostnameVerification=false&sslMode=verify-full`로 조립(쿠버네티스 env 치환, 10/25). **Flyway Job 전용**(DB 쓰는 서비스만, 앱은 읽지 않는다). flyway 11.7 이미지에는 MySQL Connector/J가 없어 MariaDB Connector/J **2.7.x**가 `jdbc:mysql://`를 처리한다. 2.x의 TLS 옵션은 `useSsl`이고(`sslMode`는 3.x 옵션이라 2.x는 무시 → TLS가 켜지지 않아 `RSA public key is not available`로 실패), `serverSslCert`로 준 RDS CA로 **서버 인증서·호스트 이름을 검증**한다. `trustServerCertificate=false`·`disableSslHostnameVerification=false`는 2.x 기본값이지만 검증을 끄지 않는다는 뜻을 URL에 명시한다. CA는 ConfigMap `rds-ca-bundle`(Terraform `envs/staging-k8s/rds_ca.tf`)을 `/etc/rds-ca`에 마운트. `sslMode=verify-full`은 3.x로 바뀔 때를 위한 것 |
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | `5` (booking만 `15` — 4.2.1 동시 선점에서 풀 대기 0.55초, 11/11). 연결 수(RDS db.t4g.small `max_connections` 약 170, 경보 `rds_connection_alarm` 130): booking `maxReplicas` 2(2장, 11/12)로 평소 booking 최대 2 × 15 = 30 + catalog 20·user 10·payment 5 = 65, 블루/그린 전환 순간~60초는 preview가 active 수까지 늘어(2장) 최대 4 × 15 = 60 + 35 = 95 < 경보. 같은 때 다른 서비스가 롤링 배포 중이면 `maxSurge: 1` 파드 몫(풀 5)이 더해진다 — catalog·user·payment가 모두 겹쳐도 + 15 = 110 < 경보(두 서비스 동시 블루/그린 금지는 롤링을 막지 않는다). (최대 4였다면 8 × 15 + 35 = 155 → 경보) |
| `SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE` | 없음 = Hikari 기본(최대 풀과 같음) (booking만 `15` = 최대 풀, 11/12) — 몰리는 순간 연결을 새로 열지 않게 늘 열어 둔다. 줄이면(예: 5) 10분 쉰 파드에 연결이 5개만 남고, 100명이 몰릴 때 모자란 연결을 하나씩 새로 열어 풀 5처럼 줄을 선다. 동작은 기본값과 같고, 풀을 다시 조정할 때 찾기 쉽게 값을 적어 둔다. 상한은 maxReplicas가 정한다 |
| `SPRING_FLYWAY_ENABLED` | `false` (마이그레이션은 Flyway 훅 Job) |
| `SPRING_DATA_REDIS_HOST` / `_PORT` / `_SSL_ENABLED` | `${VALKEY_HOST}` / `6379` / `true` (Valkey 쓰는 서비스만) |

### 서비스별

| 서비스 | ConfigMap 키 | Secret `{svc}-secrets` 키 |
| --- | --- | --- |
| gateway | ⚠️AI `APP_AI_ENABLED`(기본 `false` — 꺼져 있으면 `/api/ai/**` 라우트 없음 → 유효한 토큰이면 404, 토큰이 없거나 잘못되면 인증이 먼저라 401, rc2 M6 테스트 후 `true`), `APP_SERVICES_AI_URL=http://ai:8080` — ai 라우트는 **metadata `response-timeout: 28000`**(ms, 전역 `httpclient.response-timeout: 15s`의 예외, SSE 라우트의 `-1`과 같은 방식. 없으면 15초에 `UPSTREAM_TIMEOUT`). 하위 연결 풀은 `httpclient.pool.max-idle-time: 30s`(앱 `application.yml`) — 하위 Tomcat keep-alive 60초보다 짧게 둬 이미 닫힌 연결을 꺼내 쓰는 503(PrematureClose)을 막는다. ai(uvicorn keep-alive 기본 5초)는 이 값으로 덮지 못해 ai 쪽 keep-alive를 늘려 맞춘다. gateway는 `APP_SERVICES_*_READ_TIMEOUT`을 읽지 않으므로 `APP_SERVICES_AI_READ_TIMEOUT`은 만들지 않는다, `APP_COGNITO_ISSUER_URI=${COGNITO_ISSUER_URI}`, `APP_COGNITO_CLIENT_ID=${COGNITO_CLIENT_ID}`, `APP_SERVICES_{CATALOG,BOOKING,USER}_URL`, `APP_SERVICES_PAYMENT_URL=http://payment:8080`(포트원 웹훅 라우트 `POST /api/webhooks/portone`만, ADR-112) (issuer·client-id는 기본값이 없어 빠지면 기동 실패. `app.cognito.issuer-uri`·`app.cognito.client-id`로 바인딩 → `JwtDecoderConfig`가 issuer·`client_id` 검증) | 없음 |
| catalog | `APP_SERVICES_BOOKING_URL`, `APP_SERVICES_BOOKING_READ_TIMEOUT=300ms`, `APP_CACHE_TTL=10m`, `APP_SCHEDULE_ENABLED=true`·`APP_SCHEDULE_DAYS=7`·`APP_SCHEDULE_INTERVAL=1h`(상영 일정 이어 붙이기 — 오늘(KST)부터 7일 안에 회차가 없는 영업일을 앞선 가장 최근 영업일 편성으로 복사, 날짜·시각만 옮김. 회차가 있는 날은 건드리지 않는다. 새 회차 ID = 영업일 epochDay × 100000 + 순번, `INSERT IGNORE` + `GET_LOCK`이라 파드 여럿이어도 한 번만. 넣은 날짜의 `catalog:screenings:{date}:*` 캐시 삭제. 기본값으로 동작해 gitops 변경 불필요) | `SPRING_DATASOURCE_PASSWORD` |
| 🆕 booking (추가) | `APP_CHECKOUT_SAGA_ENABLED`(기본 `false` — 체크아웃 API 404. 메시징·coupon 준비 뒤 `true`. 🆕 API만 막는다 — 체크아웃 스윕(1초)은 플래그와 상관없이 돌아 끈 뒤에도 진행 중 체크아웃을 닫는다. 토스페이로 켜기 전 조건은 런북 6-D-6), ~~`APP_CHECKOUT_STEP_TIMEOUT=30s`~~ 🆕 `APP_CHECKOUT_STEP_TIMEOUT=10s`(쿠폰 예약·결제 준비만), ~~⏳ `APP_CHECKOUT_VERIFY_INTERVAL`(결제 재확인 간격, 5s 제안 — 재확인은 선점 만료 + `APP_PAYMENT_PENDING_GRACE`까지)~~ 🆕 결제 재확인 간격은 설정이 아니라 코드 규칙(11/24): 결제창 미완료(`NOT_YET`)는 5초, 포트원·payment 무응답은 5 → 10 → 20 → 40초 …로 2배씩, 매번 ±20% 흔들림, **선점 만료(`expires_at`)까지만** — saga 예매에는 `APP_PAYMENT_PENDING_GRACE`를 쓰지 않는다(선점 5분에 바로 만료, 환불은 payment), 🆕 `APP_CHECKOUT_PAYMENT_RETRY_ENABLED`(기본 `false` — 결제창 취소·PG 거절이 포트원 FAILED로 확인돼도 지금처럼 보상·좌석 해제. `true`면 `PAYMENT_RETRYABLE`로 좌석을 두고 다시 결제, 예매당 3회. 가짜 포트원 통합 테스트·토스페이 수동 시연 뒤 gitops로 켠다), 🆕 `APP_CHECKOUT_COUPON_ADAPTER=none\|sqs`(기본 `none` — 쿠폰 요청을 할인 없이 바로 예약 성공으로 처리, coupon 없이 saga·측정이 돈다. coupon 배포 뒤 `sqs`), 🆕 `APP_MESSAGING_CONSUMER_ENABLED`(기본 `false` — 결과 소비자(`booking-replies`)를 등록하지 않는다. 큐·Pod Identity(6.1.3) 뒤 `true`), 🆕 `APP_MESSAGING_MAX_CONCURRENT`(결과 소비자 동시 처리 수, 코드 기본 `10` — **비교 측정 때는 payment와 같이 올린다**: 결과 반영(예매 확정·좌석·아웃박스)이 booking에서 일어나 10으로 두면 「확정까지 시간」이 구조가 아니라 booking 쪽 줄 때문에 길어진다. 연결 풀 15라 값은 `hikaricp_connections_pending`을 보며 정한다)·`APP_MESSAGING_MAX_MESSAGES_PER_POLL`(기본 `10`, 동시 처리 수보다 클 수 없다) — payment 행과 같은 이름·뜻, `APP_MESSAGING_COUPON_COMMANDS_URL=${SQS_COUPON_COMMANDS_URL}`, `APP_MESSAGING_PAYMENT_COMMANDS_URL=${SQS_PAYMENT_COMMANDS_URL}`, `APP_MESSAGING_REPLIES_URL=${SQS_BOOKING_REPLIES_URL}`, `APP_MESSAGING_EVENTS_TOPIC_ARN=${SNS_BOOKING_EVENTS_ARN}`(🆕 6.2.4 — `booking.confirmed`·`booking.canceled`를 SNS `booking-events`로. **비어 있으면 그 아웃박스 행을 만들지 않는다**: 토픽 전에 쌓이면 10회 재시도 끝에 FAILED. 그동안 빠진 이벤트는 user 복제본이 사용자별 첫 조회 때 동기로 채운다. 로컬은 SNS가 없어 `app.messaging.events-local-queue-url`(application-local.yml)로 user 구독 큐에 바로 보낸다), (여유 시) `APP_SCREENING_LOOKUP_MODE=sync\|local` | 같음 |
| 🆕 coupon | `SPRING_DATA_REDIS_*`(기존 Valkey), `APP_MESSAGING_COMMANDS_URL=${SQS_COUPON_COMMANDS_URL}`, `APP_MESSAGING_REPLIES_URL=${SQS_BOOKING_REPLIES_URL}`, `APP_COUPON_IDEMPOTENCY_TTL=24h`, `APP_COUPON_RESERVED_ALARM_AFTER=1h`, 🆕 `APP_COUPON_SWEEP_INTERVAL=1m`(종료 스윕 — ISSUED → EXPIRED, 종료 뒤 오래 RESERVED로 남은 쿠폰 ERROR 로그), 🆕 `APP_MESSAGING_CONSUMER_ENABLED`·`APP_OUTBOX_RELAY_ENABLED`(기본 `false` — payment와 같은 이름·뜻. `coupon-commands`·`booking-replies` 큐와 Pod Identity `cinema-staging-coupon-pod`(6.1.3) 뒤 gitops로 `true`), ⏳ 이벤트 값(수량·할인율·시각)은 관리자 화면에서(`POST /ops/coupons/events`) | `SPRING_DATASOURCE_PASSWORD` |
| 🆕 user (추가) | `APP_BOOKING_HISTORY_MODE=sync\|local`(`local`은 사용자별 첫 조회 때 1회 sync로 채운 뒤 복제본만 — API 명세서 v1.5 23-1), `APP_MESSAGING_BOOKING_EVENTS_URL=${SQS_USER_BOOKING_EVENTS_URL}` | 같음 |
| 🆕 catalog (추가) | `APP_MESSAGING_EVENTS_TOPIC_ARN=${SNS_CATALOG_EVENTS_ARN}`, `APP_OUTBOX_RELAY_ENABLED`(기본 `false` — booking과 같은 플래그·같은 이유: SNS 토픽 `catalog-events`와 구독 큐(6.1.3)가 생기기 전에 켜면 재시도 끝에 아웃박스 행이 `FAILED`가 된다. 꺼 두면 `PENDING`으로 쌓였다가 켤 때 흘러간다. 토픽·`ai-catalog-events` 구독 뒤 gitops로 `true`) | 같음 |
| 🆕 payment (추가) | `APP_MESSAGING_COMMANDS_URL=${SQS_PAYMENT_COMMANDS_URL}`, `APP_MESSAGING_REPLIES_URL=${SQS_BOOKING_REPLIES_URL}`, 🆕 `APP_MESSAGING_CONSUMER_ENABLED`(기본 `false` — 명령 소비자(`payment-commands`)를 등록하지 않는다. gitops `base/payment/serviceaccount.yaml`·Pod Identity 뒤 `true`), 🆕 `APP_MESSAGING_MAX_CONCURRENT`(명령 소비자 동시 처리 수, 코드 기본 `10` — 비교 측정은 `200`(동기 Tomcat 스레드와 같게), 그 뒤 `100`·`50` 추가 측정으로 운영값을 정한다. 바꾸면 payment 롤링 재시작), 🆕 `APP_MESSAGING_MAX_MESSAGES_PER_POLL`(한 번에 받는 수, 기본 `10` = SQS 상한. 동시 처리 수보다 클 수 없다 — 동시 처리 수를 10 아래로 내리면 같이 내린다), 🆕 `APP_OUTBOX_RELAY_ENABLED`(기본 `false`)·`APP_OUTBOX_RELAY_INTERVAL=1s`(결과 outbox → `booking-replies`, booking과 같은 이름·뜻 — 큐·Pod Identity 전에 켜면 10회 재시도 끝에 FAILED). 포트원 스윕·웹훅이 saga 결제(`saga_payment`)를 닫으면 결과 이벤트도 낸다(data-model 7-2). 소비는 Spring Cloud AWS `@SqsListener` 수동 확인 — 처리 → (inbox + 상태 전이 + 결과 outbox) 한 트랜잭션 → 삭제, 모의 판정·포트원 조회는 트랜잭션 밖(풀 5개를 잡고 기다리지 않게). 형식이 틀린 명령(JSON 아님·envelope·ID 없음·모르는 `type`)은 로그 + `payment_command_total{type=unknown, result=error}` 뒤 삭제(DLQ 아님), 처리 중 DB 장애만 재배달 → DLQ. 청구(`charge-requested`) 결과는 `approved`·`declined`뿐(청구를 못 했으면 `declined{retryable=false}`) | 같음 |
| 🆕 gateway (추가) | `APP_SERVICES_COUPON_URL=http://coupon:8080`(다른 서비스 주소처럼 `application.yml`에 기본값을 둔다 — gitops `gateway-config`에 없어도 기동한다. 기본값 없이 넣으면 gitops보다 이미지가 먼저 나갈 때 기동 실패 → 블루/그린 abort. coupon이 아직 배포되지 않은 동안 `/api/coupons/**`는 503 `SERVICE_UNAVAILABLE`), `APP_GATEWAY_MAX_BODY_SIZE=64KB`(초과 413 `PAYLOAD_TOO_LARGE`) — `X-User-Grade`는 코드(토큰 `cognito:groups` ∋ `VIP` → `VIP`, 아니면 `NORMAL`, 외부 값은 모든 라우트에서 지움, VIP 지정은 토큰 재발급 뒤 반영). 401·403·413·차단 경로 404는 `errorCategory=SECURITY` 로그(`reason`·`status`·`route`·`traceId`, 토큰·IP 원문 없음 — 🆕 6.3.3 `reason`: `TOKEN_REQUIRED`·`INVALID_TOKEN`(401 응답 `details[].reason`과 같은 이름)·`FORBIDDEN`·`MAX_BODY_SIZE`·`BLOCKED_PATH`, 그리고 경로 조작 400 `INVALID_PATH`·외부 `X-User-*` 헤더 `FORGED_HEADER`(헤더 이름만, 요청은 진행). 413은 `Content-Length`가 넘으면 하위 호출 없이, chunked는 읽다가 넘는 순간. 인증보다 먼저라 토큰이 없어도 큰 본문은 413) | 없음 |
| booking | `APP_SERVICES_{CATALOG,PAYMENT}_URL`, `APP_SERVICES_PAYMENT_PG_READ_TIMEOUT=10s`(payment가 포트원을 부르는 verify·cancel만 응답 대기 — 포트원 최대 약 8초 < 10초 < gateway 15초, ADR-112. 나머지 payment 호출은 기본 3초), `APP_SQS_NOTIFICATION_QUEUE_URL=${SQS_NOTIFICATION_URL}`, `APP_SEAT_HOLD_TTL_SECONDS=300`(30~600, 벗어나면 기동 실패. 멱등 처리 중 키도 같은 값. 바꾸면 이전 TTL 동안 고아 판정이 어긋나므로 데모 준비 때 한 번만), `APP_SEAT_HOLD_STRATEGY=redis`(ADR-45 인터페이스, db-lock은 나중), `APP_OUTBOX_RELAY_INTERVAL=1s`, `APP_PAYMENT_PENDING_GRACE=2m`(ADR-112 — 결제 요청이 붙은 PENDING은 만료+이 유예가 지나면 만료 스캔이 Payment를 조회해 반영. 그 전에는 보류), ⚠️ `APP_OUTBOX_RELAY_ENABLED` — 알림 큐(3.1.9)가 생기기 전에는 **`false`**(기본값). 큐가 없는 동안 켜 두면 확정마다 10회 재시도 끝에 아웃박스 행이 `FAILED`가 되어 사람이 손으로 되살려야 한다. 꺼 두면 `PENDING`으로 쌓여 있다가 켜는 순간 흘러간다(쌓인 양은 지표 `booking_outbox_pending`). 3.1.9 큐와 3.1.8 알림 Lambda 연결 뒤 `true`(런북 6-B-3) — 큐만 있고 받는 쪽이 없으면 메시지가 DLQ로도 못 가고 7일 뒤 사라진다` | `SPRING_DATASOURCE_PASSWORD`, `APP_PAYMENT_MOCK_KEY`(모의 결제 허용 키, Terraform `random_password` — k6 Job·블루/그린 분석 Job이 Secret 참조로 받아 `X-Payment-Mock-Key`로 보낸다. 없으면 기계 경로 자체가 꺼짐) |
| user | `APP_SERVICES_{BOOKING,CATALOG}_URL`, `APP_COGNITO_ENDPOINT`(선택, 기본 `https://cognito-idp.{AWS_REGION}.amazonaws.com`, `AWS_REGION`이 없으면 `ap-northeast-2` — 프로필 첫 생성 때 `GetUser`) | `SPRING_DATASOURCE_PASSWORD` |
| payment | `APP_PAYMENT_MOCK_DELAY_CARD=9999`, `APP_PAYMENT_MOCK_DELAY=5s`, `APP_PORTONE_ENABLED`(코드 기본 `false` — TOSSPAY 준비가 503 `PORTONE_DISABLED`이고 스윕이 돌지 않는다. Secret·설정을 넣은 뒤 gitops PR로 `true`. 켰는데 Secret이 비어 있으면 기동은 되고 포트원이 401 → verify·취소 503 `PORTONE_UNAVAILABLE`), `APP_PORTONE_API_BASE_URL=https://api.portone.io`(로컬은 Stub), `APP_PORTONE_STORE_ID`·`APP_PORTONE_CHANNEL_KEY_TOSSPAY`(포트원 콘솔 공개 값 — 브라우저에 노출되는 값이라 ConfigMap 가능), `APP_PORTONE_PENDING_TIMEOUT=7m`(결제창 미완료 PROCESSING 정리) | `SPRING_DATASOURCE_PASSWORD`, `APP_PORTONE_API_SECRET`(V2 API Secret), `APP_PORTONE_WEBHOOK_SECRET`(웹훅 시크릿, 테스트용) |
| ⚠️AI ai | 9장 표 (`APP_AI_*`) | `ANTHROPIC_API_KEY` (Secret 이름 `ai-secrets`) |

- 서비스 간 타임아웃 기본 `APP_SERVICES_{NAME}_CONNECT_TIMEOUT=1s`, `_READ_TIMEOUT=3s` (`libs/common-web` 기본값, 필요할 때만 덮어씀)
- Flyway Job 전용 Secret `{svc}-migrator-secrets`: `FLYWAY_USER={svc}_migrator`, `FLYWAY_PASSWORD`. 앱 파드에는 마운트하지 않는다
- 🆕 Secret은 External Secrets가 만든다(ADR-123): Secrets Manager `cinema/staging/{svc}` → `ExternalSecret` → 같은 이름의 K8s Secret. gitops에는 Secret **이름**만(ARN·계정 ID 금지). 빈 클러스터 순서: ESO(CRD) → ClusterSecretStore → ExternalSecret(sync wave **-3**) → ConfigMap(-2) → Flyway Job(-1) → 앱(0). 값을 바꾸면 env로 읽는 앱은 재시작해야 새 값을 쓴다
- 🆕 로컬: 메시징은 ElasticMQ(같은 큐 이름, SNS는 로컬에서 큐 직접 발송으로 대체), coupon 8086·ops-api 8087
- 로컬(`local` 프로필): Compose MySQL·Valkey·ElasticMQ, 계정 비밀번호 `local`, SQS 엔드포인트 `http://localhost:9324`. Cognito는 staging 사용자 풀을 같이 쓴다(콜백 `http://localhost:5173/callback`)
- ⚠️AI 로컬 ai: Compose `valkey-ai`(6380) + `uvicorn` 직접 실행, `services/ai/.env`에 **개발 전용** `ANTHROPIC_API_KEY`(커밋 금지), `PUBLIC_API_BASE_URL=http://localhost:8080/api`(로컬 gateway) 또는 staging CloudFront

### frontend (빌드 시 `VITE_`, 공개 값만)

| 키 | 값 |
| --- | --- |
| `VITE_API_BASE_URL` | `/api` |
| `VITE_COGNITO_AUTHORITY` | `https://cognito-idp.ap-northeast-2.amazonaws.com/{userPoolId}` |
| `VITE_COGNITO_CLIENT_ID` | 공개 클라이언트 ID (시크릿 없음) |
| `VITE_COGNITO_DOMAIN` | `https://cinema-staging.auth.ap-northeast-2.amazoncognito.com` |
| `VITE_COGNITO_REDIRECT_URI` | **넘기지 않는다**(3.1.23) — 비우면 화면이 지금 주소(`window.location.origin`) + `/callback`. CloudFront 기본 도메인·사용자 도메인 어느 쪽으로 들어와도 같은 주소로 돌아온다. 허용 목록은 Cognito 앱 클라이언트 콜백(`envs/staging/cognito.tf`, 두 도메인 + 로컬) |

`frontend-deploy` 워크플로가 Terraform 출력에서 값을 넣는다(GitHub Variables). OIDC `scope`는 `openid email profile aws.cognito.signin.user.admin`으로 설정한다. 프로필 행이 없을 때 user 서비스가 같은 Access Token으로 Cognito `GetUser`를 호출하고, 기존 행이 있으면 호출하지 않는다. `NotAuthorizedException`은 401 `UNAUTHORIZED`(`details[].reason` `TOKEN_REJECTED`)로, 그 밖의 Cognito 실패는 503 `IDENTITY_UNAVAILABLE`로 반환한다. 토큰은 `react-oidc-context`가 관리하고 `Authorization: Bearer`로 보낸다. 저장소는 **메모리**(`InMemoryWebStorage`, localStorage 금지). 새로고침하면 `signinRedirect`로 Cognito 세션을 이용해 다시 받는다(비밀번호 재입력 없음, ADR-63).

## 4. 이미지·DB 마이그레이션

```dockerfile
# services/{svc}/Dockerfile — 모든 Java 서비스 공통
FROM eclipse-temurin:21-jre
RUN useradd -r -u 10001 -d /app -s /usr/sbin/nologin app
WORKDIR /app
COPY build/libs/app.jar app.jar
# Flyway Job이 복사해 감 (gateway는 이 줄 없음). Dockerfile은 줄 끝 주석이 없다 — 주석은 따로 한 줄
COPY src/main/resources/db/migration /app/db/migration
USER 10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.security.egd=file:/dev/./urandom"
ENTRYPOINT ["java", "-jar", "app.jar"]
```

| 항목 | 규칙 |
| --- | --- |
| 마이그레이션 실행 | ArgoCD 훅 Job `{svc}-flyway`(**Sync 단계 wave -1**: ConfigMap `{svc}-config` wave -2 → Job → Deployment/Rollout wave 0, 실패하면 sync 중단): initContainer(같은 앱 이미지)가 `/app/db/migration`을 emptyDir로 복사 → `flyway/flyway:11.7.2`(Spring Boot 3.5 Flyway 11.7과 같은 메이저) 컨테이너가 `{svc}_migrator`로 `migrate`. URL은 Job env `FLYWAY_URL`(호스트는 `cinema-infra-endpoints`의 `DB_HOST` → 🆕 `DB_HOST_{SVC}`, 3장. URL 문자열은 TLS 옵션이 달라 앱 URL과 따로 두지만 **호스트는 앱과 같은 인스턴스**다), 계정은 `{svc}-migrator-secrets`, RDS CA는 ConfigMap `rds-ca-bundle` → `/etc/rds-ca` |
| 파일 위치 | `services/{svc}/src/main/resources/db/migration/V{yyyyMMddHHmm}__{설명}.sql` |
| 호환 | 블루/그린·롤링 중 두 버전이 같은 DB를 쓴다 → 확장 → 전환 → 정리. 컬럼 삭제·이름 변경은 다음 배포 |
| 실패 | Job 실패 = sync 실패 → 새 버전이 뜨지 않는다. 고친 마이그레이션은 새 V 번호로(이미 적용된 파일 수정 금지) |
| 시드 | 롯데 배치·편성은 `lotte-seed`의 SQL을 `catalog_migrator`로 1회 적재(김종식·한채연, 런북). Flyway에 넣지 않음. 편성 기간이 끝난 뒤의 날짜는 catalog가 앞선 날 편성을 복사해 이어 붙인다(3장 catalog `APP_SCHEDULE_*`) — 시드를 다시 적재하지 않아도 상영시간표가 비지 않는다 |

## 5. 필수 메트릭 (대시보드·자동 롤백이 이 이름에 의존)

| 메트릭 | 타입 | 라벨 값 | 서비스 | 쓰는 곳 |
| --- | --- | --- | --- | --- |
| `http_server_requests_seconds_*` (Spring 기본) | histogram | `status`, `uri`, `method` | 전체 (⚠️AI ai는 같은 이름으로 직접 낸다) | 5xx 비율·p95, 블루/그린 분석 |
| `booking_seat_hold_total` | counter | `result`=`success`·`conflict`·`active_hold`·`error` | booking | 신호등·동시성 데모. `success`는 PENDING 커밋(발행 전), `conflict`=409 `SEAT_UNAVAILABLE`, `active_hold`=409 `ACTIVE_HOLD_EXISTS`, `error`=5xx(Catalog 503·Valkey·RDS). **4xx 거절(검증·시작된 회차·없는 회차)은 세지 않는다** → 신호등 분모 = 네 라벨 합 |
| `booking_sse_connections` | gauge | — | booking | SSE 재연결 확인 (블루/그린 전환·노드 재기동 때 0으로 떨어졌다 다시 오르는 게 정상) |
| `booking_payment_call_total` | counter | `result`=`approved`·`declined`·`timeout`·`error` | booking | **자동 롤백**(`declined`는 실패율에서 제외). TOSSPAY(ADR-112): 준비(`prepare`)는 세지 않는다(돈이 안 움직임). 완료 확인(`verify`)은 APPROVED → `approved`, FAILED·CANCELED → `declined`, **결제창 미완료(PROCESSING)는 세지 않는다**(사용자가 늦게 닫은 것을 장애로 보지 않게), 응답 없음 → `timeout`, 4xx → `error`(409 `NOT_VERIFIABLE`은 호출자 실수라 세지 않음) |
| `booking_hold_expiry_lag_seconds` | histogram | — | booking | 만료 반영 2초 목표. 노드 0대 구간(ADR-34) 뒤 첫 스윕은 밀린 만큼(수 시간) 큰 값이 한 번 찍히는 게 정상 → 경보는 파드 기동 후 10분 제외(3.1.7) |
| `booking_notification_publish_total` | counter | `result`=`sent`·`retry`·`failed`, 🆕 `destination`=`NOTIFICATION`·`PAYMENT_COMMANDS`·`BOOKING_EVENTS`(11/24 — 아웃박스 행의 보낼 곳. `BOOKING_EVENTS`는 6.2.4 SNS, `COUPON_COMMANDS`는 coupon `sqs` 어댑터 때 추가) | booking (릴레이) | 알림 경보 |
| `booking_outbox_pending` | gauge | — | booking | 릴레이 밀림 |
| `catalog_cache_requests_total` | counter | `result`=`hit`·`miss` | catalog | 캐시 적중률 |
| `catalog_seat_counts_fallback_total` | counter | — | catalog | 300ms 초과 대체 횟수 |
| `payment_request_total` | counter | `result`=`approved`·`declined`·`processing`·`canceled` | payment | 결제 결과 분포(OPS-05) — MOCK은 생성 때 판정, TOSSPAY는 준비 때 `processing`·verify 확정 때 `approved`·`declined`·`canceled`. 자동 롤백은 booking 쪽 `booking_payment_call_total`을 쓴다 |
| `payment_cancel_total` | counter | `reason`=취소 요청 `reason`(계약 `payment.internal.openapi.yaml` enum 값만) | payment | 환불 사유별 건수(OPS-05) |
| `payment_pg_call_total` | counter | `result`=`ok`·`error`·`timeout`, `op`=`get`·`cancel` | payment | 포트원 호출 결과(ADR-112). 웹훅 수신은 `http_server_requests`(`uri=/webhooks/portone`)로 본다 |
| `payment_refund_unresolved_total` | counter | — | payment | 환불 미완료(FAILED + pgTransactionId + canceledAt null) 행이 결제 생성 뒤 15분이 지나도 끝나지 않았으면 **스윕(1분)마다 1씩** 센다 — 건수가 아니라 「남아 있는 행 × 분」이다. 경보는 `increase(...[5m]) > 0`, 0이 아니면 포트원 콘솔과 대조(사람) |
| ⚠️AI `ai_chat_total` | counter | `path`=`cache_exact`·`cache_semantic`·`preset`·`nl2sql`·`my_bookings`·`rejected`·`out_of_scope`·`clarify`·`hold_confirm`·`hold_result`·`hold_guide`·🆕 `rag`(6.4.7, 플래그가 켜져 있을 때만), `result`=`ok`·`error` | ai | 경로별 처리 수 — **토큰 절감 데모의 분모**. `clarify`(되묻기)는 Claude를 부르지만 답이 없는 경로다. `hold_*`(채팅으로 좌석 잡기)는 **기본 켜짐, 끄려면 `APP_AI_SEAT_HOLD_ENABLED=false`(3.4.19)** — 플래그가 켜져 있을 때만 0으로 등록되고, 조회 답이 아니므로 **토큰 절감 계산의 분모·분자에서 `hold_*`를 제외**한다(`path!~"hold_.*"`). `preset`에는 Claude가 프리셋을 고른 답과 **문맥 이어받기 답 중 캐시가 비어 내부 틀을 직접 실행한 것(Claude 0회·토큰 0, 3.4.22)**이 함께 들어간다 — 이어받기 건수는 로그 `context carried`로 구분한다 |
| ⚠️AI `ai_tokens_total` | counter | `kind`=`input`·`output`·`cache_read`·`cache_write`, `path` | ai | 토큰 사용량. 절감 그래프는 `input+output`(우리 캐시 효과), 프롬프트 캐싱은 `cache_read`로 **따로** 표시 |
| ⚠️AI `ai_cache_learned_total` | counter | `result`=`stored`·`known`·`dropped`·`skipped`·`full`·`error` | ai | 스스로 배우기: Claude가 프리셋을 골라 답한 말투를 장부(`ai:learned:v2`)에 적은 결과. `stored`=새로 배움, `known`=이미 같은 답으로 있음, `dropped`=같은 말투에 다른 답이 나와 지움, `skipped`=조건 밖(프리셋 `phrasings`에 있는 말투·SQL이 프리셋 SQL과 다름·날짜 말 없이 오늘로 굳은 SQL·값으로 뽑지 못한 날짜 말이 남은 질문·60자 초과), `full`=200줄 참, `error`=Valkey 실패 |
| ⚠️AI `ai_cache_requests_total` | counter | `layer`=`exact`·`semantic`, `result`=`hit`·`miss` | ai | 캐시 적중률. 질문으로 캐시를 조회한 경우만 센다 — 문맥 이어받기(3.4.22)가 내부 틀 항목을 꺼낸 답은 `ai_chat_total{path="cache_exact"}`에는 잡히지만 여기에는 세지 않는다(그래서 `cache_exact` 합계가 `exact` `hit`보다 클 수 있다) |
| ⚠️AI `ai_llm_call_seconds` | histogram | `result`=`ok`·`timeout`·`overloaded`·`rate_limited`·`error` | ai | Claude API 지연·장애 |
| ⚠️AI `ai_sql_exec_seconds` · `ai_rejected_total` | histogram · counter | — · `reason`(SQL 검증 거부 사유) | ai | SQLite 실행 시간 · 검증기 동작 |
| ⚠️AI `ai_snapshot_age_seconds` · `ai_quota_rejected_total` | gauge · counter | `scope`=`today`·`other` · `window`=`minute`·`day` | ai | 스냅샷 신선도(오늘 1분·나머지 10분) · 한도 초과 |
| ⚠️AI `ai_unmet_demand_total` | counter | `category`=`action_request`·`site_feature`·`recommendation`(조건 없는 영화 추천 요청, 3.4.25)·`unrelated`·`unknown` | ai | 범위 밖(OUT_OF_SCOPE)으로 끝난 요청의 분류별 수 — 다음 개발 후보의 근거. 욕설·인사·잡담(`chitchat`)은 수요가 아니라 세지 않는다. `unknown`은 `out_of_scope` 도구의 `category`가 정해진 값이 아닌 경우(도구 없이 끝난 폴백은 세지 않는다). 로그는 9장 |
| 🆕 `ai_rag_search_total` | counter | `result`=`hit`·`miss` | ai | 이용 안내 검색 결과(6.4.7). `miss`는 하한을 넘는 조각이 없어 `OUT_OF_SCOPE`로 끝난 경우 — 안내 문서를 더 쓸 근거 |
| 🆕 `booking_checkout_total` · `booking_checkout_seconds` · `booking_checkout_compensation_total` | counter · histogram · counter | `result`=`confirmed`·`failed`·`expired`·🆕 `coupon_rejected`·🆕 `payment_retryable` · — · `reason`=`payment_declined`·`step_timeout`·`hold_expired`(🆕 쿠폰 거절은 보상이 아니라 `result`로 센다) | booking | 체크아웃 saga(ADR-116). 자동 롤백은 기존 `booking_payment_call_total` 그대로 |
| 🆕 `booking_checkout_step_seconds` · `booking_checkout_verify_retry_total` | histogram · counter | `step`=`accepted`·`coupon_reserved`·`payment_prepared`·`verified`·`applied` · — | booking | 동기/비동기 비교 측정(API 명세서 v1.5 21-3) — 단계별 시간·결제 재확인 횟수 |
| 🆕 `payment_command_total` · `payment_command_seconds` | counter · histogram | `type`, `result`=`ok`·`duplicate`·`error` · `type` | payment | saga 명령 처리 |
| 🆕 `payment_outbox_publish_total` · `payment_outbox_pending` | counter · gauge | `result`=`sent`·`retry`·`failed` · — | payment (결과 릴레이) | saga 결과 발송·밀림(#182 — booking `booking_notification_publish_total`·`booking_outbox_pending`과 같은 규칙) |
| 🆕 `coupon_command_total` | counter | `type`, `result`=`ok`·`duplicate`·`rejected`·`error` | coupon | saga 명령 처리(거절 포함) |
| 🆕 `{svc}_message_consumed_total` | counter | `type`(메시지 type), `result`=`ok`·`duplicate`·`error` | 메시지 받는 서비스 | inbox 중복·실패. DLQ 깊이는 CloudWatch |
| 🆕 `coupon_claim_total` · `coupon_stock_remaining` | counter · gauge | `result`=`issued`·`sold_out`·`not_eligible`·`duplicate`·`closed` · — | coupon | 선착순 시연 |
| 🆕 `coupon_outbox_publish_total` · `coupon_outbox_pending` | counter · gauge | `result`=`sent`·`retry`·`failed` · — | coupon (결과 릴레이) | saga 결과 발송·밀림(6.3.2 — payment `payment_outbox_*`와 같은 뜻) |
| 🆕 `ops_action_total` | counter | `action`=`stop`·`start`·`report`, `result`=`ok`·`denied`·`error` | ops-api | 관리자 조작(감사 로그와 함께) |
| `cinema:http_requests:rate5m` | recording rule | `application` | 인프라 (gitops `observability/prometheusrule.yaml`, 3.1.7) | 신호등 계산용 서비스별 요청률(actuator 제외). **앱이 만드는 지표가 아니다** |
| `cinema:http_5xx_ratio:rate5m` · `cinema:http_p95_seconds:rate5m` | recording rule | `application` | 인프라 (3.1.7) | 최근 5분 5xx 비율 · p95(SSE `…/stream` 제외). 트래픽이 없으면 값이 없다 |
| `cinema:http_5xx_state` · `cinema:http_latency_state` · `cinema:service_state` | recording rule | `application` | 인프라 (3.1.7) | 신호등 상태 0 정상 · 1 주의 · 2 장애. 5xx 주의 ≥ 1%·장애 ≥ 5%, p95 주의 ≥ 300ms·장애 ≥ 1s, `service_state`는 둘 중 큰 값. 값이 없으면 대시보드가 회색 "트래픽 없음" |

블루/그린 분석(`SeatSAC-gitops/analysis/`):

| 대상 | 조건 (preview 파드, 4분 지연 뒤 1분 × 3회 ≈ 6분, 하나라도 실패하면 abort = 전환 없음) |
| --- | --- |
| 공통 | 분석 트래픽 Job(`rollout-smoke`, k6)이 `{svc}-preview:8080`으로 조회 요청을 preview Ready를 기다린 뒤(최대 150초) 6분(요청 제한 5초) 보내고 **응답 200이 95% 미만이면 Job 실패**(404처럼 5xx가 아닌 고장도 잡음) · `/actuator/**` 제외 요청 **≥ 20건/분** (세 측정 모두, 트래픽 0이면 통과가 아니라 실패. 분석은 preview 확장과 동시에(Ready 전) 시작하므로 preview 기동·Job 지연은 4분 지연으로 흡수) |
| booking | 5xx 비율 ≤ 1% **그리고** `booking_payment_call_total{result=~"timeout\|error"}` / (`approved`+`declined`+`timeout`+`error`) ≤ 5% (결제 호출 0건이면 통과 — 분석 트래픽에 결제 경로를 넣는 것은 BKG-10 이후) |
| gateway | 5xx 비율 ≤ 1% — ⚠️AI 쿼리에 `uri!~"/ai.*"` 추가(Claude API 지연·장애가 gateway 자동 롤백을 일으키지 않게, ADR-86·78 보완). rc1 전에 머지 |

- ⚠️AI ai는 블루/그린 분석 대상이 아니다. AI 지표는 데모 신호등과 **별도 대시보드**(`SeatSAC AI`)에만
- 실제 사용자 트래픽은 active로만 간다 → preview에는 분석 Job 요청만 들어온다. 앱이 할 일은 없다(조회 API가 데이터를 바꾸지 않으면 됨)
- booking 분석 회차(`SMOKE_SCREENING_ID`)는 **시드에 있는 실제 회차**(좌석 있는 상영관)여야 한다 — 없는 회차는 catalog 404에서 끝나 Valkey·RDS를 타지 않고, 200 검사로 **모든 booking 배포가 abort**된다. 고르는 법은 런북 6-D-0. 좌석 현황은 지난 회차도 200이라 고정값으로 둔다(결제 경로를 넣을 때는 선점이 `SCREENING_STARTED`로 막히므로 **미래 회차**가 필요). 시드를 다시 적재해 ID가 바뀌면 gitops도 고친다
- 분석 중(약 6분) preview 파드에서도 만료 스캔·아웃박스 릴레이가 돈다(2장) — 새 버전이 전환 전에 실제 예매 데이터를 건드린다는 뜻. 조건부 UPDATE·점유라 안전
- 5xx 비율 쿼리는 `uri!~"/actuator.*"`(probe 제외)
- preview가 `progressDeadlineSeconds: 600` 안에 Ready가 안 되면 abort(`progressDeadlineAbort`)

preview·active 구분은 파드 라벨 `rollouts-pod-template-hash`로 한다(앱은 아무것도 안 해도 됨).

## 6. 네트워크

| 규칙 | 내용 |
| --- | --- |
| NetworkPolicy | 앱 namespace **ingress 기본 거부**(`default-deny-ingress`) + 허용: ALB(**public 서브넷 CIDR 2개**, VPC CIDR 전체가 아님 — 같은 VPC의 파드가 gateway에 붙지 못하게) → gateway, gateway → catalog·booking·user·**payment**(포트원 웹훅 `/webhooks/portone`만 쓰지만 L4라 포트 단위, ADR-112), catalog → booking, booking → catalog·payment, user → booking·catalog, 분석 트래픽 Job(`app.kubernetes.io/name: rollout-smoke`) → booking·gateway(3.1.6), 부하 테스트 Job(`app.kubernetes.io/name: k6-loadtest`) → gateway(4.2.1, 실행 중에만 — 런북 6-H), `monitoring` → 전체(8080). egress는 막지 않음. L4(포트)까지라 허용된 호출자는 `/internal/**`도 부를 수 있다(외부 `/api/internal/**`는 gateway가 404). kubelet 프로브·port-forward·exec는 대상 아님. 확인 `infra/scripts/np-check.sh`(런북 6-E) |
| 🆕 NetworkPolicy 추가 (11/23) | gateway → **coupon**, ALB(public 서브넷 2개) → **ops-api**, ops-api → ai·coupon(8080, 내부 통계·이벤트 생성), `monitoring` → coupon·ops-api. `monitoring` namespace의 Prometheus·Loki 정책에 ops-api 허용. 메시징은 SQS(바깥)라 서비스 간 허용 규칙이 늘지 않는다. `np-check.sh` `SERVICES`에 `coupon`·`ops-api` |
| 전제 | EKS VPC CNI `enableNetworkPolicy=true` (3.1.2) |
| 보안그룹 | RDS 3306·Valkey 6379는 노드 보안그룹에서만(🆕 RDS 6개 모두, Karpenter 노드도 같은 보안그룹 — EC2NodeClass `securityGroupSelectorTerms`). ALB는 CloudFront 관리형 접두사 목록만(80, `api_origin_https`를 켜면 443도 — 런북 7-8. 🆕 ADR-124에서 `on`) |
| 🆕 NAT | **AZ별 2개**(`one_nat_gateway_per_az`, ADR-122) — AZ a 장애에도 AZ c 파드의 SQS·SNS·Secrets Manager·ECR·Cognito·Claude·EC2 API 호출 유지. 밤 끄기 대상이면 둘 다 |
| 🆕 CloudFront `/ops/*` | ALB(같은 IngressGroup `cinema`) 규칙 → `ops-api`, CachingDisabled·AllViewer. WAF 웹 ACL `cinema-staging-waf`(관리형 규칙 + IP 요청 제한) |
| 외부 호출 | booking → SQS, user → Cognito `GetUser`, gateway → Cognito JWKS, **payment → 포트원 `api.portone.io`**(ADR-112): NAT 경유 HTTPS. 포트원 → CloudFront `/api/webhooks/portone`(인바운드, gateway `permitAll`, 서명 검증은 payment). ⚠️AI ai → Claude API(`api.anthropic.com`), ai → **CloudFront `PUBLIC_API_BASE_URL`**(공개 API — 스냅샷·내 예매, 좌석 잡기(플래그 기본 켜짐) `GET /screenings/{id}/seats` · `GET /auditoriums/{id}/layout` · 확인 뒤 `POST /seats/hold` · 같은 확인의 재전송 때만 `GET /bookings/{id}`): NAT 경유 HTTPS. ai는 클러스터 안에서 gateway를 직접 부르지 않는다(gateway NetworkPolicy는 public 서브넷만 허용, 기존 정책은 수정하지 않음) |
| ⚠️AI ai NetworkPolicy | **`base/ai/networkpolicy.yaml`만 신규**: gateway → ai(8080), `monitoring` → ai(8080). 기존 `base/{svc}/networkpolicy.yaml`은 수정하지 않는다. `np-check.sh` `SERVICES`에 `ai` 추가(gateway → ai ALLOWED, catalog → ai BLOCKED 확인) |
| ⚠️AI ai Valkey 보안그룹 | 6379는 노드 보안그룹에서만(기존 Valkey와 같은 방식), TLS |
| SSE | 아래 4가지가 **한 세트**(하나라도 빠지면 연결이 끊기거나 이벤트가 몰려서 온다): ① Gateway 라우트 `/screenings/*/seats/stream` 응답 타임아웃 없음(한채연) ② ALB 유휴 120초 — gateway Ingress 주석 `load-balancer-attributes: idle_timeout.timeout_seconds=120`, 대상 그룹 `deregistration_delay.timeout_seconds=30`(내려가는 파드의 SSE를 35초 종료 유예 안에 정리, 3.1.5) ③ CloudFront 경로 `/api/screenings/*/seats/stream` 캐시 정책 CachingDisabled·**압축 끔**, 원본 응답 대기 30초 > heartbeat 15초(3.1.11) ④ booking 응답 헤더 `Content-Type: text/event-stream`, `Cache-Control: no-cache`(남궁일) |

## 7. 개발 ↔ 인프라 인계 지점

"넘겨주는 쪽"이 끝나야 "받는 쪽"이 시작할 수 있는 것만 적었다. 필요 시점이 밀리면 스크럼에서 바로 말한다.

| # | 넘겨주는 것 | 주는 쪽 | 받는 쪽 (작업) | 필요 시점 | 확인 방법 |
| --- | --- | --- | --- | --- | --- |
| 1 | 로컬 Compose(MySQL 8.4·Valkey 8.2·ElasticMQ) + 스키마·계정 SQL | 김종식 | 개발 전원 (서비스 골격) | 즉시 | `docker compose -f infra/compose/docker-compose.yml up` 후 각 `{svc}_app` 접속 |
| 2 | `libs/common-core`·`common-web` 최소판 (오류·traceId·`@CurrentUser`·내부 클라이언트) | 한채연·남궁일 | 전 서비스, 김종식(메트릭 공통 태그) | 서비스 골격 커밋 전 | `error.v1.json` 스키마 테스트 통과 |
| 3 | Flyway V1 (catalog·user 완료, booking·payment ERD 6차) | 각 서비스 담당 | 김종식 3.1.12 (DB 계정·GRANT·Secret) | 3.1.12 시작 전 | Compose에서 `migrate` 성공 |
| 4 | RDS·Valkey 엔드포인트, `{svc}-secrets`, `{svc}-migrator-secrets`, `time_zone=UTC` | 김종식 (3.1.3·3.1.12) | 각 서비스 staging 배포, 시드 적재 | 첫 staging 배포 전 | 관리자 `kubectl get secret -n cinema-staging` (developer는 Secret 목록도 Forbidden — 이름은 이 문서 3장으로 확인) |
| 5 | Cognito 사용자 풀·앱 클라이언트·`ADMIN` 그룹·Pre sign-up Lambda | 김종식 (Terraform) | 한채연 GW-02·3.3.3·로그인 화면 | 3.3.1 인증 필터 시작 전 | Hosted UI 로그인 → Access Token 발급 |
| 6 | EKS + Access Entry(4명) + namespace `cinema-staging`·`monitoring` + port-forward RBAC (`envs/staging-k8s`) | 김종식 (3.1.2·3.1.10) | 개발 전원 (`kubectl logs`·port-forward) | 첫 staging 배포 전 | 4명 `kubectl get pods -n cinema-staging` |
| 7 | 서비스 Dockerfile·`bootJar` 이름·actuator 설정 | 각 서비스 담당 (Dockerfile 5개는 3.1.4에서 4장 템플릿으로 맞춤) | 김종식 3.1.4 `build-push` | 서비스 첫 머지 | PR의 `pr-check` `image` 통과(빌드·비루트·Trivy) → 머지 후 `build-push` ECR push |
| 8 | gitops `base/{svc}` (Deployment/Rollout·Service·HPA·NetworkPolicy·PodMonitor·Flyway Job·gateway Ingress) + ArgoCD·서비스 Application(`envs/staging-k8s`) | 김종식 (3.1.5) | 각 서비스 (main 머지 = 배포) | 첫 staging 배포 | `kubectl -n argocd get applications` Synced·Healthy (팀원도 조회 가능) |
| 9 | 필수 메트릭 구현 (5장) | 남궁일·한채연 | 김종식 3.1.6 AnalysisTemplate, 3.1.7 대시보드 | 3.1.6 블루/그린 시연 전 | `/actuator/prometheus`에 이름·라벨 존재 |
| 10 | SQS 큐·DLQ, booking Pod Identity, 알림 Lambda | 김종식 (3.1.8·3.1.9) | 남궁일 3.2.6 (확정·아웃박스 릴레이) | 3.2.6 통합 테스트 전 | 확정 → Lambda 로그에 `eventId` |
| 11 | `seat-event`·`booking.confirmed` 발행 테스트 (examples로 검증) | 남궁일 | 김종식 (Lambda 소비), 화면 | 3.2.3·3.2.6 | 발행 테스트가 `contracts/events` 스키마 통과 |
| 12 | CloudFront·S3·`frontend-deploy` + `VITE_` 값 | 김종식 (3.1.11) | 한채연·남궁일 화면 | 화면 첫 배포 | CloudFront 도메인 새로고침·`/api` 동작 |
| 13 | SSE 경로 세트(6장 SSE ①~④): ALB 유휴 120초(3.1.5 Ingress)·CloudFront 캐시·압축 끔(3.1.11)·Gateway 라우트 타임아웃 없음·`/api` StripPrefix | 김종식(ALB·CloudFront) · 한채연(라우트) | 남궁일 BKG-05 | 3.2.3 SSE 확인 (3.1.5·3.1.11 작업 때 같이 확인) | CloudFront 경유 `curl -N`으로 15초 ping이 몰리지 않고 바로바로 옴 |
| 14 | NetworkPolicy 허용 목록 (6장) | 김종식 (3.1.13) | 전 서비스 내부 호출 | 내부 API 통합 후 | `infra/scripts/np-check.sh` 전부 ✅ — Cognito 뒤 `REQUIRE_GATEWAY=1`로 확인 — gateway → payment는 포트원 웹훅 때문에 **ALLOWED**(ADR-112, `np-check.sh` `allowed()`의 gateway 목록에 payment 추가 필요 — 김종식). **그리고** ALB → gateway 실경로(스크립트 밖): ALB 대상 그룹 gateway healthy + CloudFront `/api/movies` 200 (런북 6-E) |
| 15 | 부하 테스트 시나리오 대상 API·데이터 | 남궁일 | 김종식 OPS-10 (k6 Job) | 4.2.1 전 | k6 스크립트 dry-run |
| ⚠️AI 16 | ECR `cinema/ai`·ai Valkey·`ai-secrets`·`cinema-infra-endpoints` 키 2개·`ng-ai`·Application `cinema-ai` | 김종식 (3.1.18·3.1.19) | 김서현 staging 배포 | rc1(11/6) | `kubectl -n argocd get application cinema-ai`, `ng-ai` 1대일 때 ai readiness 200 |
| ⚠️AI 17 | CI(`changed-services.sh` ai 분기, `pr-check` `python`·ai 기동 확인, `build-push` ai) — **#79에 먼저 포함**(3.4.8 PR이 검사를 받도록) · gitops `base/ai`·`overlays/staging/ai` | 김종식 (3.1.20) | 김서현 (main 머지 = 배포) | CI는 3.4.8 리베이스 전, gitops는 3.1.18 apply 후 | PR `image`(ai)·`python` 통과 |
| ⚠️AI 18 | gateway `/api/ai/**` 라우트(플래그 off, 라우트 metadata `response-timeout: 28000`) + 분석 쿼리 `/ai` 제외 | 한채연(3.3.8)·김종식(3.1.21) | 김서현·한채연 화면 | **rc1(11/6)** | 플래그 off에서 유효한 토큰으로 `/api/ai/chat` 404(토큰이 없거나 잘못되면 401 — 인증이 라우팅보다 먼저), gateway 블루/그린 통과 |
| ⚠️AI 19 | `contracts/openapi/ai.openapi.yaml` + 예시 | 김서현·한채연 (2.2.8) | 한채연 채팅 화면(3.3.7) | 11/6 | contract-check 통과 |

| 🆕 20 | RDS 6개·`DB_HOST_{SVC}`·Secrets Manager·ESO | 김종식 (6.1.1·6.1.4) | 전원 (자기 인스턴스로 전환) | 11/23 오후 | 각 서비스 readiness 200 + Flyway Job 성공 |
| 🆕 21 | SNS·SQS·DLQ·Pod Identity·`cinema-infra-endpoints` 메시징 키 | 김종식 (6.1.3) | 남궁일 6.2.x · 🆕 김종식 6.3.2(coupon, 11/24) · 한채연 6.3.4 · 김서현 6.4.2 | 11/23 저녁 | 로컬 ElasticMQ 테스트 통과 → staging에서 명령 1건 왕복 |
| 🆕 22 | 계약 PR 6.0.1 (API 명세서 v1.5 21~26장 → `contracts/`) | 전원 | 구현 전원 | 11/23 오전 | `contract-check` 통과 |
| 🆕 23 | coupon ECR·gitops `base/coupon`·Application·NetworkPolicy | 김종식 (6.1.5) | ~~한채연~~ 🆕 김종식 coupon 배포(11/24) | 11/24 오전 | `kubectl -n argocd get application cinema-coupon` Healthy |
| 🆕 24 | ops-api(`/ops`)·Role·`ignoreDifferences` | 김종식 (6.1.7) | 남궁일 6.2.6 · 한채연 6.3.5·6.3.7 | 11/24 | `GET /ops/services` 200(ADMIN 토큰), 403(일반 토큰) |
| 🆕 25 | ai 통계 내부 API | 김서현 (6.4.4) | 김종식 ops-api 7번 | 11/24 | ops-api → ai 200 |
| 🆕 26 | catalog `screening.changed`·`movie.changed` 발행 | 한채연 (6.3.4) | 김서현 6.4.2 | 11/24 | `ai-catalog-events` 큐에 메시지 |
| 🆕 27 | ai 응답 `path: RAG`·`sources` 계약(6.0.1에 포함) | 김서현 (6.4.7) | 한채연 챗 화면(라벨·출처 표시 — 없어도 깨지지 않는다: 모르는 `path`는 글자 그대로 보인다, `chat-labels.ts`) | 11/24 | `contract-check` 통과 |
| 🆕 28 | 관리자 AI 범위 밖 통계 응답(JSON) | 김서현 6.4.4 · 김종식 6.1.7 | 김서현 6.4.8(워크플로우 입력) | 11/25 | 응답을 파일로 저장해 워크플로우 1회 실행(없으면 같은 모양의 샘플 파일) |

## 8. 인프라 쪽 남은 정리

- [x] Compose·Terraform에서 `stats_db`·`stats_lambda`·`lambda-stats` 보안그룹·`lambdas/daily-stats` 삭제 (10/22, SG는 다음 apply에서 삭제)
- [x] EKS 1.36 · 노드그룹 ng-a·ng-c · 애드온(VPC CNI 네트워크 정책 켬·metrics-server) · Access Entry · LB Controller (3.1.2, 코드 10/22 · apply 후 런북 4장 확인)
- [x] 가동 정책: 평일 08:23 켜기·23:07 끄기(KST), 데모 주간 24시간, Budgets 월·일 경보 (3.1.14, 코드 10/22 · global apply 후 런북 8-1장 확인). 10/27: 실행을 EventBridge Scheduler로(GitHub schedule 지연·누락), 워크플로는 백업
- [x] ElastiCache Valkey 8.2 `cache.t4g.micro`(noeviction·Ex·TLS) + 메모리 80%·Evictions 경보, RDS MySQL 8.4 Single-AZ `db.t4g.small`(UTC·utf8mb4·TLS 강제) (3.1.3, 코드 10/22 · apply 후 런북 5장 확인)
- [ ] SQS 알림 큐·DLQ(`maxReceiveCount 3`)·booking Pod Identity(`cinema-staging-booking-pod`, ServiceAccount `booking`)·DLQ 경보·오래된 메시지(2일) 경보 (3.1.9, `envs/staging/sqs.tf`·gitops `base/booking/serviceaccount.yaml` — 코드 10/28 · **런북 6-B-2 확인 후 체크**). 릴레이 켜기(`APP_OUTBOX_RELAY_ENABLED=true`)는 알림 Lambda(3.1.8) 연결 뒤 6-B-3
- [ ] DB 스키마 4·계정 8(`REQUIRE SSL`)·`{svc}-secrets`·`{svc}-migrator-secrets`·RDS CA ConfigMap `rds-ca-bundle` (3.1.12, `envs/staging-k8s/db_bootstrap.tf`·`rds_ca.tf` — 클러스터 안 Job이 마스터 Secret을 Pod Identity로 읽어 인증서 검증 TLS로 생성. 코드 10/24 · **런북 8-B PASS·두 번째 plan·8-C 시드 적재 확인 후 체크**). Flyway 훅 Job 틀은 gitops `base/{svc}/flyway-job.yaml`(3.1.5에서 연결)
- [ ] ArgoCD(Helm)·root 앱·서비스 Application(Helm `argocd-apps`)·팀원 읽기 전용 화면, 플랫폼 차트 버전 고정(argo-rollouts·kube-prometheus-stack), gateway Ingress(ALB 유휴 120초·대상 정리 30초) (3.1.5, `envs/staging-k8s/argocd.tf`·`argocd_apps.tf`, gitops `apps/staging`·`base/gateway/ingress.yaml` — 코드 10/25 · **런북 6-C-2 확인 후 체크**). gateway·ALB·CloudFront `/api`는 Cognito 후
- [ ] 앱 JDBC도 서버 인증서 검증으로: `SPRING_DATASOURCE_URL`의 `sslMode=REQUIRED`(암호화만) → `VERIFY_IDENTITY` + `rds-ca-bundle` 트러스트스토어 — 김종식. 3.1.5에서 분리: Connector/J는 PEM CA를 바로 못 읽어 initContainer로 트러스트스토어(PKCS12)를 만들어야 하고, 실제 RDS로 기동 검증이 필요해 앱이 처음 뜬 뒤 별도 PR
- [ ] NetworkPolicy 허용 목록·기본 거부 (3.1.13, gitops `base/*/networkpolicy.yaml` — gateway ALB 대역을 public 서브넷으로 좁힘, 10/27 · **런북 6-E `np-check.sh` 확인 후 체크**, gateway는 Cognito 뒤 `REQUIRE_GATEWAY=1`로 다시)
- [ ] 신호등 대시보드 **SeatSAC 신호등**(Grafana)·기록 규칙 `cinema:*`·CloudWatch 데이터소스(Valkey 메모리·Evictions, SQS DLQ)·Grafana Pod Identity(`cinema-staging-grafana-pod`, ServiceAccount `kube-prometheus-stack-grafana`) (3.1.7, `envs/staging/grafana.tf`·gitops `observability/`·`platform/kube-prometheus-stack/values.yaml` — 코드 11/2 · **런북 6-F 확인 후 체크**). 앱 지표(`booking_seat_hold_total` 등 5장)가 구현되기 전에는 해당 패널이 회색·"수집 없음"으로 나오는 게 정상
- [ ] Compose `elasticmq-native:latest` → 버전 고정 — 김종식
- [ ] `infra/terraform/envs/staging`에 Cognito·SQS·Pod Identity 모듈 추가 시 이 문서 3·6장 이름 그대로 — 김종식
- [ ] Cognito 사용자 풀 `cinema-staging-users`·공개 앱 클라이언트·Hosted UI `cinema-staging`·`ADMIN` 그룹·Pre sign-up Lambda `cinema-staging-cognito-presignup` + `cognito_enabled = true`(gateway Application) (3.1.16, `envs/staging/cognito.tf` — 코드 10/28 · **런북 7-6-4 확인 후 체크**)
- [ ] 알림 Lambda `cinema-staging-notification`(python3.12·arm64·30초·VPC 밖)·실행 역할·로그 그룹(7일)·SQS 이벤트 소스 매핑(배치 10·`ReportBatchItemFailures`·동시 2) (3.1.8, `envs/staging/lambda.tf`·`lambdas/notification` — 코드 11/2 · 런북 6-B-5 확인 후 체크). 배포는 Terraform `archive_file`(Pre sign-up Lambda와 같은 방식, `lambda-deploy` 워크플로 없음)`
- [ ] ⚠️AI ai 인프라: ECR `cinema/ai` · ElastiCache `cinema-staging-ai-valkey`(Valkey 8.2 `cache.t4g.micro`, `noeviction`·`reserved-memory-percent 50`·TLS, 메모리 80% 경보) · `ai-secrets`(`ANTHROPIC_API_KEY` sensitive) · `cinema-infra-endpoints` 키 `AI_VALKEY_HOST`·`PUBLIC_API_BASE_URL` · `argocd_apps.tf`에 `ai` (3.1.18, rc1)
- [ ] ⚠️AI 노드그룹 `ng-ai`(t3.large 1대, min 0·max 1·desired 0, 라벨·taint `cinema/workload=ai`) · EventBridge Scheduler 켜기 대상에서 제외 · `cluster-scale.sh` 대상 지정(`up`은 `ng-ai` 제외, `up ng-ai`·`down ng-ai`, `down`은 전부) · Budgets 금액 재확정 (3.1.19, rc1)
- [ ] ⚠️AI gitops `base/ai`(Deployment·Service·NetworkPolicy·PodMonitor, HPA·Flyway Job 없음) · `overlays/staging/ai` · `np-check.sh` · 저장소 변수 `AI_BUILD_PUSH_ENABLED=true` (3.1.20). CI(`changed-services.sh`·`pr-check` `python`·`build-push` ai)는 #79에서 먼저
- [ ] ⚠️AI gateway 분석 쿼리 `uri!~"/ai.*"` (3.1.21, **rc1 전**)
- [ ] ⚠️AI AI 대시보드 `SeatSAC AI` + 런북 6-G(켜기·끄기·질의 금지 구간) (3.1.22, gitops `observability/dashboards/seatsac-ai.json` — 코드 11/17 · **런북 6-G-8 확인 후 체크**)
- [x] CloudFront → ALB 구간 HTTPS(3.1.23 후속, 선택 — **발표 뒤**): `api_origin_https` `prepare` apply → gitops `base/gateway/ingress.yaml` 443 리스너(`spec.tls.hosts: origin.<도메인>`) → `on` apply → 80 정리 (`envs/staging/origin_https.tf` — 코드 11/20 · **11/23 `on`까지 적용**(#166·gitops #41·#167), 사용자 도메인·기본 도메인 두 주소 `/api/movies` 200 확인. 80 정리(런북 7-8 4번)는 하지 않았다 — 선택)
- [ ] 로그 저장소 Loki·수집 Alloy·Grafana 데이터소스 `loki` · S3 `cinema-staging-loki-{계정ID}`(보존 7일·수명 주기 14일)·Pod Identity `cinema-staging-loki-pod`(monitoring/loki)·ConfigMap `monitoring/loki-storage` (3.1.24, ADR-113 가번호, `envs/staging/loki.tf`·`envs/staging-k8s/loki.tf`·gitops `platform/{loki,alloy}` — 코드 11/18 · **런북 6-J 적용·확인 후 체크**)

- [ ] 🆕 RDS 6개(`cinema-staging-{svc}-db`, ai 외 Multi-AZ)·인스턴스별 db-bootstrap·`DB_HOST_{SVC}` (6.1.1, ADR-115)
- [ ] 🆕 NAT AZ별·Valkey 2개 복제본·자동 장애 조치 (6.1.2, ADR-122)
- [ ] 🆕 SNS 2·SQS(구독 2~3·명령 2·결과 1)·DLQ·경보·Pod Identity (6.1.3, ADR-117)
- [ ] 🆕 External Secrets·Secrets Manager `cinema/staging/*`·ExternalSecret (6.1.4, ADR-123)
- [ ] 🆕 Karpenter(NodePool·EC2NodeClass·중단 알림 SQS)·`cluster-scale.sh` 연동 (6.1.6, ADR-122)
- [ ] 🆕 ops-api·`/ops` CloudFront 동작·ALB 규칙·Role·서비스 Application `ignoreDifferences` (6.1.7, ADR-119)
- [ ] 🆕 WAF·리포트 버킷·Glue·Athena·내보내기 CronJob (6.1.8·6.1.9, ADR-121·124)
- [ ] 🆕 CloudWatch EKS audit 로그 끄기(하루 약 1.5GB) · Budgets 일 경보 $50

## 9. ⚠️AI ai 서비스 (Python, ADR-82~87)

| 항목 | 앱(김서현)이 할 일 | 인프라(김종식)가 할 일 |
| --- | --- | --- |
| 런타임 | Python 3.12, FastAPI + uvicorn(워커 1), `pyproject.toml` + `uv.lock`. 테스트 pytest, 린트 ruff | `pr-check` `python` job(ruff·pytest, `services/ai/**` 바뀐 PR만), `changed-services.sh`의 `ALL_SERVICES`에 `ai` + **ai만 Gradle 대신 `docker build services/ai`** 분기 |
| 포트·헬스·메트릭 | 8080. `/actuator/health/liveness`(프로세스만), `/actuator/health/readiness`(스냅샷 첫 적재 완료 + ai Valkey PING), `/actuator/prometheus` | Java 서비스와 같은 프로브·PodMonitor 템플릿(`app.kubernetes.io/name: ai`, 포트 `http`, 15초). startupProbe 5초 × 30회(임베딩 모델·첫 스냅샷 적재) |
| 이미지 | `python:3.12-slim`, UID 10001, 임베딩 모델(`paraphrase-multilingual-MiniLM-L12-v2`, ONNX, fastembed)을 **빌드 때** `/app/models`에 넣는다(실행 중 다운로드 없음). 시드·스냅샷 파일은 이미지에 넣지 않는다 | `build-push`가 ECR `cinema/ai:{sha7}` → gitops `overlays/staging/ai/image-tag`. Trivy 동일 기준 |
| 비루트·파일 | 쓰기 경로 `/tmp`만. SQLite는 **메모리 DB(`:memory:`)** — 디스크에 남기지 않음 | `runAsNonRoot`, `readOnlyRootFilesystem` + `/tmp` emptyDir |
| 로그 | stdout JSON 한 줄(`timestamp`·`level`·`service=ai`·`traceId`·`userId`·`path`·`latencyMs`·`tokens`·`questionLength`·`questionHash`·`sqlTemplate`·`rowCount`, 좌석 잡기일 때 `holdReason` — 결과 코드만). **질문 원문·SQL 리터럴 값·결과 행·Claude 응답 본문·사용자 토큰·좌석 이름·예매 ID는 남기지 않는다** — 질문에 이메일·전화번호 같은 개인정보가 들어올 수 있다. `questionHash` = HMAC-SHA256(질문, 파드 기동 때 만든 임의 키) 앞 16자(같은 파드 안 반복 질문 추적용, 되돌릴 수 없음), `sqlTemplate` = 리터럴을 `?`로 바꾼 SQL(sqlglot). 디버깅에 원문이 필요하면 로컬에서 재현한다. **예외 — `unmet demand` 로그**(`category`·`reason`·`reasonDropped`·`questionHash`·`questionLength`): 범위 밖 요청의 `reason`은 Claude가 쓴 120자 이하 요약으로 예외 허용. 질문을 베낀 요약은 버리고(`reason: null`, `reasonDropped: true`) 숫자 연속·이메일은 가린다(`[숫자]`·`[이메일]`). `chitchat`은 기록하지 않는다 | `kubectl logs` |
| 오류·traceId | `error.v1.json` 형식 그대로, `X-Trace-Id` 받으면 그대로·없으면 32자리 생성. 코드는 `services/ai` 안(conventions 6장 ⚠️AI 행). `libs/` 변경 없음 | — |
| 배포 | 별도 ArgoCD Application `cinema-ai` | `argocd_apps.tf` `argocd_services`에 `ai` 추가. **다른 Application 파일은 수정하지 않는다** |

### 9-1. 설정 (ConfigMap `ai-config` · `cinema-infra-endpoints` · Secret `ai-secrets`)

| 키 | 값 | 비고 |
| --- | --- | --- |
| `APP_ENV` | `staging` | |
| `AI_VALKEY_HOST` · `PUBLIC_API_BASE_URL` | `cinema-infra-endpoints`(Terraform) | 이름 그대로 읽음 |
| `APP_AI_VALKEY_PORT` / `APP_AI_VALKEY_TLS` | `6379` / `true` | |
| `APP_AI_MODEL` | `claude-sonnet-5-5` | ADR-83. 교체는 이 값만 |
| `APP_AI_REQUEST_DEADLINE_SECONDS` | `25` | 요청 하나의 **전체 기한**(Claude 호출·SQL 실행·재요청 전부). 넘으면 남은 호출을 하지 않고 503 `AI_LLM_UNAVAILABLE`(`reason` `TIMEOUT`). 아래 시간 예산 |
| `APP_AI_LLM_TIMEOUT_SECONDS` | `15` | Claude 호출 1회 상한. 실제로는 `min(15, 전체 기한까지 남은 시간)`. 내부 REST 3초의 예외 |
| `APP_AI_LLM_MAX_CALLS_PER_QUERY` | `3` | 도구 선택 1 + 검증 실패 재요청 1 + SQL 오류 재요청 1. 3회를 다 써도 **전체 기한 25초 안**에서만(15초 × 3을 보장하지 않는다) |
| `APP_AI_PROMPT_CACHE_ENABLED` | `true` | 스키마 설명(system)에 프롬프트 캐싱. 지표 `kind=cache_read`로 따로 |
| `APP_AI_SQL_TIMEOUT_SECONDS` / `APP_AI_MAX_ROWS` | `5` / `500` | progress handler로 5초 중단(전체 기한이 더 짧으면 그쪽), `fetchmany(500)`. 읽기 전용 방식은 9-2 |
| `APP_AI_SNAPSHOT_TODAY_INTERVAL` / `APP_AI_SNAPSHOT_OTHER_INTERVAL` / `APP_AI_SNAPSHOT_DAYS` | `60s` / `600s` / `7` | 오늘 회차 1분, 나머지 10분, 오늘~+6일 |
| `APP_AI_SNAPSHOT_REFRESH_ON_CHAT` / `APP_AI_SNAPSHOT_REFRESH_TIMEOUT_SECONDS` | `true` / `2.0` | 질문 시점 잔여석 갱신: SQL이 `remaining_seats`를 읽으면 실행 직전에 **그 SQL의 날짜 × 극장** 회차만 새로 받는다(SQL 조건 분석기 `sql/conditions.py`, 극장 조건이 없으면 극장 전부). 날짜는 2개까지 — 날짜 조건을 읽지 못하면(범위 비교·OR) 오늘만, 3개 이상이면 앞의 2개만 새로 받고 나머지는 주기 갱신 값으로 답하며 답 문장에 알린다(catalog 호출 최대 8번). 같은 범위 동시 질문은 한 번만, 확인 불가(TOTAL)가 섞이면 한 번 더. 2초 안에 못 받거나 실패하면 기존 스냅샷으로 답하고 답 문장에 알린다. 잔여석은 물었을 때만 SELECT 하고, 날짜가 없으면 되묻는다(`CLARIFY`) |
| `APP_AI_CACHE_TTL` / `APP_AI_SESSION_TTL` | `1h` / `30m` | |
| `APP_AI_SEAT_HOLD_ENABLED` | `true` | **기본 켜짐, 끄려면 `APP_AI_SEAT_HOLD_ENABLED=false`(3.4.19)**. 채팅으로 좌석 잡기 — 확인 버튼을 누른 뒤에만 선점한다. ConfigMap에 넣지 않으면 앱 기본값(켜짐)으로 동작하고, 끌 때만 gitops `overlays/staging/ai`의 `ai-config`에 `APP_AI_SEAT_HOLD_ENABLED=false` 한 줄을 넣는다. **staging은 gitops에서 `false`로 두고(끄는 gitops PR을 이 코드보다 먼저 머지), 로컬 확인 뒤 켠다.** 꺼져 있으면 Claude 도구 목록·system·처리 경로가 좌석 잡기가 없던 때와 같다. 켜져 있어도 ai Valkey나 `PUBLIC_API_BASE_URL`이 없으면 꺼진 것으로 동작한다 |
| `APP_AI_SEAT_HOLD_CONFIRM_TTL` | `120s` | 좌석 잡기(위 행)가 켜져 있을 때만 쓴다. 확인 버튼을 기다리는 시간. 지나면 대기 행동(`ai:pending:*`)이 사라져 확인을 눌러도 잡지 않는다 |
| `APP_AI_CACHE_WARM_PRESETS` | `true` | 프리셋 `phrasings`(버튼 질문 말투)를 기동 때·캐시 TTL 절반마다 정확 일치·의미 캐시에 넣는다(`cache/warm.py`). 버튼 질문은 첫 사용자부터 토큰 0. 끄면 첫 질문은 Claude. 문맥 이어받기 내부 틀 7개(`context_templates.yaml`, 3.4.22)도 같이 넣되 키가 따로(`ctx {presetId}`)이고 의미 캐시에는 넣지 않는다 — 직접 친 질문은 적중하지 않고 이어받기 요청만 쓴다(끄면 이어받은 답의 `path`가 `PRESET`). 기본값으로 동작해 gitops 변경 불필요 |
| `APP_AI_CACHE_LEARN_ENABLED` | `true` | 스스로 배우기(`cache/learned.py`): Claude가 프리셋을 골라 답한 새 말투를 `ai:learned:v2`에 적고 예열 루프가 캐시에 계속 다시 넣는다 — 캐시 TTL(1시간) 뒤에도 그 말투는 토큰 0. 끄면 새로 적지도 다시 넣지도 않는다(이미 적힌 줄은 키 TTL 7일 뒤 사라짐, 바로 지우려면 `DEL ai:learned:v2`). 한계: Claude가 조건을 빼먹고 프리셋을 고른 답은 구분하지 못해 최대 7일 캐시로 나간다. 기본값으로 동작해 gitops 변경 불필요 |
| `APP_AI_SEMANTIC_CACHE_ENABLED` / `APP_AI_SEMANTIC_THRESHOLD` | `true` / `0.92` | rc2까지 의미 캐시가 안 되면 `false`로 정확 일치 캐시만(B1) |
| 🆕 `APP_AI_RAG_ENABLED` | `false` | 이용 안내 검색(RAG, 6.4.7). 꺼져 있으면 Claude 도구 목록·system·처리 경로가 지금과 같다. 켜기는 평가(6.4.6) 뒤 gitops `overlays/staging/ai` PR 1회(ai만 롤링 재시작) |
| 🆕 `APP_AI_RAG_TOP_K` / `APP_AI_RAG_MIN_SCORE` | `3` / ⏳ | Claude에 주는 조각 수 / 코사인 유사도 하한. 하한은 6.4.6 평가 문항으로 재서 정한다 — 의미 캐시의 0.92를 그대로 쓰지 않는다(질문끼리가 아니라 질문과 문서를 비교해 값이 낮게 나온다) |
| `APP_AI_LIMIT_PER_MINUTE` / `APP_AI_LIMIT_PER_DAY` | `600` / `300` | 사용자당. 동시 1건. 11/18 발표 전 테스트·시연 연습용으로 올린 값(gitops #29, 11/20 키 폐기까지). 앱 기본값과 계약(`ai.openapi.yaml`)의 설계 값은 `10` / `100` 그대로 |
| 🆕 `APP_AI_DATA_SOURCE` | `snapshot` | `snapshot`(지금처럼 메모리 SQLite) · `db`(자기 인스턴스 `ai_db`, ADR-120). `db`로 바꾸기는 아래 세 줄이 준비된 뒤 gitops `overlays/staging/ai` PR 1회(ai만 롤링 재시작). 되돌리기는 `snapshot` |
| 🆕 `DB_HOST_AI` · `SQS_AI_CATALOG_EVENTS_URL` | `cinema-infra-endpoints`(Terraform, 6.1.1·6.1.3) | 이름 그대로 읽음. `APP_AI_DATA_SOURCE=db`일 때만 쓴다 |
| 🆕 ai DB 비밀번호 | Secret `ai-secrets`에 키 2개 — `APP_AI_DB_PASSWORD`(`ai_app`)·`APP_AI_QUERY_DB_PASSWORD`(`ai_query`) | 키 이름 확정(11/24, 김서현 — #170 코멘트). 계정은 6.1.1 db-bootstrap이 만들고, Secret 전달은 6.1.4(External Secrets — `ai-secrets`는 지금 Terraform이 값을 다시 쓰지 않아 여기에 키를 더하지 않는다). 그 전에는 `db`로 켤 수 없다 |
| 🆕 ⏳ `ai_db` 마이그레이션 | 제안: 다른 서비스와 같은 Flyway 훅 Job `ai-flyway`(`ai-migrator-secrets`, SQL은 이미지 `/app/db/migration`) | **방식 미확정**(6.4.1). `ai-migrator-secrets`는 6.1.1에서 만든다. 지금 gitops `base/ai`에는 Flyway Job이 없다 |
| `ANTHROPIC_API_KEY` (Secret `ai-secrets`) | Terraform sensitive 변수 → K8s Secret | staging 키. 로컬은 개발 전용 키를 `.env`에 |

**시간 예산** (`/api/ai/chat` 한 번, 바깥이 항상 안쪽보다 길다)

| 층 | 상한 | 넘으면 |
| --- | --- | --- |
| CloudFront 원본 응답 대기 | 30초 (기존 설정, 3.1.11) | 504 |
| gateway `/api/ai/**` 라우트 `response-timeout` | **28초** (`28000`) | 503 `SERVICE_UNAVAILABLE`(`details[{field: service, value: ai, reason: UPSTREAM_TIMEOUT}]`) — gateway는 하위 서비스의 연결 실패·시간 초과를 모두 503으로 준다(3.3.1, `GatewayErrorHandler`). ai 파드가 없을 때는 같은 503에 `reason` `UPSTREAM_UNAVAILABLE` |
| ai 요청 전체 기한 `APP_AI_REQUEST_DEADLINE_SECONDS` | **25초** | 503 `AI_LLM_UNAVAILABLE` `TIMEOUT` — 정상 경로에서는 여기서 먼저 끝나 화면이 ai의 오류 형식을 받는다 |
| └ Claude 호출 1회 | min(15초, 남은 시간) × 최대 3회 | 다음 호출 없이 위 503 |
| └ 잔여석 갱신 1회 | min(2초, 남은 시간) | 기존 스냅샷으로 답하고 답 문장에 알린다(남은 시간이 없으면 갱신하지 않는다) |
| └ SQLite 실행 1회 | min(5초, 남은 시간) | `REJECTED` 또는 SQL 오류 재요청 |
| └ 좌석 잡기 공개 API 1회(플래그 `APP_AI_SEAT_HOLD_ENABLED`, 기본 켜짐) | min(전체 3초·연결 1초, 남은 시간) — 확인 요청 때 좌석 현황 + 배치도 2회, 확인 뒤 선점 1회(+ 시간 초과·연결 오류 시 같은 키로 재시도 1회) | 좌석 화면으로 안내(`HOLD_GUIDE`·`HOLD_RESULT` `reason` `UPSTREAM_UNAVAILABLE`·`HOLD_NOT_SENT`) |
| `ai:inflight:{userId}` 잠금 | 40초 | 25초 기한보다 길어 처리 중 풀리지 않는다 |

### 9-2. 실행 규칙

- **ai는 RDS·기존 Valkey에 연결하지 않는다.** 데이터는 공개 API(`PUBLIC_API_BASE_URL`)로 채운 메모리 SQLite, 캐시·세션·한도는 ai Valkey. 🆕 11/23(노션 ADR-120): `APP_AI_DATA_SOURCE=db`면 **자기 인스턴스 `ai_db`만** 쓴다(`DB_HOST_AI`, 앱 `ai_app`·NL2SQL 실행 `ai_query` 읽기 전용 계정 — 실행 상한 5초·500행 그대로, MySQL `MAX_EXECUTION_TIME`). 복제본은 SQS `ai-catalog-events` 소비 + 최초 백필(공개 API) + 10분 전체 대조. 범위 밖 질문은 `ai_db.unmet_demand`. 다른 서비스 DB·기존 Valkey는 계속 금지. 기본값은 `snapshot`(복제본 준비 뒤 gitops로 `db`). `ng-ai`가 0대인 동안(평소) `ai-catalog-events`는 받는 파드가 없어 큐에 쌓이고 보존 기간이 지나면 사라진다 — DLQ로 가지 않아 경보도 울리지 않는다. 그래서 복제본은 이벤트만 믿지 않고 **기동 때 백필 + 10분 전체 대조**로 맞춘다(ai를 켠 직후 답은 첫 백필이 끝난 뒤부터, 그 전은 503 `AI_SNAPSHOT_NOT_READY`)
- 🆕 **이용 안내 검색(RAG)**(`APP_AI_RAG_ENABLED` 기본 `false`, 6.4.7 — ADR-126): 켜져 있을 때만 Claude 도구에 `search_guide`가 추가된다. Claude가 이 도구를 고르면 ai가 질문을 임베딩해 메모리의 안내 조각(data-model 6-5)에서 상위 `APP_AI_RAG_TOP_K`개를 찾고, 하한을 넘는 조각만 Claude에 돌려줘 **그 조각만 근거로** 답하게 한다 → `path: RAG`, `sources`에 쓴 조각의 `id`·`title`. Claude 호출은 2회(도구 선택 1 + 답 1, `APP_AI_LLM_MAX_CALLS_PER_QUERY` 3 안). 하한을 넘는 조각이 없으면 지금처럼 `OUT_OF_SCOPE`(`site_feature`)로 끝나고 수요 기록에 남는다. RAG 답은 캐시에 저장하지 않는다(처음에는 단순하게 — 저장은 평가 뒤 검토). 외부 호출·DB·Valkey 키는 늘지 않는다. 로그에는 `path=RAG`와 `sourceIds`(문서 id)만 더 남긴다
- 🆕 **수요 → 백로그 워크플로우**(6.4.8, LangGraph — ADR-126): `services/ai/backlog/`에 둔다. `eval/`처럼 **이미지에 들어가지 않고**(Dockerfile은 `src`만 복사) 클러스터에 배포하지 않는다 — 사람이 로컬에서 실행한다. 의존성 `langgraph`는 `pyproject.toml`의 별도 그룹 `workflow`에만 넣어 서비스 이미지(`--no-dev`)에 들어가지 않는다. 입력은 관리자 통계 API(6.4.4) 응답을 저장한 JSON 파일 — 분류·요약·횟수뿐이고 질문 원문·userId가 없다(6-4 `unmet_demand` 규칙). 단계: ① 읽기 ② 비슷한 요약 묶기 ③ 횟수가 기준 미만이면 건너뜀 ④ 상위 묶음의 기능 제안서 초안(Claude, 개발 전용 키) ⑤ **사람 승인 대기**(체크포인트 파일 — 껐다 켜도 이어진다) ⑥ 승인된 것만 마크다운 파일로 저장. 노션 WBS 등록은 사람이 한다(이슈는 노션 WBS만 — ADR-48·64). 코드·PR을 만드는 단계는 없다. `ai_db`·Valkey·다른 서비스에 쓰지 않는다
- 회차 ID 넘기기(계약 `screeningIds`): 검증을 통과한 SQL을 실행할 때 행 하나가 회차 하나이면 회차 ID 열을 덧붙였다 떼어 `screeningIds`로 보낸다(프리셋·NL2SQL·캐시 적중 모두 같은 실행 경로, data-model 6-1). Claude에게 주는 프롬프트·스키마 설명과 로그 `sqlTemplate`은 덧붙이기 전 SQL 그대로다. 덧붙인 SQL만 실패하면 남은 시간 안에서 원래 SQL로 한 번 다시 실행해 답은 그대로 내고(`screeningIds: null`) WARNING 한 줄(오류 종류만)을 남긴다. ai는 화면 주소를 만들지 않는다
- SQLite 읽기 전용: `:memory:`를 URI `mode=ro`로 열면 비어 있는 읽기 전용 DB가 되어 적재할 수 없다. 그래서 **적재 → 잠금 → 교체** 순서로 한다. ① 갱신마다 **새** `:memory:` 연결을 쓰기 가능으로 열어 적재 ② 끝나면 그 연결에 `PRAGMA query_only=ON` + `set_authorizer`(SELECT·READ·FUNCTION만 허용, 나머지 DENY — `PRAGMA query_only=OFF`·`ATTACH`·쓰기 SQL도 여기서 막힌다. `query_only`만으로는 SQL로 다시 끌 수 있다) ③ 질의용 참조를 새 연결로 바꾸고 이전 연결은 진행 중 질의가 끝나면 닫는다(이중 버퍼). 질의 쪽은 쓰기 가능한 연결을 받을 수 없다. 적재 실패면 이전 스냅샷을 그대로 쓰고 `ai_snapshot_age_seconds`가 늘어난다
- 스냅샷 적재 호출(오늘 1분마다 4회, 10분마다 약 50회, **잔여석을 읽는 질문마다 날짜 수 × 극장 수**(「오늘 건대입구」 1회, 극장을 안 정하면 4회, 확인 불가가 섞이면 한 번 더))은 CloudFront → gateway → catalog로 들어간다. 질문 시점 호출은 질문 하나에 최대 8회(날짜 2 × 극장 4 — 9-1)이고, 사용자당 동시 1건(`ai:inflight`)으로 묶이며(staging 한도는 11/18부터 분당 600·하루 300 — 9-1), 같은 범위 동시 질문은 한 번을 같이 쓴다. **부하 테스트(4.2.1)·블루/그린 전환 테스트(4.2.2) 중에는 `ng-ai` desired 0 · 플래그 off, 좌석 데모 중에는 `ng-ai` desired 0 · AI 질의 금지(플래그는 rc2에서 켜 둔 채)** → 테스트 환경 = 데모 환경 (런북 6-G)
- **채팅으로 좌석 잡기**(`APP_AI_SEAT_HOLD_ENABLED` **기본 켜짐**, 3.4.19 — ADR 번호는 노션 등록 후 확정. 끄려면 gitops `overlays/staging/ai`의 `ai-config`에 `APP_AI_SEAT_HOLD_ENABLED=false` 한 줄 — ai만 롤링 재시작, gateway 블루/그린 없음): ai가 쓰기 API(`POST /seats/hold`)를 부르는 **유일한** 경우. 사용자의 확인 버튼 없이는 부르지 않는다 — 확인 요청 때 잡을 내용과 일회용 확인 ID를 `ai:pending:{userId}:{sessionId}`(2분)에 두고, 같은 사용자·같은 세션에서 그 확인 ID가 실린 확인이 오면 한 번 꺼내 쓰고 지운다(`GETDEL`). 선점에 성공하면 그 결과를 `ai:held:{userId}:{sessionId}`(선점이 남은 동안, 최대 5분)에 두어, 응답만 실패해 같은 확인 ID가 다시 오면 선점을 다시 하지 않는다 — `GET /bookings/{bookingId}`(사용자 토큰 그대로)로 그 예매를 한 번 다시 보고, 아직 PENDING이면 같은 결과를, 그 사이 취소·만료·결제 실패면 `HOLD_ENDED`, 결제 완료면 `ALREADY_PAID`, 확인하지 못하면 `UPSTREAM_UNAVAILABLE` 안내를 준다(결제 화면 링크 없음). 사용자 토큰은 저장하지 않고 확인 요청에 실려 온 `Authorization`을 그대로 넘긴다. 호출은 공개 주소(`PUBLIC_API_BASE_URL`)로만. 좌석 현황·예매 결과는 Claude에 보내지 않는다. 결제는 채팅에서 하지 않는다. 좌석 데모·부하 테스트·전환 테스트 중에는 위 스냅샷 규칙대로 `ng-ai` 0대(부하·전환 테스트는 gateway 플래그 off까지)라 ai 자체가 답하지 않으므로 기본 켜짐이어도 좌석 잡기가 나가지 않는다 — 이 플래그를 따로 끌 필요는 없다. `ng-ai`를 켠 채 좌석 측정을 해야 하면 그때만 `false`로 끈다
- `ng-ai`는 EventBridge Scheduler 평일 켜기 대상에서 **뺀다**(평소 0대). 켜기·끄기는 `cluster-scale.sh up ng-ai`·`down ng-ai`(cinema-admin). `cluster-scale.sh up`·`up 2`와 `cluster-schedule`의 `up`은 `ng-ai`를 켜지 않고 `down`은 `ng-ai`까지 끈다(켜 둔 채 잊어도 백업 끄기·수동 `down`이 끈다)
- 플래그 켜기는 rc2(11/13) M6 테스트가 끝난 뒤 gitops PR 1회(gateway 블루/그린 1회). 이후 플래그·ai 코드 변경도 시연·측정 중이 아닐 때 머지할 수 있다(11/14 동결 삭제). 단 발표 당일 머지 마감(플래그는 gateway 블루/그린이라 **11/20 05:00**, ai 코드는 **11/20 07:00**, `docs/repo-workflow.md` 2장 동결) 뒤에는 시연·측정 중이 아니어도 머지하지 않는다

<노션 반영 필요> WBS 3.1.5·3.1.6·3.1.12 메모에 "서비스 실행 계약(docs/service-runtime-contract.md) 기준", 기능명세서 OPS-11 "SSO" → "IAM 역할 + MFA(ADR-47)", OPS-15 허용 목록: ⚠️AI 11/5부터 ai 행 다시 추가(gateway → ai, monitoring → ai)

## 10. 🆕 ops-api 서비스 (Python, 노션 ADR-119 · 11/23)

| 항목 | 앱(김종식)이 할 일 | 인프라(김종식)가 할 일 |
| --- | --- | --- |
| 런타임 | Python 3.12 FastAPI(⏳ 6.0.1 확정), ai와 같은 골격(오류 `error.v1.json`·traceId·actuator 경로·JSON 로그) | `pr-check` `python`·`changed-services.sh`에 `ops-api`(Gradle 없이 `docker build`), ECR `cinema/ops-api` |
| 경로·인증 | `/ops/**`만. Cognito JWKS로 Access Token 검증(issuer·`client_id`) + `cognito:groups` ∋ `ADMIN`(아니면 403). 모든 쓰기 조작은 `ops audit` 로그 한 줄(userId·action·target·result) | CloudFront 동작 `/ops/*` → ALB 규칙(같은 IngressGroup) → `ops-api:8080`. gateway를 거치지 않는다 |
| 상태 조회 | 정해 둔 PromQL만(`cinema:service_state`, HPA 현재·최대, 노드 CPU) + Kubernetes API 읽기. 사용자 입력을 쿼리에 넣지 않는다 | Prometheus·Loki NetworkPolicy에 ops-api 허용 |
| 정지/가동 | `deployments/scale`·`rollouts/scale` patch(0 ↔ 정지 전 값). 대상 gateway·catalog·booking·user·payment·coupon·ai(ops-api 제외). 블루/그린 진행 중이면 409 `ROLLOUT_IN_PROGRESS` | Role(`cinema-staging`): deployments·rollouts·hpa·pods get/list, `*/scale` patch만. 서비스 Application `ignoreDifferences: /spec/replicas` + `RespectIgnoreDifferences=true` |
| 리포트 | Athena 쿼리 → CSV → presigned URL 5분. 열에 IP·토큰·질문 원문 없음 | Pod Identity `cinema-staging-ops-api-pod`(Athena·Glue·S3 reports), 내보내기 CronJob(1시간, Loki → S3 `errors/dt=`) |
| 자원 | requests 100m / 256Mi, limits mem 384Mi, replicas 1 | 롤링, HPA 없음, Flyway Job 없음 |

- 정지 버튼이 바꾼 replicas는 Git과 다르다(의도된 GitOps 예외, ADR-119). 시연 뒤 「가동」으로 되돌리고, ArgoCD Diff에 replicas가 안 보이는 것이 정상이다
- replicas 0이면 HPA가 멈추고(scale-to-zero 아님), 1 이상으로 되돌리면 다시 동작한다

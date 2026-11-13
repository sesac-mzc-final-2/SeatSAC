# 서비스 실행 계약 (앱 ↔ 인프라)

> 기준 2026-10-21. 개발(서비스 코드)과 인프라/데브옵스(Terraform·gitops·CI)가 **맞닿는 모든 약속**을 한곳에 둔다. 이름 규칙은 `docs/conventions.md`, API는 `contracts/`.
> 이 표대로 만들면 서로 회의 없이 붙는다. 표를 바꿀 때는 PR에 양쪽(서비스 담당 + 김종식) 승인을 받는다.
> 범위: 노드 t3.large 2대(ng-a 1 · ng-c 1, ADR-7·34) + ⚠️AI ai 전용 `ng-ai` 1대(평소 0대, ADR-86), 환경 staging 하나. 값은 10/22 확정(ADR-57·58·59·63), ⚠️AI 11/5 AI 재도입(ADR-82~87)은 각 장의 「ai」 항목과 9장.

---

## 1. 모든 서비스 공통 (Java 5개 — ⚠️AI Python `ai`는 9장)

| 항목 | 앱이 할 일 | 인프라가 할 일 |
| --- | --- | --- |
| 포트 | 8080 하나 (앱·actuator 같은 포트) | Service `{svc}:8080`, 포트 이름 `http` |
| 헬스 | `management.endpoint.health.probes.enabled=true`. readiness 그룹 = `readinessState,db` (gateway는 `readinessState`만). liveness에 외부 의존 넣지 않음 | 프로브(아래 2장) |
| 메트릭 | `micrometer-registry-prometheus`, `/actuator/prometheus` 노출, 공통 태그 `application={svc}`. `http.server.requests` 히스토그램 켜기(`management.metrics.distribution.percentiles-histogram.http.server.requests=true`) | PodMonitor `{svc}`(라벨 `app.kubernetes.io/name`, 포트 `http`, 15초, 파드 라벨 `rollouts-pod-template-hash`를 메트릭에 붙임). ServiceMonitor는 active·preview Service가 같은 파드를 두 번 수집해서 쓰지 않는다 |
| 종료 | `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=20s`. booking은 SIGTERM 때 SSE 연결을 정상 종료 | `preStop: sleep 5`, `terminationGracePeriodSeconds: 35` |
| 로그 | stdout JSON 한 줄(`libs/common-web` logback). 파일 로그 금지 | `kubectl logs`로 traceId 검색 (Loki 없음, OPS-09 P3) |
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
| ⚠️AI ai | 롤링 (maxUnavailable 0, maxSurge 1) | 1 (고정, HPA 없음) | 250m / 1Gi (임베딩 모델 포함) | 1.5Gi | **ai Valkey**(별도) | **없음** (SQLite 메모리 스냅샷) | 없음 (Claude API는 키, AWS 권한 불필요) |

- JVM: `-XX:MaxRAMPercentage=75` (Dockerfile). limits cpu는 두지 않는다(스로틀로 p95 악화)
- ⚠️AI ai는 `ng-ai` 노드에만 뜬다(`nodeSelector cinema/workload=ai` + toleration). 기존 5개 서비스는 taint 때문에 `ng-ai`에 올라가지 않는다. `ng-ai` desired 0이면 ai 파드는 Pending(정상) — 나머지 서비스·데모에 영향 없음
- 두 서비스 동시 블루/그린 금지(노드 2대). 공통 모듈 급한 반영도 한 번에 한 서비스씩: catalog·user·payment → booking → gateway
- 프로브: `startupProbe` readiness 경로 5초 × 30회, `readinessProbe` 5초 / 실패 3, `livenessProbe` 10초 / 실패 3
- 블루/그린: active Service `{svc}`, preview Service `{svc}-preview`, `prePromotionAnalysis` 통과 후 전환, `scaleDownDelaySeconds: 60`. gateway는 ALB 대상 그룹 확인(ADR-35)
- 블루/그린 파드 수: 분석 중 preview는 **1개**(`previewReplicaCount: 1`, active는 HPA 그대로). 전환 직전 preview를 active 수까지 늘리므로 **전환 순간~60초만 2배**(booking HPA 최대 2 → 4개, requests 1 vCPU) → 노드 2대에서 preview가 Pending이면 전환이 멈추고 10분 뒤 abort. booking `maxReplicas`는 **2**(11/12 결정 — 4면 전환 순간 8개 × 풀 15로 RDS 연결이 경보를 넘는다, 3장). 4.2.1(11/11)은 최대 4에서 쟀으므로 이 변경을 머지한 직후(rc2 전) 최대 2로 contention·deploy를 한 번 다시 재서 선점 p95·CPU를 확인한다. 선점 p95가 300ms를 넘으면 원인을 가른다 — DB 연결 대기(`hikaricp_connections_acquire_seconds_max` 큼, CPU 여유)면 파드를 늘려도 안 풀리므로 근본 수정 A(진 요청 사유 판정을 DB 없이), CPU 부족이면 「풀 10 · 최소 유휴 10 · 최대 4」(전환 순간 8 × 10 + 35 = 115)를 수정 A와 함께. **최대 4로만 되돌리지 않는다**(전환 순간 155). 데모 주간은 머지 금지라 11/15까지 끝낸다(노션 ADR-96)
- booking 만료 스캔·아웃박스 릴레이는 **preview 파드에서도 돈다** → 만료·릴레이 로직 변경은 이전 버전과 호환되게(조건부 UPDATE로 한 번만 처리)

## 3. 설정·Secret (서비스가 읽는 이름)

값은 예시. **SeatSAC-gitops가 Public이라(ADR-74) 엔드포인트·계정 ID는 gitops에 쓰지 않는다**: `{svc}-config`(gitops)에는 `${DB_HOST}` 같은 자리만 두고, 실제 값은 Terraform ConfigMap `cinema-infra-endpoints`(`envs/staging-k8s/infra_endpoints.tf`)에서, 비밀번호는 Secret에서 온다.

#### Terraform ConfigMap `cinema-infra-endpoints` (`cinema-staging`, 모든 서비스·Flyway Job이 받는다)

| 키 | 값 | 쓰는 곳 |
| --- | --- | --- |
| `DB_HOST` | RDS 주소 (`aws_db_instance.address`) | `SPRING_DATASOURCE_URL`, Flyway Job `FLYWAY_URL` |
| `VALKEY_HOST` | Valkey primary 엔드포인트 | `SPRING_DATA_REDIS_HOST` |
| `SQS_NOTIFICATION_URL` | `https://sqs.ap-northeast-2.amazonaws.com/{계정ID}/cinema-staging-notification-events` | booking `APP_SQS_NOTIFICATION_QUEUE_URL` |
| `COGNITO_ISSUER_URI` | `https://cognito-idp.ap-northeast-2.amazonaws.com/{userPoolId}` (Cognito 전에는 빈 값) | gateway `APP_COGNITO_ISSUER_URI` |
| `COGNITO_CLIENT_ID` | 앱 클라이언트 ID (Cognito 전에는 빈 값) | gateway `APP_COGNITO_CLIENT_ID` |

| ⚠️AI `AI_VALKEY_HOST` | ai Valkey primary 엔드포인트(`cinema-staging-ai-valkey`) | ai (이름 그대로 읽음) |
| ⚠️AI `PUBLIC_API_BASE_URL` | `https://{배포ID}.cloudfront.net/api` (CloudFront 기본 도메인, Terraform 출력) | ai — 스냅샷 적재·"내 예매" 호출 |

새 엔드포인트가 생기면 이 표와 `infra_endpoints.tf`에 먼저 추가하고, gitops에는 `${키}`만 쓴다. ⚠️AI Python은 `${키}` 치환을 하지 않으므로 ai는 이 ConfigMap 키를 **이름 그대로** 읽는다.

### 공통 (ConfigMap `{svc}-config`)

| 키 | 값 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `staging` |
| `AWS_REGION` | `ap-northeast-2` |
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://${DB_HOST}:3306/{svc}_db?connectionTimeZone=UTC&sslMode=REQUIRED` |
| `SPRING_DATASOURCE_USERNAME` | `{svc}_app` |
| (`FLYWAY_URL`) | ⚠️ ConfigMap이 아니라 **Flyway Job의 env**(gitops `base/{svc}/flyway-job.yaml`): `DB_HOST`를 `cinema-infra-endpoints`에서 받아 `jdbc:mysql://$(DB_HOST):3306/{svc}_db?useSsl=true&serverSslCert=/etc/rds-ca/rds-ca.pem&trustServerCertificate=false&disableSslHostnameVerification=false&sslMode=verify-full`로 조립(쿠버네티스 env 치환, 10/25). **Flyway Job 전용**(DB 쓰는 서비스만, 앱은 읽지 않는다). flyway 11.7 이미지에는 MySQL Connector/J가 없어 MariaDB Connector/J **2.7.x**가 `jdbc:mysql://`를 처리한다. 2.x의 TLS 옵션은 `useSsl`이고(`sslMode`는 3.x 옵션이라 2.x는 무시 → TLS가 켜지지 않아 `RSA public key is not available`로 실패), `serverSslCert`로 준 RDS CA로 **서버 인증서·호스트 이름을 검증**한다. `trustServerCertificate=false`·`disableSslHostnameVerification=false`는 2.x 기본값이지만 검증을 끄지 않는다는 뜻을 URL에 명시한다. CA는 ConfigMap `rds-ca-bundle`(Terraform `envs/staging-k8s/rds_ca.tf`)을 `/etc/rds-ca`에 마운트. `sslMode=verify-full`은 3.x로 바뀔 때를 위한 것 |
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | `5` (booking만 `15` — 4.2.1 동시 선점에서 풀 대기 0.55초, 11/11). 연결 수(RDS db.t4g.small `max_connections` 약 170, 경보 `rds_connection_alarm` 130): booking `maxReplicas` 2(2장, 11/12)로 평소 booking 최대 2 × 15 = 30 + catalog 20·user 10·payment 5 = 65, 블루/그린 전환 순간~60초는 preview가 active 수까지 늘어(2장) 최대 4 × 15 = 60 + 35 = 95 < 경보. 같은 때 다른 서비스가 롤링 배포 중이면 `maxSurge: 1` 파드 몫(풀 5)이 더해진다 — catalog·user·payment가 모두 겹쳐도 + 15 = 110 < 경보(두 서비스 동시 블루/그린 금지는 롤링을 막지 않는다). (최대 4였다면 8 × 15 + 35 = 155 → 경보) |
| `SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE` | 없음 = Hikari 기본(최대 풀과 같음) (booking만 `15` = 최대 풀, 11/12) — 몰리는 순간 연결을 새로 열지 않게 늘 열어 둔다. 줄이면(예: 5) 10분 쉰 파드에 연결이 5개만 남고, 100명이 몰릴 때 모자란 연결을 하나씩 새로 열어 풀 5처럼 줄을 선다. 동작은 기본값과 같고, 풀을 다시 조정할 때 찾기 쉽게 값을 적어 둔다. 상한은 maxReplicas가 정한다 |
| `SPRING_FLYWAY_ENABLED` | `false` (마이그레이션은 Flyway 훅 Job) |
| `SPRING_DATA_REDIS_HOST` / `_PORT` / `_SSL_ENABLED` | `${VALKEY_HOST}` / `6379` / `true` (Valkey 쓰는 서비스만) |

### 서비스별

| 서비스 | ConfigMap 키 | Secret `{svc}-secrets` 키 |
| --- | --- | --- |
| gateway | ⚠️AI `APP_AI_ENABLED`(기본 `false` — 꺼져 있으면 `/api/ai/**` 라우트 없음 → 404, rc2 M6 테스트 후 `true`), `APP_SERVICES_AI_URL=http://ai:8080` — ai 라우트는 **metadata `response-timeout: 28000`**(ms, 전역 `httpclient.response-timeout: 15s`의 예외, SSE 라우트의 `-1`과 같은 방식. 없으면 15초에 `UPSTREAM_TIMEOUT`). gateway는 `APP_SERVICES_*_READ_TIMEOUT`을 읽지 않으므로 `APP_SERVICES_AI_READ_TIMEOUT`은 만들지 않는다, `APP_COGNITO_ISSUER_URI=${COGNITO_ISSUER_URI}`, `APP_COGNITO_CLIENT_ID=${COGNITO_CLIENT_ID}`, `APP_SERVICES_{CATALOG,BOOKING,USER}_URL` (issuer·client-id는 기본값이 없어 빠지면 기동 실패. `app.cognito.issuer-uri`·`app.cognito.client-id`로 바인딩 → `JwtDecoderConfig`가 issuer·`client_id` 검증) | 없음 |
| catalog | `APP_SERVICES_BOOKING_URL`, `APP_SERVICES_BOOKING_READ_TIMEOUT=300ms`, `APP_CACHE_TTL=10m` | `SPRING_DATASOURCE_PASSWORD` |
| booking | `APP_SERVICES_{CATALOG,PAYMENT}_URL`, `APP_SQS_NOTIFICATION_QUEUE_URL=${SQS_NOTIFICATION_URL}`, `APP_SEAT_HOLD_TTL_SECONDS=300`(30~600, 벗어나면 기동 실패. 멱등 처리 중 키도 같은 값. 바꾸면 이전 TTL 동안 고아 판정이 어긋나므로 데모 준비 때 한 번만), `APP_SEAT_HOLD_STRATEGY=redis`(ADR-45 인터페이스, db-lock은 나중), `APP_OUTBOX_RELAY_INTERVAL=1s`, `APP_PAYMENT_PENDING_GRACE=2m`(ADR-112 — 결제 요청이 붙은 PENDING은 만료+이 유예가 지나면 만료 스캔이 Payment를 조회해 반영. 그 전에는 보류), ⚠️ `APP_OUTBOX_RELAY_ENABLED` — 알림 큐(3.1.9)가 생기기 전에는 **`false`**(기본값). 큐가 없는 동안 켜 두면 확정마다 10회 재시도 끝에 아웃박스 행이 `FAILED`가 되어 사람이 손으로 되살려야 한다. 꺼 두면 `PENDING`으로 쌓여 있다가 켜는 순간 흘러간다(쌓인 양은 지표 `booking_outbox_pending`). 3.1.9 큐와 3.1.8 알림 Lambda 연결 뒤 `true`(런북 6-B-3) — 큐만 있고 받는 쪽이 없으면 메시지가 DLQ로도 못 가고 7일 뒤 사라진다` | `SPRING_DATASOURCE_PASSWORD` |
| user | `APP_SERVICES_{BOOKING,CATALOG}_URL`, `APP_COGNITO_ENDPOINT`(선택, 기본 `https://cognito-idp.{AWS_REGION}.amazonaws.com`, `AWS_REGION`이 없으면 `ap-northeast-2` — 프로필 첫 생성 때 `GetUser`) | `SPRING_DATASOURCE_PASSWORD`, `APP_PAYMENT_MOCK_KEY`(모의 결제 허용 키, Terraform `random_password` — k6 Job·블루/그린 분석 Job이 Secret 참조로 받아 `X-Payment-Mock-Key`로 보낸다. 없으면 기계 경로 자체가 꺼짐) |
| payment | `APP_PAYMENT_MOCK_DELAY_CARD=9999`, `APP_PAYMENT_MOCK_DELAY=5s`, `APP_PORTONE_ENABLED=true`(false면 TOSSPAY 503), `APP_PORTONE_API_BASE_URL=https://api.portone.io`(로컬은 Stub), `APP_PORTONE_STORE_ID`·`APP_PORTONE_CHANNEL_KEY_TOSSPAY`(포트원 콘솔 공개 값 — 브라우저에 노출되는 값이라 ConfigMap 가능), `APP_PORTONE_PENDING_TIMEOUT=7m`(결제창 미완료 PROCESSING 정리) | `SPRING_DATASOURCE_PASSWORD`, `APP_PORTONE_API_SECRET`(V2 API Secret), `APP_PORTONE_WEBHOOK_SECRET`(웹훅 시크릿, 테스트용) |
| ⚠️AI ai | 9장 표 (`APP_AI_*`) | `ANTHROPIC_API_KEY` (Secret 이름 `ai-secrets`) |

- 서비스 간 타임아웃 기본 `APP_SERVICES_{NAME}_CONNECT_TIMEOUT=1s`, `_READ_TIMEOUT=3s` (`libs/common-web` 기본값, 필요할 때만 덮어씀)
- Flyway Job 전용 Secret `{svc}-migrator-secrets`: `FLYWAY_USER={svc}_migrator`, `FLYWAY_PASSWORD`. 앱 파드에는 마운트하지 않는다
- 로컬(`local` 프로필): Compose MySQL·Valkey·ElasticMQ, 계정 비밀번호 `local`, SQS 엔드포인트 `http://localhost:9324`. Cognito는 staging 사용자 풀을 같이 쓴다(콜백 `http://localhost:5173/callback`)
- ⚠️AI 로컬 ai: Compose `valkey-ai`(6380) + `uvicorn` 직접 실행, `services/ai/.env`에 **개발 전용** `ANTHROPIC_API_KEY`(커밋 금지), `PUBLIC_API_BASE_URL=http://localhost:8080/api`(로컬 gateway) 또는 staging CloudFront

### frontend (빌드 시 `VITE_`, 공개 값만)

| 키 | 값 |
| --- | --- |
| `VITE_API_BASE_URL` | `/api` |
| `VITE_COGNITO_AUTHORITY` | `https://cognito-idp.ap-northeast-2.amazonaws.com/{userPoolId}` |
| `VITE_COGNITO_CLIENT_ID` | 공개 클라이언트 ID (시크릿 없음) |
| `VITE_COGNITO_DOMAIN` | `https://cinema-staging.auth.ap-northeast-2.amazoncognito.com` |
| `VITE_COGNITO_REDIRECT_URI` | `https://{배포ID}.cloudfront.net/callback` (로컬 `http://localhost:5173/callback`) |

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
| 마이그레이션 실행 | ArgoCD 훅 Job `{svc}-flyway`(**Sync 단계 wave -1**: ConfigMap `{svc}-config` wave -2 → Job → Deployment/Rollout wave 0, 실패하면 sync 중단): initContainer(같은 앱 이미지)가 `/app/db/migration`을 emptyDir로 복사 → `flyway/flyway:11.7.2`(Spring Boot 3.5 Flyway 11.7과 같은 메이저) 컨테이너가 `{svc}_migrator`로 `migrate`. URL은 Job env `FLYWAY_URL`(호스트는 `cinema-infra-endpoints`의 `DB_HOST`, 3장, 앱 URL과 별도), 계정은 `{svc}-migrator-secrets`, RDS CA는 ConfigMap `rds-ca-bundle` → `/etc/rds-ca` |
| 파일 위치 | `services/{svc}/src/main/resources/db/migration/V{yyyyMMddHHmm}__{설명}.sql` |
| 호환 | 블루/그린·롤링 중 두 버전이 같은 DB를 쓴다 → 확장 → 전환 → 정리. 컬럼 삭제·이름 변경은 다음 배포 |
| 실패 | Job 실패 = sync 실패 → 새 버전이 뜨지 않는다. 고친 마이그레이션은 새 V 번호로(이미 적용된 파일 수정 금지) |
| 시드 | 롯데 배치·편성은 `lotte-seed`의 SQL을 `catalog_migrator`로 1회 적재(김종식·한채연, 런북). Flyway에 넣지 않음 |

## 5. 필수 메트릭 (대시보드·자동 롤백이 이 이름에 의존)

| 메트릭 | 타입 | 라벨 값 | 서비스 | 쓰는 곳 |
| --- | --- | --- | --- | --- |
| `http_server_requests_seconds_*` (Spring 기본) | histogram | `status`, `uri`, `method` | 전체 (⚠️AI ai는 같은 이름으로 직접 낸다) | 5xx 비율·p95, 블루/그린 분석 |
| `booking_seat_hold_total` | counter | `result`=`success`·`conflict`·`active_hold`·`error` | booking | 신호등·동시성 데모. `success`는 PENDING 커밋(발행 전), `conflict`=409 `SEAT_UNAVAILABLE`, `active_hold`=409 `ACTIVE_HOLD_EXISTS`, `error`=5xx(Catalog 503·Valkey·RDS). **4xx 거절(검증·시작된 회차·없는 회차)은 세지 않는다** → 신호등 분모 = 네 라벨 합 |
| `booking_sse_connections` | gauge | — | booking | SSE 재연결 확인 (블루/그린 전환·노드 재기동 때 0으로 떨어졌다 다시 오르는 게 정상) |
| `booking_payment_call_total` | counter | `result`=`approved`·`declined`·`timeout`·`error` | booking | **자동 롤백**(`declined`는 실패율에서 제외) |
| `booking_hold_expiry_lag_seconds` | histogram | — | booking | 만료 반영 2초 목표. 노드 0대 구간(ADR-34) 뒤 첫 스윕은 밀린 만큼(수 시간) 큰 값이 한 번 찍히는 게 정상 → 경보는 파드 기동 후 10분 제외(3.1.7) |
| `booking_notification_publish_total` | counter | `result`=`sent`·`retry`·`failed` | booking (릴레이) | 알림 경보 |
| `booking_outbox_pending` | gauge | — | booking | 릴레이 밀림 |
| `catalog_cache_requests_total` | counter | `result`=`hit`·`miss` | catalog | 캐시 적중률 |
| `catalog_seat_counts_fallback_total` | counter | — | catalog | 300ms 초과 대체 횟수 |
| `payment_request_total` | counter | `result`=`approved`·`declined`·`processing`·`canceled` | payment | 모의 결제 결과 분포(OPS-05). 자동 롤백은 booking 쪽 `booking_payment_call_total`을 쓴다 |
| `payment_cancel_total` | counter | `reason`=취소 요청 `reason`(계약 `payment.internal.openapi.yaml` enum 값만) | payment | 환불 사유별 건수(OPS-05) |
| `payment_pg_call_total` | counter | `result`=`ok`·`error`·`timeout`, `op`=`get`·`cancel` | payment | 포트원 호출 결과(ADR-112). 웹훅 수신은 `http_server_requests`(`uri=/webhooks/portone`)로 본다 |
| ⚠️AI `ai_chat_total` | counter | `path`=`cache_exact`·`cache_semantic`·`preset`·`nl2sql`·`my_bookings`·`rejected`·`out_of_scope`, `result`=`ok`·`error` | ai | 경로별 처리 수 — **토큰 절감 데모의 분모** |
| ⚠️AI `ai_tokens_total` | counter | `kind`=`input`·`output`·`cache_read`·`cache_write`, `path` | ai | 토큰 사용량. 절감 그래프는 `input+output`(우리 캐시 효과), 프롬프트 캐싱은 `cache_read`로 **따로** 표시 |
| ⚠️AI `ai_cache_requests_total` | counter | `layer`=`exact`·`semantic`, `result`=`hit`·`miss` | ai | 캐시 적중률 |
| ⚠️AI `ai_llm_call_seconds` | histogram | `result`=`ok`·`timeout`·`overloaded`·`rate_limited`·`error` | ai | Claude API 지연·장애 |
| ⚠️AI `ai_sql_exec_seconds` · `ai_rejected_total` | histogram · counter | — · `reason`(SQL 검증 거부 사유) | ai | SQLite 실행 시간 · 검증기 동작 |
| ⚠️AI `ai_snapshot_age_seconds` · `ai_quota_rejected_total` | gauge · counter | `scope`=`today`·`other` · `window`=`minute`·`day` | ai | 스냅샷 신선도(오늘 1분·나머지 10분) · 한도 초과 |
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
| 전제 | EKS VPC CNI `enableNetworkPolicy=true` (3.1.2) |
| 보안그룹 | RDS 3306·Valkey 6379는 노드 보안그룹에서만. ALB는 CloudFront 관리형 접두사 목록만 |
| 외부 호출 | booking → SQS, user → Cognito `GetUser`, gateway → Cognito JWKS, **payment → 포트원 `api.portone.io`**(ADR-112): NAT 경유 HTTPS. 포트원 → CloudFront `/api/webhooks/portone`(인바운드, gateway `permitAll`, 서명 검증은 payment). ⚠️AI ai → Claude API(`api.anthropic.com`), ai → **CloudFront `PUBLIC_API_BASE_URL`**(공개 API — 스냅샷·내 예매): NAT 경유 HTTPS. ai는 클러스터 안에서 gateway를 직접 부르지 않는다(gateway NetworkPolicy는 public 서브넷만 허용, 기존 정책은 수정하지 않음) |
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
| 14 | NetworkPolicy 허용 목록 (6장) | 김종식 (3.1.13) | 전 서비스 내부 호출 | 내부 API 통합 후 | `infra/scripts/np-check.sh` 전부 ✅ — Cognito 뒤 `REQUIRE_GATEWAY=1`로 gateway → payment BLOCKED까지. **그리고** ALB → gateway 실경로(스크립트 밖): ALB 대상 그룹 gateway healthy + CloudFront `/api/movies` 200 (런북 6-E) |
| 15 | 부하 테스트 시나리오 대상 API·데이터 | 남궁일 | 김종식 OPS-10 (k6 Job) | 4.2.1 전 | k6 스크립트 dry-run |
| ⚠️AI 16 | ECR `cinema/ai`·ai Valkey·`ai-secrets`·`cinema-infra-endpoints` 키 2개·`ng-ai`·Application `cinema-ai` | 김종식 (3.1.18·3.1.19) | 김서현 staging 배포 | rc1(11/6) | `kubectl -n argocd get application cinema-ai`, `ng-ai` 1대일 때 ai readiness 200 |
| ⚠️AI 17 | CI(`changed-services.sh` ai 분기, `pr-check` `python`·ai 기동 확인, `build-push` ai) — **#79에 먼저 포함**(3.4.8 PR이 검사를 받도록) · gitops `base/ai`·`overlays/staging/ai` | 김종식 (3.1.20) | 김서현 (main 머지 = 배포) | CI는 3.4.8 리베이스 전, gitops는 3.1.18 apply 후 | PR `image`(ai)·`python` 통과 |
| ⚠️AI 18 | gateway `/api/ai/**` 라우트(플래그 off, 라우트 metadata `response-timeout: 28000`) + 분석 쿼리 `/ai` 제외 | 한채연(3.3.8)·김종식(3.1.21) | 김서현·한채연 화면 | **rc1(11/6)** | 플래그 off에서 `/api/ai/chat` 404, gateway 블루/그린 통과 |
| ⚠️AI 19 | `contracts/openapi/ai.openapi.yaml` + 예시 | 김서현·한채연 (2.2.8) | 한채연 채팅 화면(3.3.7) | 11/6 | contract-check 통과 |

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
- [ ] ⚠️AI AI 대시보드 `SeatSAC AI` + 런북 6-G(켜기·끄기·질의 금지 구간) (3.1.22)

## 9. ⚠️AI ai 서비스 (Python, ADR-82~87)

| 항목 | 앱(김서현)이 할 일 | 인프라(김종식)가 할 일 |
| --- | --- | --- |
| 런타임 | Python 3.12, FastAPI + uvicorn(워커 1), `pyproject.toml` + `uv.lock`. 테스트 pytest, 린트 ruff | `pr-check` `python` job(ruff·pytest, `services/ai/**` 바뀐 PR만), `changed-services.sh`의 `ALL_SERVICES`에 `ai` + **ai만 Gradle 대신 `docker build services/ai`** 분기 |
| 포트·헬스·메트릭 | 8080. `/actuator/health/liveness`(프로세스만), `/actuator/health/readiness`(스냅샷 첫 적재 완료 + ai Valkey PING), `/actuator/prometheus` | Java 서비스와 같은 프로브·PodMonitor 템플릿(`app.kubernetes.io/name: ai`, 포트 `http`, 15초). startupProbe 5초 × 30회(임베딩 모델·첫 스냅샷 적재) |
| 이미지 | `python:3.12-slim`, UID 10001, 임베딩 모델(`paraphrase-multilingual-MiniLM-L12-v2`, ONNX, fastembed)을 **빌드 때** `/app/models`에 넣는다(실행 중 다운로드 없음). 시드·스냅샷 파일은 이미지에 넣지 않는다 | `build-push`가 ECR `cinema/ai:{sha7}` → gitops `overlays/staging/ai/image-tag`. Trivy 동일 기준 |
| 비루트·파일 | 쓰기 경로 `/tmp`만. SQLite는 **메모리 DB(`:memory:`)** — 디스크에 남기지 않음 | `runAsNonRoot`, `readOnlyRootFilesystem` + `/tmp` emptyDir |
| 로그 | stdout JSON 한 줄(`timestamp`·`level`·`service=ai`·`traceId`·`userId`·`path`·`latencyMs`·`tokens`·`questionLength`·`questionHash`·`sqlTemplate`·`rowCount`). **질문 원문·SQL 리터럴 값·결과 행·Claude 응답 본문은 남기지 않는다** — 질문에 이메일·전화번호 같은 개인정보가 들어올 수 있다. `questionHash` = HMAC-SHA256(질문, 파드 기동 때 만든 임의 키) 앞 16자(같은 파드 안 반복 질문 추적용, 되돌릴 수 없음), `sqlTemplate` = 리터럴을 `?`로 바꾼 SQL(sqlglot). 디버깅에 원문이 필요하면 로컬에서 재현한다 | `kubectl logs` |
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
| `APP_AI_CACHE_TTL` / `APP_AI_SESSION_TTL` | `1h` / `30m` | |
| `APP_AI_SEMANTIC_CACHE_ENABLED` / `APP_AI_SEMANTIC_THRESHOLD` | `true` / `0.92` | rc2까지 의미 캐시가 안 되면 `false`로 정확 일치 캐시만(B1) |
| `APP_AI_LIMIT_PER_MINUTE` / `APP_AI_LIMIT_PER_DAY` | `10` / `100` | 사용자당. 동시 1건 |
| `ANTHROPIC_API_KEY` (Secret `ai-secrets`) | Terraform sensitive 변수 → K8s Secret | staging 키. 로컬은 개발 전용 키를 `.env`에 |

**시간 예산** (`/api/ai/chat` 한 번, 바깥이 항상 안쪽보다 길다)

| 층 | 상한 | 넘으면 |
| --- | --- | --- |
| CloudFront 원본 응답 대기 | 30초 (기존 설정, 3.1.11) | 504 |
| gateway `/api/ai/**` 라우트 `response-timeout` | **28초** (`28000`) | 504 `UPSTREAM_TIMEOUT` |
| ai 요청 전체 기한 `APP_AI_REQUEST_DEADLINE_SECONDS` | **25초** | 503 `AI_LLM_UNAVAILABLE` `TIMEOUT` — 정상 경로에서는 여기서 먼저 끝나 화면이 ai의 오류 형식을 받는다 |
| └ Claude 호출 1회 | min(15초, 남은 시간) × 최대 3회 | 다음 호출 없이 위 503 |
| └ SQLite 실행 1회 | min(5초, 남은 시간) | `REJECTED` 또는 SQL 오류 재요청 |
| `ai:inflight:{userId}` 잠금 | 40초 | 25초 기한보다 길어 처리 중 풀리지 않는다 |

### 9-2. 실행 규칙

- **ai는 RDS·기존 Valkey에 연결하지 않는다.** 데이터는 공개 API(`PUBLIC_API_BASE_URL`)로 채운 메모리 SQLite, 캐시·세션·한도는 ai Valkey
- SQLite 읽기 전용: `:memory:`를 URI `mode=ro`로 열면 비어 있는 읽기 전용 DB가 되어 적재할 수 없다. 그래서 **적재 → 잠금 → 교체** 순서로 한다. ① 갱신마다 **새** `:memory:` 연결을 쓰기 가능으로 열어 적재 ② 끝나면 그 연결에 `PRAGMA query_only=ON` + `set_authorizer`(SELECT·READ·FUNCTION만 허용, 나머지 DENY — `PRAGMA query_only=OFF`·`ATTACH`·쓰기 SQL도 여기서 막힌다. `query_only`만으로는 SQL로 다시 끌 수 있다) ③ 질의용 참조를 새 연결로 바꾸고 이전 연결은 진행 중 질의가 끝나면 닫는다(이중 버퍼). 질의 쪽은 쓰기 가능한 연결을 받을 수 없다. 적재 실패면 이전 스냅샷을 그대로 쓰고 `ai_snapshot_age_seconds`가 늘어난다
- 스냅샷 적재 호출(오늘 1분마다 4회, 10분마다 약 50회)은 CloudFront → gateway → catalog로 들어간다. **부하 테스트(4.2.1)·블루/그린 전환 테스트(4.2.2) 중에는 `ng-ai` desired 0 · 플래그 off, 좌석 데모 중에는 `ng-ai` desired 0 · AI 질의 금지(플래그는 rc2에서 켜 둔 채 — 데모 주간은 머지 금지라 끌 수 없다)** → 테스트 환경 = 데모 환경 (런북 6-G)
- `ng-ai`는 EventBridge Scheduler 평일 켜기 대상에서 **뺀다**(평소 0대). 켜기·끄기는 `cluster-scale.sh up ng-ai`·`down ng-ai`(cinema-admin). `cluster-scale.sh up`·`up 2`와 `cluster-schedule`의 `up`은 `ng-ai`를 켜지 않고 `down`은 `ng-ai`까지 끈다(켜 둔 채 잊어도 백업 끄기·수동 `down`이 끈다)
- 플래그 켜기는 rc2(11/13) M6 테스트가 끝난 뒤 gitops PR 1회(gateway 블루/그린 1회). 데모 주간에는 플래그·ai 코드 모두 머지 금지

<노션 반영 필요> WBS 3.1.5·3.1.6·3.1.12 메모에 "서비스 실행 계약(docs/service-runtime-contract.md) 기준", 기능명세서 OPS-11 "SSO" → "IAM 역할 + MFA(ADR-47)", OPS-15 허용 목록: ⚠️AI 11/5부터 ai 행 다시 추가(gateway → ai, monitoring → ai)

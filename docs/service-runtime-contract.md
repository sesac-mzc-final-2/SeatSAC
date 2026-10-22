# 서비스 실행 계약 (앱 ↔ 인프라)

> 기준 2026-10-21. 개발(서비스 코드)과 인프라/데브옵스(Terraform·gitops·CI)가 **맞닿는 모든 약속**을 한곳에 둔다. 이름 규칙은 `docs/conventions.md`, API는 `contracts/`.
> 이 표대로 만들면 서로 회의 없이 붙는다. 표를 바꿀 때는 PR에 양쪽(서비스 담당 + 김종식) 승인을 받는다.
> 범위: 노드 t3.large 2대(ng-a 1 · ng-c 1, ADR-7·34), 환경 staging 하나. 값은 10/22 확정(ADR-57·58·59·63).

---

## 1. 모든 서비스 공통 (Java 5개)

| 항목 | 앱이 할 일 | 인프라가 할 일 |
| --- | --- | --- |
| 포트 | 8080 하나 (앱·actuator 같은 포트) | Service `{svc}:8080`, 포트 이름 `http` |
| 헬스 | `management.endpoint.health.probes.enabled=true`. readiness 그룹 = `readinessState,db` (gateway는 `readinessState`만). liveness에 외부 의존 넣지 않음 | 프로브(아래 2장) |
| 메트릭 | `micrometer-registry-prometheus`, `/actuator/prometheus` 노출, 공통 태그 `application={svc}`. `http.server.requests` 히스토그램 켜기(`management.metrics.distribution.percentiles-histogram.http.server.requests=true`) | ServiceMonitor `{svc}`(라벨 `app.kubernetes.io/name`, 포트 `http`, 15초) |
| 종료 | `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=20s`. booking은 SIGTERM 때 SSE 연결을 정상 종료 | `preStop: sleep 5`, `terminationGracePeriodSeconds: 35` |
| 로그 | stdout JSON 한 줄(`libs/common-web` logback). 파일 로그 금지 | `kubectl logs`로 traceId 검색 (Loki 없음, OPS-09 P3) |
| 공통 코드 | `libs/common-core`·`common-web` 의존 → 오류 형식·traceId·`@CurrentUser`·내부 호출 클라이언트 자동 적용 | `libs/**` 변경 시 `pr-check`가 5개 전부 테스트, 배포는 안 함 |
| 시간 | JDBC URL에 `connectionTimeZone=UTC`, `Clock.systemUTC()` 주입 | RDS 파라미터 그룹 `time_zone=UTC` |
| 설정 주입 | 아래 3장 이름만 읽는다. 기본값은 `application-local.yml`에만 | `envFrom`: ConfigMap `{svc}-config` + Secret `{svc}-secrets` |
| 이미지 | `bootJar` 결과 `build/libs/app.jar`, Dockerfile은 4장 템플릿 | `build-push`가 x86 빌드 → ECR `cinema/{svc}:{sha7}` |
| 비루트 | 쓰기 경로 `/tmp`만 | `runAsNonRoot: true`, `readOnlyRootFilesystem: true` + `/tmp` emptyDir |

## 2. 서비스별 배포 방식·자원

| 서비스 | 배포 | replicas (HPA) | requests cpu / mem | limits mem | Valkey | RDS 스키마 | Pod Identity |
| --- | --- | --- | --- | --- | --- | --- | --- |
| gateway | **블루/그린** (Rollout) | 1 (HPA 끔, 블루/그린 중 2) | 250m / 512Mi | 768Mi | — | — | 없음 |
| booking | **블루/그린** (Rollout) | 1 (1~4, CPU 70%) | 250m / 512Mi | 768Mi | ✅ | `booking_db` | `cinema-staging-booking-pod`: `sqs:SendMessage`·`GetQueueUrl` on 알림 큐 |
| catalog | 롤링 (maxUnavailable 0, maxSurge 1) | 1 (1~4) | 200m / 448Mi | 640Mi | ✅ | `catalog_db` | 없음 |
| user | 롤링 | 1 (1~2) | 150m / 384Mi | 576Mi | — | `user_db` | 없음 (`GetUser`는 Access Token으로 호출, IAM 불필요) |
| payment | 롤링 | 1 (고정) | 150m / 384Mi | 576Mi | — | `payment_db` | 없음 |

- JVM: `-XX:MaxRAMPercentage=75` (Dockerfile). limits cpu는 두지 않는다(스로틀로 p95 악화)
- 두 서비스 동시 블루/그린 금지(노드 2대). 공통 모듈 급한 반영도 한 번에 한 서비스씩: catalog·user·payment → booking → gateway
- 프로브: `startupProbe` readiness 경로 5초 × 30회, `readinessProbe` 5초 / 실패 3, `livenessProbe` 10초 / 실패 3
- 블루/그린: active Service `{svc}`, preview Service `{svc}-preview`, `prePromotionAnalysis` 통과 후 전환, `scaleDownDelaySeconds: 60`. gateway는 ALB 대상 그룹 확인(ADR-35)
- booking 만료 스캔·아웃박스 릴레이는 **preview 파드에서도 돈다** → 만료·릴레이 로직 변경은 이전 버전과 호환되게(조건부 UPDATE로 한 번만 처리)

## 3. 설정·Secret (서비스가 읽는 이름)

값은 예시. 실제 값은 Terraform 출력(엔드포인트)과 Secret(비밀번호)에서 온다.

### 공통 (ConfigMap `{svc}-config`)

| 키 | 값 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `staging` |
| `AWS_REGION` | `ap-northeast-2` |
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://{rds-endpoint}:3306/{svc}_db?connectionTimeZone=UTC&sslMode=REQUIRED` |
| `SPRING_DATASOURCE_USERNAME` | `{svc}_app` |
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | `5` |
| `SPRING_FLYWAY_ENABLED` | `false` (마이그레이션은 PreSync Job) |
| `SPRING_DATA_REDIS_HOST` / `_PORT` / `_SSL_ENABLED` | `{valkey-primary-endpoint}` / `6379` / `true` (Valkey 쓰는 서비스만) |

### 서비스별

| 서비스 | ConfigMap 키 | Secret `{svc}-secrets` 키 |
| --- | --- | --- |
| gateway | `APP_COGNITO_ISSUER_URI=https://cognito-idp.ap-northeast-2.amazonaws.com/{userPoolId}`, `APP_COGNITO_CLIENT_ID`, `APP_SERVICES_{CATALOG,BOOKING,USER}_URL` (issuer·client-id는 기본값이 없어 빠지면 기동 실패) | 없음 |
| catalog | `APP_SERVICES_BOOKING_URL`, `APP_SERVICES_BOOKING_READ_TIMEOUT=300ms`, `APP_CACHE_TTL=10m` | `SPRING_DATASOURCE_PASSWORD` |
| booking | `APP_SERVICES_{CATALOG,PAYMENT}_URL`, `APP_SQS_NOTIFICATION_QUEUE_URL`, `APP_SEAT_HOLD_TTL_SECONDS=300`, `APP_SEAT_HOLD_STRATEGY=redis`(ADR-45 인터페이스, db-lock은 나중), `APP_OUTBOX_RELAY_INTERVAL=1s` | `SPRING_DATASOURCE_PASSWORD` |
| user | `APP_SERVICES_{BOOKING,CATALOG}_URL` | `SPRING_DATASOURCE_PASSWORD` |
| payment | `APP_PAYMENT_MOCK_DELAY_CARD=9999`, `APP_PAYMENT_MOCK_DELAY=5s` | `SPRING_DATASOURCE_PASSWORD` |

- 서비스 간 타임아웃 기본 `APP_SERVICES_{NAME}_CONNECT_TIMEOUT=1s`, `_READ_TIMEOUT=3s` (`libs/common-web` 기본값, 필요할 때만 덮어씀)
- Flyway Job 전용 Secret `{svc}-migrator-secrets`: `FLYWAY_USER={svc}_migrator`, `FLYWAY_PASSWORD`. 앱 파드에는 마운트하지 않는다
- 로컬(`local` 프로필): Compose MySQL·Valkey·ElasticMQ, 계정 비밀번호 `local`, SQS 엔드포인트 `http://localhost:9324`. Cognito는 staging 사용자 풀을 같이 쓴다(콜백 `http://localhost:5173/callback`)

### frontend (빌드 시 `VITE_`, 공개 값만)

| 키 | 값 |
| --- | --- |
| `VITE_API_BASE_URL` | `/api` |
| `VITE_COGNITO_AUTHORITY` | `https://cognito-idp.ap-northeast-2.amazonaws.com/{userPoolId}` |
| `VITE_COGNITO_CLIENT_ID` | 공개 클라이언트 ID (시크릿 없음) |
| `VITE_COGNITO_DOMAIN` | `https://cinema-staging.auth.ap-northeast-2.amazoncognito.com` |
| `VITE_COGNITO_REDIRECT_URI` | `https://{배포ID}.cloudfront.net/callback` (로컬 `http://localhost:5173/callback`) |

`frontend-deploy` 워크플로가 Terraform 출력에서 값을 넣는다(GitHub Variables). 토큰은 `react-oidc-context`가 관리하고 `Authorization: Bearer`로 보낸다. 저장소는 **메모리**(`InMemoryWebStorage`, localStorage 금지). 새로고침하면 `signinRedirect`로 Cognito 세션을 이용해 다시 받는다(비밀번호 재입력 없음, ADR-63).

## 4. 이미지·DB 마이그레이션

```dockerfile
# services/{svc}/Dockerfile — 모든 Java 서비스 공통
FROM eclipse-temurin:21-jre
RUN useradd -r -u 10001 app
WORKDIR /app
COPY build/libs/app.jar app.jar
COPY src/main/resources/db/migration /app/db/migration   # Flyway Job이 복사해 감 (gateway는 없음)
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
```

| 항목 | 규칙 |
| --- | --- |
| 마이그레이션 실행 | ArgoCD **PreSync** Job `{svc}-flyway`: initContainer(같은 앱 이미지)가 `/app/db/migration`을 emptyDir로 복사 → `flyway/flyway`(버전 고정) 컨테이너가 `{svc}_migrator`로 `migrate` |
| 파일 위치 | `services/{svc}/src/main/resources/db/migration/V{yyyyMMddHHmm}__{설명}.sql` |
| 호환 | 블루/그린·롤링 중 두 버전이 같은 DB를 쓴다 → 확장 → 전환 → 정리. 컬럼 삭제·이름 변경은 다음 배포 |
| 실패 | Job 실패 = sync 실패 → 새 버전이 뜨지 않는다. 고친 마이그레이션은 새 V 번호로(이미 적용된 파일 수정 금지) |
| 시드 | 롯데 배치·편성은 `lotte-seed`의 SQL을 `catalog_migrator`로 1회 적재(김종식·한채연, 런북). Flyway에 넣지 않음 |

## 5. 필수 메트릭 (대시보드·자동 롤백이 이 이름에 의존)

| 메트릭 | 타입 | 라벨 값 | 서비스 | 쓰는 곳 |
| --- | --- | --- | --- | --- |
| `http_server_requests_seconds_*` (Spring 기본) | histogram | `status`, `uri`, `method` | 전체 | 5xx 비율·p95, 블루/그린 분석 |
| `booking_seat_hold_total` | counter | `result`=`success`·`conflict`·`active_hold`·`error` | booking | 신호등·동시성 데모 |
| `booking_sse_connections` | gauge | — | booking | SSE 재연결 확인 |
| `booking_payment_call_total` | counter | `result`=`approved`·`declined`·`timeout`·`error` | booking | **자동 롤백**(`declined`는 실패율에서 제외) |
| `booking_hold_expiry_lag_seconds` | histogram | — | booking | 만료 반영 2초 목표 |
| `booking_notification_publish_total` | counter | `result`=`sent`·`retry`·`failed` | booking (릴레이) | 알림 경보 |
| `booking_outbox_pending` | gauge | — | booking | 릴레이 밀림 |
| `catalog_cache_requests_total` | counter | `result`=`hit`·`miss` | catalog | 캐시 적중률 |
| `catalog_seat_counts_fallback_total` | counter | — | catalog | 300ms 초과 대체 횟수 |

블루/그린 분석(`SeatSAC-gitops/analysis/`):

| 대상 | 조건 (preview 파드, 1분 × 3회) |
| --- | --- |
| booking | 5xx 비율 ≤ 1% **그리고** `booking_payment_call_total{result=~"timeout\|error"}` / (`approved`+`declined`+`timeout`+`error`) ≤ 5% |
| gateway | 5xx 비율 ≤ 1% |

preview·active 구분은 파드 라벨 `rollouts-pod-template-hash`로 한다(앱은 아무것도 안 해도 됨).

## 6. 네트워크

| 규칙 | 내용 |
| --- | --- |
| NetworkPolicy | 앱 namespace **ingress 기본 거부** + 허용: ALB(VPC CIDR) → gateway, gateway → catalog·booking·user, catalog → booking, booking → catalog·payment, user → booking·catalog, `monitoring` → 전체(8080). egress는 막지 않음 |
| 전제 | EKS VPC CNI `enableNetworkPolicy=true` (3.1.2) |
| 보안그룹 | RDS 3306·Valkey 6379는 노드 보안그룹에서만. ALB는 CloudFront 관리형 접두사 목록만 |
| 외부 호출 | booking → SQS, user → Cognito `GetUser`, gateway → Cognito JWKS: NAT 경유 HTTPS |
| Gateway 응답 타임아웃 | 하위 서비스 응답 대기 **15초**(`httpclient.response-timeout`, BKG-10 결제 최악 경로 ≈ 12초 = Booking→Payment 1+3, 재시도 1+3, 조회 1+3 수용). 초과·연결 실패는 503 `SERVICE_UNAVAILABLE`. SSE 라우트는 없음(아래 SSE) |
| SSE | Gateway 라우트 `/screenings/*/seats/stream` 응답 타임아웃 없음, CloudFront 원본 응답 대기 30초 > heartbeat 15초, ALB 유휴 120초 |

## 7. 개발 ↔ 인프라 인계 지점

"넘겨주는 쪽"이 끝나야 "받는 쪽"이 시작할 수 있는 것만 적었다. 필요 시점이 밀리면 스크럼에서 바로 말한다.

| # | 넘겨주는 것 | 주는 쪽 | 받는 쪽 (작업) | 필요 시점 | 확인 방법 |
| --- | --- | --- | --- | --- | --- |
| 1 | 로컬 Compose(MySQL 8.4·Valkey 8.2·ElasticMQ) + 스키마·계정 SQL | 김종식 | 개발 전원 (서비스 골격) | 즉시 | `docker compose -f infra/compose/docker-compose.yml up` 후 각 `{svc}_app` 접속 |
| 2 | `libs/common-core`·`common-web` 최소판 (오류·traceId·`@CurrentUser`·내부 클라이언트) | 한채연·남궁일 | 전 서비스, 김종식(메트릭 공통 태그) | 서비스 골격 커밋 전 | `error.v1.json` 스키마 테스트 통과 |
| 3 | Flyway V1 (catalog·user 완료, booking·payment ERD 6차) | 각 서비스 담당 | 김종식 3.1.12 (DB 계정·GRANT·Secret) | 3.1.12 시작 전 | Compose에서 `migrate` 성공 |
| 4 | RDS·Valkey 엔드포인트, `{svc}-secrets`, `{svc}-migrator-secrets`, `time_zone=UTC` | 김종식 (3.1.3·3.1.12) | 각 서비스 staging 배포, 시드 적재 | 첫 staging 배포 전 | `kubectl get secret -n cinema-staging` (developer는 이름만 보임) |
| 5 | Cognito 사용자 풀·앱 클라이언트·`ADMIN` 그룹·Pre sign-up Lambda | 김종식 (Terraform) | 한채연 GW-02·3.3.3·로그인 화면 | 3.3.1 인증 필터 시작 전 | Hosted UI 로그인 → Access Token 발급 |
| 6 | EKS + Access Entry(4명) + namespace `cinema-staging` | 김종식 (3.1.2·3.1.10) | 개발 전원 (`kubectl logs`·port-forward) | 첫 staging 배포 전 | 4명 `kubectl get pods -n cinema-staging` |
| 7 | 서비스 Dockerfile·`bootJar` 이름·actuator 설정 | 각 서비스 담당 | 김종식 3.1.4 `build-push` | 서비스 첫 머지 | `pr-check`에서 이미지 빌드 성공 |
| 8 | gitops `base/{svc}` (Deployment/Rollout·Service·HPA·NetworkPolicy·ServiceMonitor·Flyway Job) | 김종식 (3.1.5) | 각 서비스 (main 머지 = 배포) | 첫 staging 배포 | ArgoCD Synced·Healthy |
| 9 | 필수 메트릭 구현 (5장) | 남궁일·한채연 | 김종식 3.1.6 AnalysisTemplate, 3.1.7 대시보드 | 3.1.6 블루/그린 시연 전 | `/actuator/prometheus`에 이름·라벨 존재 |
| 10 | SQS 큐·DLQ, booking Pod Identity, 알림 Lambda | 김종식 (3.1.8·3.1.9) | 남궁일 3.2.6 (확정·아웃박스 릴레이) | 3.2.6 통합 테스트 전 | 확정 → Lambda 로그에 `eventId` |
| 11 | `seat-event`·`booking.confirmed` 발행 테스트 (examples로 검증) | 남궁일 | 김종식 (Lambda 소비), 화면 | 3.2.3·3.2.6 | 발행 테스트가 `contracts/events` 스키마 통과 |
| 12 | CloudFront·S3·`frontend-deploy` + `VITE_` 값 | 김종식 (3.1.11) | 한채연·남궁일 화면 | 화면 첫 배포 | CloudFront 도메인 새로고침·`/api` 동작 |
| 13 | ALB·Gateway 라우트(SSE 타임아웃 없음, `/api` StripPrefix) | 김종식(ALB·CloudFront) · 한채연(라우트) | 남궁일 BKG-05 | 3.2.3 SSE 확인 | CloudFront 경유 SSE 15초 ping 유지 |
| 14 | NetworkPolicy 허용 목록 (6장) | 김종식 (3.1.13) | 전 서비스 내부 호출 | 내부 API 통합 후 | gateway 파드 → payment 실패 확인 |
| 15 | 부하 테스트 시나리오 대상 API·데이터 | 남궁일 | 김종식 OPS-10 (k6 Job) | 4.2.1 전 | k6 스크립트 dry-run |

## 8. 인프라 쪽 남은 정리

- [x] Compose·Terraform에서 `stats_db`·`stats_lambda`·`lambda-stats` 보안그룹·`lambdas/daily-stats` 삭제 (10/22, SG는 다음 apply에서 삭제)
- [ ] ElastiCache Valkey `cache.t4g.micro` + 메모리 80%·Evictions 경보 (3.1.3, ADR-59)
- [ ] RDS Single-AZ (ADR-61)
- [ ] Compose `elasticmq-native:latest` → 버전 고정 — 김종식
- [ ] `infra/terraform/envs/staging`에 Cognito·SQS·Pod Identity 모듈 추가 시 이 문서 3·6장 이름 그대로 — 김종식

<노션 반영 필요> WBS 3.1.5·3.1.6·3.1.12 메모에 "서비스 실행 계약(docs/service-runtime-contract.md) 기준", 기능명세서 OPS-11 "SSO" → "IAM 역할 + MFA(ADR-47)", OPS-15 허용 목록에서 ai 행 삭제

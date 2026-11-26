# SeatSAC (싯싹) — 팀 공통 컨텍스트

> 기준 2026-10-22 · 회의록1·2 결정(ADR-49~64) 반영 · ⚠️AI 2026-11-05 AI 재도입(ADR-82~87) 반영 · 🆕 2026-11-23 MSA 고도화(노션 ADR-114~125, API 명세서 v1.5) 반영. `docs/` 6개 문서를 순서대로 합친 파일이다.
> **고칠 때는 `docs/`의 원본을 PR로 고치고 이 파일을 다시 만든다**(`docs/` → `CLAUDE.md`). 노션과 다르면 이 파일(레포)이 우선이다.

## 0. 먼저 읽을 것

**프로젝트**: 실시간 좌석 선점과 무중단 배포를 시연하는 영화 예매 사이트. 4명, 2026-10-14 ~ 11-20(발표). staging 하나가 곧 데모 환경이고 **main 머지 = 배포**다.

| 데모 | 증명할 것 |
| --- | --- |
| 무중단 배포 | 배포(booking·gateway 블루/그린) 중 k6 부하에서 오류율 0%, 실패 버전 자동 롤백 |
| 실시간 좌석 | 같은 좌석 100명 동시 요청 → 성공 정확히 1명, 선점 p95 300ms 이하, 다른 화면 반영 1초 이내 |
| 🆕 MSA 고도화 (11/23~27) | 체크아웃 saga(쿠폰 예약 → 결제 → 확정, 실패 시 보상)가 메시징으로 끝까지 가고, 서비스 하나(또는 AZ 하나)를 멈춰도 나머지가 동작하는 것. 관리자 `/ops`에서 상태·정지/가동·오류 리포트 |
| ⚠️AI AI 챗봇 (2차, 11/17) | 사용자 질문("20시에 시작하는 영화")을 캐시·프리셋·NL2SQL로 처리, 반복·유사 질문에서 Claude API 토큰이 0이 되는 것(절감률·오적중률 함께) |

| 담당 | 영역 |
| --- | --- |
| 한채연 | gateway · catalog · 화면(메인·상영시간·로그인·마이페이지·결제) · 좌석 배치 시드 · ~~user~~(🆕 11/24 김종식으로) |
| 남궁일 | booking · payment · 좌석 화면 · 백업 관리자(`cinema-admin`) |
| 김종식 | Infra · DevOps (Terraform · CI/CD · gitops · 관측 · Lambda) · ⚠️AI ai 인프라(ng-ai·ai Valkey·Secret·CI) · 🆕 user(11/24, 한채연에서 — #186) |
| ⚠️AI 김서현 | ai 서비스(Python FastAPI) · 스냅샷·NL2SQL·캐시·평가 세트 · AI 데모 · 🆕 `ai_db` 복제본·`unmet_demand`·통계 API · 이용 안내 RAG(6.4.7)·수요 → 백로그 워크플로우(6.4.8) |
| 🆕 MSA (11/23~) | 남궁일: booking saga·payment 명령·화면(체크아웃) · 한채연: gateway(`X-User-Grade`·본문 제한)·catalog 이벤트·화면(쿠폰·관리자) · 김종식: RDS 6·NAT·SNS/SQS·ESO·Karpenter·WAF·리포트·**ops-api** · 🆕 11/24 **coupon**(서비스·계약, #178)·**user 복제본**(#186) — 한채연에서 |

**절대 규칙**

- 비밀 값(액세스 키·비밀번호·tfstate 내용·민감한 tfvars 값·MFA 정보)을 코드·문서·PR·커밋에 쓰지 않는다. 발표 후 레포가 Public이 된다
- 롯데시네마 원본·fixture·시드는 비공개 `lotte-seed`에만. 롯데 실제 예매 API 호출 금지, 롯데 포스터 이미지 표시 금지
- AWS 콘솔에서 리소스를 만들지 않는다(Terraform만). `terraform apply`는 김종식(또는 백업 관리자)이 로컬에서 `cinema-admin`으로만, CI는 plan만. bootstrap·global은 destroy 금지
- 클러스터를 `kubectl apply/edit/delete`로 직접 고치지 않는다(GitOps만). 사람이 ECR·frontend S3에 직접 올리지 않는다(CI만)
- 계약(`contracts/`)을 먼저 PR로 합의하고 구현한다. 필드 추가만, 깨는 변경은 새 엔드포인트·`.v2`
- 이름·설정·메트릭은 아래 표에 있는 것만 쓰고, 새로 만들면 표에 먼저 추가한다
- 🆕 서비스는 **자기 RDS 인스턴스만**(`DB_HOST_{SVC}`), 다른 서비스 데이터는 API·메시지로. 메시지 소비는 inbox로 중복을 무시하고, 상태 전이는 조건부 UPDATE. Secret 값은 Secrets Manager → ESO(gitops에는 이름만). ops-api의 정지/가동이 바꾼 replicas는 시연 뒤 되돌린다
- ⚠️AI ai는 기존 MSA DB(RDS)·기존 Valkey·기존 NetworkPolicy·다른 Application을 건드리지 않는다. ai가 부르는 쓰기 API는 사용자가 확인 버튼을 누른 뒤의 `POST /seats/hold` 하나뿐이다(플래그 `APP_AI_SEAT_HOLD_ENABLED` 기본 켜짐 — `false`로 끈다, 확인 버튼 없이는 부르지 않는다, 공개 주소로만·사용자 토큰 그대로 — 결제·취소는 부르지 않는다. 5장 ADR 보완 행). M6(11/9~13)에는 ai 경로(와 ADR-87의 예외 — 테스트 준비·3.3.7 AI 화면)만 머지할 수 있고, 부하·전환 테스트 중에는 `ng-ai` 0대·플래그 off, 좌석 데모 중에는 `ng-ai` 0대·AI 질의 금지(플래그는 rc2에서 켜 둔 채)

---

# 1. 네이밍·공통 규약 (`docs/conventions.md`)

> 기준 2026-10-22 (회의록2 결정 반영, ADR-49~64). 03a 명명 규칙 개정을 확정한 최종본. 03a 이후 결정(Cognito ADR-37, API 명세서 v1.1, 2.2.4 v0.3, ERD 6차)을 반영해 03a와 다른 곳은 ⚠️로 표시했다.
> 범위: 서비스 6개(gateway·catalog·booking·user·payment + ⚠️ ai) + Lambda 2개(notification·cognito-presignup) (ADR-51·82).
> ⚠️ 11/5 AI 재도입(ADR-82~87): `ai`는 Python(FastAPI) 사용자 챗봇. 기존 5개 서비스·RDS·기존 Valkey는 건드리지 않는다. 아래 ⚠️AI 표시 행이 새로 생긴 규칙이다.
> 🆕 11/23 MSA 고도화(노션 ADR-114~125, API 명세서 v1.5): 서비스 8개(`coupon`·`ops-api` 추가), 서비스별 RDS 인스턴스, SNS·SQS 이벤트·명령, VIP 등급·관리자 `/ops`. 아래 🆕 표시 행이 새 규칙이다. 기존 API·키·이름은 지우지 않는다(추가만).
> 이 문서가 노션 회의록과 다르면 이 문서를 따르고, 바꿀 때는 PR로 고친다.

---

### 1. 서비스·포트·경로

| 서비스 | 언어 | 컨테이너·Service 포트 | 로컬 호스트 포트 | 클러스터 주소 |
| --- | --- | --- | --- | --- |
| gateway | Java (Spring Cloud Gateway, WebFlux) | 8080 | 8080 | `http://gateway:8080` |
| catalog | Java (Spring MVC) | 8080 | 8081 | `http://catalog:8080` |
| booking | Java | 8080 | 8082 | `http://booking:8080` |
| user | Java | 8080 | 8083 | `http://user:8080` |
| payment | Java | 8080 | 8084 | `http://payment:8080` |
| ⚠️AI ai | **Python 3.12 (FastAPI·uvicorn)** | 8080 | 8085 | `http://ai:8080` |
| 🆕 coupon | Java (Spring MVC) | 8080 | 8086 | `http://coupon:8080` |
| 🆕 ops-api | Python 3.12 (FastAPI, 제안 — 6.0.1에서 확정) | 8080 | 8087 | `http://ops-api:8080` (외부는 CloudFront `/ops/*` → ALB, gateway를 거치지 않음) |
| frontend | React (Vite) | 없음 (S3 + CloudFront) | 5173 (`/api` → 8080 프록시) | — |
| MySQL 8.4 / Valkey 8.2 / ElasticMQ (로컬) | — | 3306 / 6379 / 9324 | 같음 | 🆕 서비스별 RDS 인스턴스 6개(ADR-115) / ElastiCache(TLS, 복제본) / SQS·SNS. 로컬은 MySQL 하나에 스키마 6개(인스턴스 분리는 staging만), ElasticMQ에 큐를 같은 이름으로 |
| ⚠️AI ai Valkey (로컬, `valkey/valkey-bundle` — 벡터 검색 모듈 포함) | — | 6379 | 6380 | ElastiCache `cinema-staging-ai-valkey`(TLS) |

| 경로 | 규칙 |
| --- | --- |
| 외부 API | `https://{배포ID}.cloudfront.net/api/{경로}` → ALB → gateway가 `/api` 제거(StripPrefix=1) |
| 내부 API | `/internal/**` — Gateway 라우팅 금지, NetworkPolicy로 호출자 제한 |
| 헬스 | `/actuator/health/liveness`, `/actuator/health/readiness` (ALB는 gateway readiness만). ⚠️AI ai(Python)도 **같은 경로**로 맞춘다 — 프로브·PodMonitor 템플릿 공용 |
| 메트릭 | `/actuator/prometheus` (ai도 같은 경로, `prometheus_client`) |
| 🆕 관리자 API | `https://{도메인}/ops/**` → CloudFront 동작 `/ops/*`(CachingDisabled) → ALB 규칙(같은 IngressGroup) → `ops-api:8080`. **gateway 라우트가 아니다** — gateway를 정지해도 관리자 화면이 동작해야 해서(ADR-119). 인증은 ops-api가 Cognito Access Token을 직접 검증하고 `cognito:groups`에 `ADMIN`이 있어야 한다. `/api/admin/**`은 계속 없음 |
| Gateway 404 | `/api/internal/**`, `/api/actuator/**`, `/api/payments/**`(payment는 포트원 웹훅 `POST /api/webhooks/portone` 하나만 연다 — 인증 없음, 서명은 payment가 검증, ADR-112), `/api/auth/**`, ⚠️AI `/api/ai/**`(플래그 `APP_AI_ENABLED=false`일 때 — 라우트 자체가 없다), `/api/admin/**`(관리자 역할·API 없음 — 11/14 삭제). 404가 보이는 조건: `/api/internal/**`·`/api/actuator/**`는 경로 차단 필터(`PathGuardWebFilter`)가 인증보다 먼저라 **항상** 404. 나머지는 `/api/**` 인증(`SecurityConfig`)이 라우팅보다 먼저라 **유효한 토큰으로 인증됐을 때만** 404이고, 토큰이 없으면 401 `UNAUTHORIZED`(`TOKEN_REQUIRED`), 만료·서명 불일치 등 잘못된 토큰이면 401 `UNAUTHORIZED`(`INVALID_TOKEN`). ⚠️AI 켜져 있을 때 `/api/ai/**` 라우트는 metadata `response-timeout: 28000`(ms) — 전역 15초의 예외(SSE 라우트와 같은 방식, `docs/service-runtime-contract.md` 9-1 시간 예산) |

### 2. 코드·API 이름

| 대상 | 규칙 | 예 |
| --- | --- | --- |
| URL 경로 | 소문자 복수 명사, 단어는 `-`, ID는 `{camelCase}` | `/screenings/{screeningId}/seats`, `/seat-counts` |
| JSON 필드·쿼리 파라미터 | camelCase | `screeningId`, `hasLayout` |
| 코드 값(enum)·오류 코드·`reason` | UPPER_SNAKE | `PAYMENT_FAILED`, `SEAT_UNAVAILABLE` |
| 이벤트 `type` | 좌석 이벤트는 UPPER (`HELD`), SQS 메시지는 `{도메인}.{과거분사}`. 🆕 명령도 과거분사로 「…-requested」, 결과는 「…ed」(ADR-117) | `booking.confirmed`, `coupon.reservation-requested`, `payment.approved` |
| `operationId` | camelCase 동사 시작 | `holdSeats`, `getBooking` |
| 페이징 응답 | `{ content, page, size, totalElements, totalPages }`, `page` 0부터, `size` 기본 20·최대 50 | — |
| 페이징 없는 목록 | ⚠️ `{ items }` (2.2.4 D8, API 명세서 1-3의 "JSON 배열"을 대체) | `GET /theaters` |
| Java 패키지 | `com.seatsac.{service}`, 공통은 `com.seatsac.common.{core,web,test}` | `com.seatsac.booking.hold` |
| ⚠️AI Python 패키지 | `services/ai/src/seatsac_ai/{api,snapshot,llm,sql,cache,quota}`(🆕 6.4.7 `guide` — 이용 안내 검색) — `libs/`(Java)는 쓰지 않는다. 오류 형식·traceId 규칙은 `seatsac_ai.common`에서 같은 계약(`error.v1.json`)으로 구현 | `seatsac_ai.sql.validator` |
| 계약 파일 | `contracts/openapi/{service}.openapi.yaml`, `openapi/internal/{service}.internal.openapi.yaml`, `events/{이름}.v{N}.json`, `schemas/{이름}.v{N}.json` | 🆕 `openapi/coupon.openapi.yaml`, `openapi/ops.openapi.yaml`, `events/coupon.reservation-requested.v1.json` |
| OpenAPI 확장 | `x-feature-id`, `x-caller` | — |

### 3. 데이터베이스

| 스키마 | 소유 | 앱 계정 (DML) | 마이그레이션 계정 (DDL) |
| --- | --- | --- | --- |
| `catalog_db` | catalog | `catalog_app` | `catalog_migrator` |
| `booking_db` | booking | `booking_app` | `booking_migrator` |
| `user_db` | user | `user_app` | `user_migrator` |
| `payment_db` | payment | `payment_app` | `payment_migrator` |
| 🆕 `coupon_db` | coupon | `coupon_app` | `coupon_migrator` |
| 🆕 `ai_db` | ai | `ai_app`(+ NL2SQL 실행용 읽기 전용 `ai_query`) | `ai_migrator` |

🆕 11/23부터 **스키마마다 RDS 인스턴스가 따로**다(ADR-115): `cinema-staging-{svc}-db`(booking `db.t4g.small`, 나머지 `db.t4g.micro`, ai만 Single-AZ). 인스턴스 안의 스키마·계정 이름은 위 표 그대로이고, 엔드포인트 키만 `DB_HOST` → `DB_HOST_{SVC}`(런타임 계약 3장)로 바뀐다. 서비스는 자기 인스턴스에만 접속한다 — 다른 서비스 데이터가 필요하면 이벤트 복제본(ADR-116)으로.

- 테이블·컬럼 `snake_case`, 테이블 단수형. 다른 스키마 FK 없음(ID 컬럼만)
- 상태 컬럼은 `VARCHAR` + 앱 enum (MySQL ENUM 금지 — 블루/그린 중 값 추가가 어려움)
- 문자셋 `utf8mb4` / `utf8mb4_0900_ai_ci`
- Flyway: `services/{svc}/src/main/resources/db/migration/V{yyyyMMddHHmm}__{설명}.sql`. 확장 → 전환 → 정리 순서로, 컬럼 삭제·이름 변경은 다음 배포에
- 시드(롯데시네마 배치·편성)는 앱 레포 Flyway에 넣지 않는다 → 비공개 `lotte-seed`
- ⚠️AI `ai`는 다른 서비스의 DB에 접근하지 않는다. ~~RDS에 접근하지 않는다~~ → 🆕 11/23 **자기 인스턴스 `ai_db`만** 쓴다(ADR-120 — 레포 ADR-84의 「SQLite 스냅샷만」 대체, `APP_AI_DATA_SOURCE=snapshot`이면 예전처럼 스냅샷). 데이터는 catalog 이벤트 복제본 + 최초 공개 API 백필(`docs/data-model.md` 6장)
- 🆕 서비스가 남의 데이터를 쓸 때는 **이벤트 복제본**(자기 DB 안의 사본, 필요한 컬럼만, `version`이 클 때만 덮어씀)으로 둔다. 테이블 이름은 원본과 같게 두거나 `{원본}_copy`·`{도메인}_history`. 원본은 계속 원본 서비스다

### 4. Valkey 키·채널

| 키 | 소유 | TTL | 값·비고 |
| --- | --- | --- | --- |
| `catalog:movies:*`, `catalog:movie:{id}`, `catalog:theaters:*`, `catalog:screenings:{date}:{theaterId}:{movieId\|all}`, `catalog:layout:{auditoriumId}:{version}` | catalog | 10분 | 변경 시 삭제. 잔여 좌석은 캐시 금지 |
| `seat:{screeningId}:{seatId}` | booking | 선점 TTL (`APP_SEAT_HOLD_TTL_SECONDS`, 기본 300초) | holdId(UUID) |
| `user-hold:{screeningId}:{userId}` | booking | 선점 TTL (기본 300초) | 회차당 진행 중 선점 1건 |
| `seat-seq:{screeningId}` | booking | 없음 | 회차별 이벤트 일련번호. 이벤트마다 +1, **키가 없으면 현재 시각 ms × 1000부터** 시작(`seq-next.lua`) — Valkey 재시작으로 키가 사라져도 번호가 뒤로 가지 않아 열린 화면이 빈틈으로 보고 다시 받는다 |
| `hold-expiry` (sorted set) | booking | 없음 | 멤버 `{screeningId}:{holdId}`, 점수 = 만료 시각 |
| ⚠️ `idem:{userId}:{Idempotency-Key}` | booking | 24시간 | 요청 해시 + 처리 상태 + 첫 응답 (03a `idem:{키}` 대체) |
| 🆕 `coupon:stock:{eventId}` | coupon | 이벤트 종료 + 1일 | 선착순 남은 수량(`DECR`, 음수면 `INCR`로 되돌리고 매진, DB INSERT 실패도 `INCR`). 원본 수량은 `coupon_db.coupon_event.total_quantity` — 키가 없으면 `total_quantity − 발급 수`로 `SET NX`. `DECR` 뒤 프로세스가 죽거나 결과를 모르면 되돌리지 않는다(덜 발급되는 쪽), 대조는 발급이 멈춘 뒤에만 — data-model 7-2 |
| 🆕 `coupon:idem:{userId}:{Idempotency-Key}` | coupon | 24시간 | 발급 요청 멱등(booking `idem:`과 같은 모양 — 같은 키 다른 이벤트 409 `IDEMPOTENCY_KEY_CONFLICT`, 처리 중 409 `IDEMPOTENCY_IN_PROGRESS`, 끝났으면 첫 응답 재생) |

| Pub/Sub 채널 | 발행 | 구독 | 메시지 |
| --- | --- | --- | --- |
| `seat-events:{screeningId}` | booking (RDS 커밋 후) | booking 각 파드 → SSE | `contracts/events/seat-event.v1.json` ⚠️ `screeningId` 포함 |

- ⚠️ 03a의 `refresh:{사용자ID}:{토큰ID}`는 **삭제**(Cognito가 Refresh Token 관리). 요청 수 제한 `rl:`은 만들지 않는다(ADR-62)
- 새 키는 `{서비스}:{대상}:{id}` 형식으로 만들고 이 표에 소유를 추가한다

##### ⚠️AI ai Valkey (`cinema-staging-ai-valkey`, ai 전용 — 위 표의 기존 Valkey와 **다른 클러스터**)

| 키 | TTL | 값·비고 |
| --- | --- | --- |
| `ai:exact:v2:{sha256(정규화 질문)}` | 1시간 | 정확 일치 캐시: SQL 템플릿 + 슬롯 + 경로(프리셋·NL2SQL). **결과는 캐시하지 않는다**(스냅샷에서 다시 실행). `v2`는 캐시 버전 — 저장 SQL의 뜻이 바뀌면 올리고 옛 키는 TTL로 사라진다(#140: 잔여석은 물었을 때만) |
| `ai:semcache:v2:{id}` (HASH, 인덱스 `ai_semcache_v2_idx`) | 1시간 | 의미 캐시: 슬롯을 `{time}`·`{date}`·`{theater}`·`{movie}`·`{n}`으로 바꾼 질문의 임베딩(384차원 FLOAT32, COSINE) + SQL 템플릿 + 슬롯 종류 |
| `ai:learned:v2` (HASH, 필드 = sha256(정규화 질문) 앞 24자) | 줄마다 7일(배운 때부터 — 값의 `learnedAt`을 보고 예열 루프가 지운다). 키 전체 TTL은 마지막으로 적은 때부터 7일 | 스스로 배운 말투 장부(`cache/learned.py`): Claude가 **프리셋을 골라** 답한 새 말투의 정규화 질문 + 캐시 항목(SQL 템플릿·슬롯 종류). 저장 SQL이 지금의 프리셋 SQL과 같을 때만 적고(NL2SQL은 적지 않음), 날짜 빈칸을 `:today`·`:d1~:d3`으로 바꾼 SQL은 질문에 "오늘·내일·모레"가 있을 때만 적고, 값으로 뽑지 못한 날짜 말("주말·어제·다음 주" 등 낱말 목록)이 남은 질문은 적지 않는다. 예열 루프가 캐시 TTL 절반마다 `ai:exact`·`ai:semcache`에 다시 넣는다. 최대 200줄(동시 요청이면 몇 줄 넘을 수 있음), 템플릿 질문 60자 이하만(슬롯 값은 빈칸이지만 나머지 글자는 질문 그대로라 긴 문장은 두지 않는다). 같은 말투에 다른 답이 나오면 그 줄을 지우고, 프리셋이 없어졌거나 SQL·질문 정규화 규칙이 바뀐 줄은 다시 넣을 때 지운다. 수동 초기화는 `APP_AI_CACHE_LEARN_ENABLED=false` 뒤 `DEL ai:learned:v2`(끄지 않으면 다시 배운다) |
| `ai:session:{userId}:{sessionId}` | 30분 | 후속 질문 문맥(직전 질문·SQL·경로) + 최근 답 5개가 조회한 조건 `ctx [{id, date, theater, movie, time, timeDirection}]`(문맥 이어받기 3.4.22 — 답 아래 영화 버튼이 `continueFrom`으로 그 `id`를 보낸다. 값은 날짜·극장명·영화 제목·시각(○시 이후/이전)뿐). 파드가 바뀌어도 유지. **키에 `X-User-Id`를 넣어 사용자별로 격리**한다 — 다른 사용자의 `sessionId`를 보내도 그 사용자 키에는 닿지 않는다. `sessionId`는 서버가 UUID로 발급하고, 요청의 `sessionId`가 이 사용자 키에 없으면(만료·남의 것·위조) 새 세션으로 시작한다(오류 아님) |
| `ai:pending:{userId}:{sessionId}` | **2분**(`APP_AI_SEAT_HOLD_CONFIRM_TTL`) | **기본 켜짐, 끄려면 `APP_AI_SEAT_HOLD_ENABLED=false`(3.4.19)** — 플래그가 켜져 있을 때만 쓴다. 채팅으로 좌석 잡기의 확인을 기다리는 행동(확인 ID·회차 ID·`seatIds`·좌석 이름·ai가 만든 `Idempotency-Key`·사용자 ID, 또는 회차 고르기 목록). `GETDEL`로 한 번만 꺼낸다. 사용자 토큰은 넣지 않는다 |
| `ai:held:{userId}:{sessionId}` | **선점이 남은 동안, 최대 5분**(booking이 준 `expiresAt`까지 — 선점 TTL 300초를 넘지 않는다) | 좌석 잡기 플래그가 켜져 있을 때만 쓴다. 방금 **성공한** 선점의 결과(확인 ID·사용자 ID·회차 ID·`bookingId`·좌석 이름·금액·만료 시각). 응답이 화면에 닿지 못해 같은 확인 ID가 같은 세션으로 다시 오면 선점을 다시 하지 않고, `GET /bookings/{bookingId}`로 그 예매가 아직 PENDING인지 본 뒤 같은 `HOLD_RESULT`를 준다(끝난 예매면 `HOLD_ENDED`·`ALREADY_PAID` 안내)(지우지 않고 읽는다). 사용자 토큰·`seatIds`·`Idempotency-Key`는 넣지 않는다. 실패·결과 모름(`UPSTREAM_UNAVAILABLE`)은 남기지 않는다 |
| `ai:quota:min:{userId}:{yyyyMMddHHmm}` · `ai:quota:day:{userId}:{yyyyMMdd}` | 60초 · 1일 | 사용 한도 카운터(설계 값 분당 10·하루 100, staging은 11/18부터 분당 600·하루 300 — 런타임 계약 9-1). gateway 요청 수 제한(ADR-62)과 별개인 **ai 앱 안** 한도 |
| `ai:inflight:{userId}` | **40초** | 사용자당 동시 1건. `SET NX PX 40000`, 값은 요청마다 새 토큰. 끝나면 **값이 내 토큰일 때만** 지운다(Lua 비교 후 `DEL` — 늦게 끝난 요청이 다음 요청의 잠금을 지우지 않게). TTL 40초가 gateway 라우트 28초·요청 전체 기한 25초(`APP_AI_REQUEST_DEADLINE_SECONDS`)보다 길어 갱신 없이도 처리 중 만료되지 않는다. 파드가 죽어 못 지운 경우만 TTL로 풀린다 |
| `ai:semcheck:0~2` (HASH, 인덱스 `ai_semcheck_idx`) | 120초 | 확인용(런북 6-G-6 의미 캐시 벡터 검색 확인). `HSET`·`EXPIRE`를 `MULTI/EXEC`로 함께 걸고 실행 끝에 키·인덱스를 지운다. 앱은 쓰지 않는다 |

- "내 예매" 같은 **개인 질문은 캐시하지 않는다**(다른 사용자에게 적중 방지). 좌석 잡기 답도 캐시·세션 문맥에 남기지 않는다
- 파라미터 그룹: `maxmemory-policy noeviction`, `reserved-memory-percent 50`(t4g.micro에서 벡터 검색 필수 조건), TLS. `allkeys-lru`로 두면 메모리가 찰 때 한도·동시 1건 키(`ai:quota:*`·`ai:inflight:*`)도 지워져 한도를 우회할 수 있어서 **아무 키도 지우지 않는** `noeviction`으로 한다
- 그래서 **모든 `ai:*` 키에 TTL**을 단다(위 표). 의미 캐시는 앱이 최대 5,000건으로 제한(넘으면 저장하지 않음 — 1건 약 2.5KB, 합 약 13MB로 가용 메모리 약 250MB의 5%)
- 메모리가 차서 쓰기가 실패하면: 캐시 저장 실패는 **무시**(적중 안 됨과 같음), 한도·동시 1건 키 쓰기 실패는 **거부**(503 `AI_LLM_UNAVAILABLE`, `reason` `QUOTA_STORE_UNAVAILABLE`) — 한도 확인 없이 Claude를 부르지 않는다. 경보: 메모리 80%

### 5. SQS·SNS

| 대상 | 이름 |
| --- | --- |
| 알림 큐 | `cinema-staging-notification-events` (그대로) |
| DLQ | `cinema-staging-notification-events-dlq` (maxReceiveCount 3) |
| 메시지 속성 | `type`, `traceId` (String) |
| 본문 | envelope `{eventId, type, version, occurredAt, producer, traceId, payload}` — `contracts/events/booking.confirmed.v1.json` |
| 발송 | ⚠️ booking 아웃박스(`booking_db.outbox_message`) + 릴레이(파드마다 1초). 요청 스레드에서 보내지 않음 |

🆕 11/23 서비스 간 메시징(ADR-117, API 명세서 v1.5 26장)

| 대상 | 이름 | 비고 |
| --- | --- | --- |
| 이벤트 토픽(SNS) | `cinema-staging-booking-events`, `cinema-staging-catalog-events` | 보내는 서비스 하나가 소유 |
| 구독 큐 | `cinema-staging-{소비 svc}-{도메인}-events` — `user-booking-events`, `ai-catalog-events`, (여유 시) `booking-catalog-events` | raw message delivery |
| 명령 큐 | `cinema-staging-{svc}-commands` — `coupon-commands`, `payment-commands` | saga 명령 |
| 결과 큐 | `cinema-staging-booking-replies` | saga 결과(coupon·payment → booking) |
| DLQ | `{큐}-dlq`, maxReceiveCount 3, 메시지 1건 이상이면 경보 | 큐마다 |
| 메시지 속성 | `type`, `traceId`, 🆕 `correlationId`(saga = `checkoutId`) | String |
| 본문 | 기존 envelope + `correlationId` | `contracts/events/{type}.v1.json` |
| 보내기 | 같은 트랜잭션에 `outbox_message` 행 → 릴레이(1초) | booking·catalog·coupon·payment |
| 받기 | 서비스 파드가 SQS long polling(20초), `processed_message(message_id UK)`로 한 번만. Lambda는 외부 작업(알림)만 | 받는 서비스 전부 |

### 6. 오류·헤더·시간·ID·로그

| 대상 | 규칙 |
| --- | --- |
| 오류 응답 | ⚠️ `{ code, message, traceId, details[] }` — `details`는 **항상 배열**(없으면 `[]`), 원소 `{ field, value?, reason? }`. 화면 분기는 `code`로 한다 — 단 **401 `UNAUTHORIZED`는 `details[].reason`으로 나눈다**(재로그인으로 풀리지 않는 user의 `TOKEN_REJECTED`·`IDENTITY_MISMATCH`, 아래 공통 오류 코드 표). `message`는 개발용 (`contracts/schemas/error.v1.json`) |
| 헤더 | `Authorization: Bearer {Cognito Access Token}`, `X-User-Id`(= `sub`), `X-User-Role`(항상 `USER` — 관리자 역할 없음, 11/14. 🆕 관리자는 `/ops`에서 ops-api가 토큰의 `ADMIN` 그룹을 직접 본다), 🆕 `X-User-Grade`(`NORMAL`·`VIP` — 토큰 `cognito:groups`에 `VIP`가 있으면 VIP, ADR-118. 외부 값은 모든 라우트에서 지우고 인증된 요청에만 붙인다. 그룹 변경은 토큰 재발급 뒤 반영), `X-Trace-Id`, `Idempotency-Key`, `CloudFront-Viewer-Address`, `X-Payment-Mock-Key`(모의 결제 허용 키 — k6·분석 Job만, 값은 booking Secret, ADR-112), 포트원 웹훅 `webhook-id`·`webhook-timestamp`·`webhook-signature`. 외부에서 들어온 `X-User-*`·`X-Trace-Id`는 Gateway가 지우거나 덮어쓴다 |
| traceId | ⚠️ 소문자 16진수 **32자리**(UUID 하이픈 제거). Gateway가 요청마다 생성, 응답 헤더에도. 요청 없는 작업(만료 스캔·릴레이)은 새로 생성 |
| 시간 | DB `DATETIME(3)` **UTC**(RDS `time_zone=UTC`, 앱 `Clock.systemUTC()`, 상태 전이 조건에 `NOW()` 대신 앱이 구한 `:now`). API·메시지는 ISO-8601 `+09:00`. `business_date`·`date` 파라미터·화면은 Asia/Seoul |
| ID | PK `BIGINT AUTO_INCREMENT`(Java `Long`, OpenAPI `int64`). ⚠️ 사용자 ID = Cognito `sub`(`CHAR(36)`, `String`). holdId·paymentId·eventId = UUID v4 `CHAR(36)` |
| 금액 | 정수(원). 1석 15,000원, `amount = seatCount × 15000`. 🆕 쿠폰을 쓰면 `final = amount − floor(amount × discountRate / 100)`(결제 준비 전에 booking이 확정, ADR-118) |
| 로그 | stdout JSON 한 줄: `timestamp`(+09:00), `level`, `service`, `traceId`, `userId`(있으면), `message`, `error`(있으면). 카드 번호·토큰·비밀번호 금지. 🆕 오류·보안 로그에는 `errorCategory`(`HTTP_UPSTREAM`·`TIMEOUT`·`AUTH`·`FORBIDDEN`·`SECURITY`·`VALIDATION`·`CONFLICT`·`INTERNAL`, ADR-121) — 일자별 리포트가 이 값으로 나눈다. `SECURITY`는 401·403·차단 경로(`/api/internal`·`actuator`)·위조 헤더·WAF 차단. 메시지 소비 로그는 `messageType`·`correlationId` |

#### 공통 오류 코드

| HTTP | code | 어디서 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED`, `SCREENING_STARTED` | 전체 / booking |
| 401 | `UNAUTHORIZED` | gateway |
| 401 | `UNAUTHORIZED` | user — 프로필을 처음 만들 때 Access Token으로 Cognito `GetUser`를 부른다(ADR-80). 구분은 `code`가 아니라 **`details[].reason`**: `TOKEN_REQUIRED` 토큰 없음(Gateway의 토큰 없음 401과 같은 이름이라 화면은 구분하지 않는다) · `TOKEN_REJECTED` Cognito가 토큰을 거절(`NotAuthorizedException`) · `IDENTITY_MISMATCH` 토큰의 `sub`이 `X-User-Id`와 다름. `TOKEN_REJECTED`는 범위 누락처럼 **설정 문제면 다시 로그인해도 같다**(Gateway 검증 뒤 토큰이 폐기된 드문 경우만 새 토큰으로 풀린다). 화면은 토큰 갱신 1회 뒤에도 `TOKEN_REJECTED`·`IDENTITY_MISMATCH`면 재로그인으로 보내지 않고 안내로 끝낸다(로그인 반복 방지) |
| 401 | `UNAUTHORIZED`(reason `WEBHOOK_SIGNATURE_INVALID`) | payment 웹훅 |
| 403 | `FORBIDDEN` | gateway·서비스 |
| 404 | `NOT_FOUND` | 전체 |
| 409 | `SEAT_UNAVAILABLE`, `ACTIVE_HOLD_EXISTS`, `BOOKING_STATE_CONFLICT`, `PAYMENT_ALREADY_REQUESTED`, `IDEMPOTENCY_KEY_CONFLICT`, `IDEMPOTENCY_IN_PROGRESS` | booking (🆕 멱등 두 코드는 coupon 발급 `POST /coupons/events/{eventId}/claim`도 같은 뜻으로 쓴다 — 4장 `coupon:idem`) |
| 409 | `PAYMENT_CONFLICT`, `PAYMENT_NOT_APPROVED` | payment (내부) |
| 500 | `INTERNAL_ERROR` | 전체 |
| 503 | `SERVICE_UNAVAILABLE` | gateway·서비스 |
| 503 | `PAYMENT_RESULT_UNKNOWN` | booking |
| 503 | `BOOKING_UNAVAILABLE`, ⚠️ `IDENTITY_UNAVAILABLE` | user (`IDENTITY_UNAVAILABLE` = Cognito 호출 실패·타임아웃·장애 — **다시 시도하면 될 수 있다**. 토큰 자체가 거절된 경우는 위 401) |
| 409 | ⚠️AI `AI_QUERY_IN_PROGRESS` | ai — 같은 사용자의 질문이 처리 중(동시 1건) |
| 429 | ⚠️AI `AI_QUOTA_EXCEEDED` | ai — 사용 한도 초과. `details[].reason` `PER_MINUTE`·`PER_DAY`. gateway 요청 수 제한(ADR-62 미도입)과 다른, ai 앱 안의 비용 보호 |
| 503 | ⚠️AI `AI_LLM_UNAVAILABLE` | ai — Claude API 타임아웃·과부하·지출 한도·서킷 열림. `details[].reason` `TIMEOUT`·`OVERLOADED`·`RATE_LIMITED`·`SPEND_LIMIT`·`CIRCUIT_OPEN`·`QUOTA_STORE_UNAVAILABLE`(ai Valkey에 한도를 기록하지 못함) |
| 503 | ⚠️AI `AI_SNAPSHOT_NOT_READY` | ai — 기동 직후 SQLite 스냅샷 첫 적재 전(🆕 `APP_AI_DATA_SOURCE=db`면 첫 백필 전) |
| 409 | 🆕 `CHECKOUT_IN_PROGRESS`, `CHECKOUT_NOT_READY`, `PAYMENT_PATH_CONFLICT` | booking — 체크아웃 saga(ADR-116). 동기 결제와 체크아웃을 한 예매에 섞으면 `PAYMENT_PATH_CONFLICT` |
| 403 | 🆕 `COUPON_NOT_ELIGIBLE` | coupon — VIP 아님 |
| 409 | 🆕 `COUPON_EVENT_NOT_OPEN`(`reason` `UPCOMING`·`CLOSED`), `COUPON_SOLD_OUT`, `COUPON_ALREADY_CLAIMED` | coupon |
| — | 🆕 `COUPON_NOT_USABLE`, `PAYMENT_DECLINED`, `STEP_TIMEOUT`, `HOLD_EXPIRED` | booking 체크아웃 응답의 `failure.code`(HTTP 오류가 아니라 200 응답 안의 값). `STEP_TIMEOUT`은 돈이 움직이기 전 단계만, 결제 결과 불명은 `HOLD_EXPIRED`(`reason` `PAYMENT_RESULT_UNKNOWN`). coupon 거절 `reason`은 `NOT_OWNER`·`NOT_ISSUED`·`EXPIRED`·`ALREADY_RELEASED`. 🆕 `COUPON_NOT_USABLE`은 `state` `COUPON_REJECTED`와 함께 온다 — 예매는 그대로라 화면은 남은 선점 시간 안에 쿠폰을 다시 고르거나 쿠폰 없이 결제하게 한다 |
| 413 | 🆕 `PAYLOAD_TOO_LARGE` | gateway — 요청 본문 64KB 초과(ADR-124) |
| 400 | 🆕 `SERVICE_NOT_STOPPABLE` | ops-api — 정지/가동 대상이 아님 |
| 409 | 🆕 `ROLLOUT_IN_PROGRESS` | ops-api — booking·gateway 블루/그린 진행 중 |
| 503 | 🆕 `REPORT_UNAVAILABLE` | ops-api — Athena·S3 오류 |

새 코드는 이 표와 해당 OpenAPI 응답 `description`에 함께 추가한다. ⚠️AI ai 코드는 `services/ai` 안에서 정의하고 `libs/`의 `CommonResponseCode`는 바꾸지 않는다(Python이라 `libs/`를 쓰지 않음). 형식은 `error.v1.json` 그대로

### 7. 메트릭

- 이름 `{service}_{대상}_{단위}`, 라벨은 `result`·`route` 등 값 종류가 적은 것만(사용자 ID·좌석 ID 금지)
- 필수 메트릭과 라벨 값은 `docs/service-runtime-contract.md` 5장
- ⚠️AI ai(Python)도 HTTP 지표는 `http_server_requests_seconds_*{application="ai",uri,status,method}` 이름으로 낸다(기록 규칙·신호등 공용). AI 지표는 `ai_*`(5장)

### 8. 환경변수·설정·Secret

| 구분 | 규칙 | 예 |
| --- | --- | --- |
| Spring 표준 | 바인딩 이름 그대로 | `SPRING_DATASOURCE_URL`, `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_SSL_ENABLED` |
| 앱 고유 | `APP_` | `APP_SEAT_HOLD_TTL_SECONDS=300`, `APP_SEAT_HOLD_STRATEGY=redis` |
| 다른 서비스 주소·타임아웃 | `APP_SERVICES_{NAME}_URL`, `_CONNECT_TIMEOUT`, `_READ_TIMEOUT` | `APP_SERVICES_PAYMENT_URL=http://payment:8080` |
| 인증 | ⚠️ `APP_COGNITO_ISSUER_URI`, `APP_COGNITO_CLIENT_ID` (gateway 앱 설정 `app.cognito.*`, Spring 표준 `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`는 쓰지 않음. 03a `APP_JWT_SECRET`·`jwt-secret` 삭제) | — |
| AWS | SDK 표준 `AWS_REGION=ap-northeast-2`. 자격 증명은 Pod Identity, 액세스 키 금지 | — |
| frontend | `VITE_` (빌드에 포함 = 공개 값만). 개발 전용(빌드에 안 들어감): `DEV_API_PROXY` — Vite 프록시 `/api` 대상(기본 `http://localhost:8080`, 목 서버는 `npm run dev:mock`) | `VITE_API_BASE_URL=/api`, `VITE_COGNITO_AUTHORITY` |
| K8s Secret | `{service}-secrets`(앱), `{service}-migrator-secrets`(Flyway Job만). ~~Terraform 생성~~ → 🆕 External Secrets가 Secrets Manager `cinema/staging/{service}`에서 동기화(ADR-123) — 이름·키는 그대로 | `booking-secrets` |
| ConfigMap | `{service}-config`(gitops, 엔드포인트는 `${키}`만), ⚠️ `cinema-infra-endpoints`(Terraform, 엔드포인트 — 키는 `docs/service-runtime-contract.md` 3장) | `${DB_HOST}` → 🆕 `${DB_HOST_BOOKING}` |
| 🆕 ops-api(Python) | `OPS_*`(pydantic-settings), 엔드포인트 키는 ai처럼 이름 그대로 읽음 | `OPS_PROMETHEUS_URL` |
| 프로필 | `local`, `staging` | `SPRING_PROFILES_ACTIVE=staging` |
| ⚠️AI ai(Python) | `APP_AI_*`(pydantic-settings). **Python은 `${키}` 치환이 없으므로** `cinema-infra-endpoints`의 키(`AI_VALKEY_HOST`·`PUBLIC_API_BASE_URL`)를 이름 그대로 읽는다. 프로필은 `APP_ENV=local\|staging` | `APP_AI_MODEL=claude-sonnet-5-5` |
| ⚠️AI gateway 플래그 | `APP_AI_ENABLED`(gateway `{svc}-config`, 기본 `false`) — `app.ai.enabled`. 꺼져 있으면 `/api/ai/**` 라우트가 없다 → 404 | — |
| ⚠️AI Claude API 키 | staging: Secret `ai-secrets`의 `ANTHROPIC_API_KEY`(Terraform sensitive 변수 → K8s Secret, tfstate는 암호화 S3). **로컬: 개발 전용 키를 따로 발급해 `services/ai/.env`**(`.gitignore` 대상, Git·문서·PR 금지). 지출 한도는 Anthropic 콘솔에서 월 $90(11/13 $20 → $90, 11/20 발표 뒤 키 폐기) | — |

> `VITE_`에 비밀 값 금지. Secret 값은 Git·문서에 두지 않는다(Terraform 랜덤 생성 → K8s Secret, tfstate는 암호화 S3). ⚠️AI `.env`는 커밋 금지(gitleaks가 `sk-ant-` 형식을 막는다).

### 9. K8s·AWS 리소스 이름

| 대상 | 규칙 | 예 |
| --- | --- | --- |
| 공통 접두사 / 환경 | `cinema` / `staging` (환경은 staging 하나, ADR-34) | — |
| 앱 namespace | `cinema-staging` | — |
| 플랫폼 namespace | 도구 기본값 | `argocd`, `argo-rollouts`, `monitoring`, `kube-system`, 🆕 `external-secrets`, `karpenter` |
| 관리자 namespace | `cinema-ops` (팀원 조회 범위 밖, Terraform이 만드는 운영 Job·그 입력 Secret) | `db-bootstrap-*` Job (3.1.12) |
| AWS 리소스 | `cinema-staging-{대상}` | `cinema-staging-eks`, `cinema-staging-valkey`, `cinema-staging-users`(Cognito), ⚠️AI `cinema-staging-ai-valkey`, `cinema-staging-ng-ai`(노드그룹), 🆕 `cinema-staging-{svc}-db`(RDS 6), `cinema-staging-{도메인}-events`(SNS), `cinema-staging-{svc}-commands`(SQS), `cinema-staging-reports`(Athena 작업 그룹), `cinema-staging-waf` |
| 🆕 Secrets Manager | `cinema/staging/{service}`(앱 → K8s Secret `{service}-secrets`) · `cinema/staging/{service}-migrator`(Flyway Job → `{service}-migrator-secrets`). 값은 키 = 값 JSON, 키 이름은 K8s Secret과 같다 | `cinema/staging/booking`, `cinema/staging/booking-migrator` |
| 🆕 Cognito 그룹 | `VIP`(쿠폰 대상), `ADMIN`(관리자 `/ops`만) | — |
| 계정 단위 리소스 | `cinema-{대상}` | `cinema-admin`, `cinema-developer`, `cinema-gha-ecr-push`, `cinema-tfstate-{계정ID}` |
| S3 버킷 | `cinema-staging-frontend-{계정ID}`, `cinema-tfstate-{계정ID}`, `cinema-staging-loki-{계정ID}`(3.1.24 로그), 🆕 `cinema-staging-reports-{계정ID}`(오류·보안 리포트·Athena 결과) | — |
| ECR | `cinema/{service}` (frontend 없음) | `cinema/booking`, ⚠️AI `cinema/ai`, 🆕 `cinema/coupon`, `cinema/ops-api` |
| 이미지 태그 | git SHA 7자리, 불변 | `cinema/booking:3f9a1c2` |
| Pod Identity 역할 | `cinema-staging-{대상}-pod` | `cinema-staging-booking-pod`, `cinema-staging-loki-pod`(3.1.24), 🆕 `cinema-staging-{coupon,payment,user,catalog,ai,ops-api,external-secrets,karpenter}-pod` |
| Lambda | `cinema-staging-{기능}` | `cinema-staging-notification`, `cinema-staging-cognito-presignup`, 🆕 `cinema-staging-karpenter-sweep`(6.1.6 밤 정리) |
| K8s 리소스 | `{service}`, Rollout 미리보기 Service `{service}-preview`, Flyway Job `{service}-flyway` | `booking-preview` |
| 공통 라벨 | `app.kubernetes.io/name={service}`, `app.kubernetes.io/part-of=cinema` | — |
| Service 포트 이름 | `http` (8080) | — |
| ⚠️AI 노드 격리 | 노드 라벨·taint `cinema/workload=ai`(taint 효과 `NoSchedule`). ai 파드만 `nodeSelector` + `toleration` | `ng-ai` |
| ⚠️AI ArgoCD Application | `cinema-ai`(Terraform `argocd_apps.tf` 목록에 추가, gitops `apps/`에는 두지 않음) | — |
| AWS 태그 | `Project=cinema`, `Env=staging`, `Owner={이름}`, `ManagedBy=terraform` | — |

### 10. Git

`docs/repo-workflow.md` (레포·브랜치·커밋·PR·CI).


<노션 반영 필요> 03a 결정 칸에 "10/22 확정(ADR-49) + ⚠️ 항목 수정"(idem 키, refresh 키·jwt-secret·rl 키 삭제, traceId 32자리, details 배열, `{items}`, seat-event `screeningId`, outbox_message, ai·stats 스키마 삭제), 03a Action Item "docs/conventions.md 커밋" 완료 처리
---

# 2. 서비스 계약 (`docs/contracts.md`)

> 기준 2026-10-21. 원본은 `contracts/` 파일이고 이 문서는 목록과 확정 결정만 담는다. 파일과 이 문서가 다르면 **파일이 맞다**.
> 근거: API 명세서 v1.1(10/21), 2.2.4 Catalog·User API 명세 v0.3(10/21), 계약 대조표(15번), 10/22 회의록2 결정(ADR-54·55).
> ⚠️AI 11/5 AI 재도입(ADR-82) · 11/6 WBS 2.2.8: `openapi/ai.openapi.yaml` 외부 2개 + 응답 예시 5종 **추가**(이 파일이 기준, 구현은 3.4.x). 상세는 API 명세서 v1.4 15장.
> 🆕 11/23 MSA 고도화(노션 ADR-114~125): 체크아웃 saga·coupon·관리자 `/ops`·AI 통계·메시지 16종 **추가**. 상세는 노션 「API 명세서 v1.5 (MSA 고도화)」 21~26장. 계약 파일은 WBS 6.0.1 PR에서 만든다 — 이 문서의 🆕 행은 그 PR의 목록이다(모두 추가만, `contract-break-ok` 불필요).

---

### 1. 파일

> 🆕 11/24 담당 변경: coupon(#178)·user(#186)는 **김종식**. coupon saga 명령의 받는 쪽·결과의 보내는 쪽도 김종식이다 — 아래 표의 `events/coupon.*` 두 행(명령·결과)에 남은 「한채연」은 #181(같은 표의 바로 옆 행을 고침)이 머지된 뒤 고친다. 화면(쿠폰·관리자)은 한채연.

| 파일 | 내용 | 담당 (CODEOWNERS) |
| --- | --- | --- |
| `openapi/catalog.openapi.yaml` | 외부 6개 (조회만 — 관리자 API는 11/14 삭제) | 한채연 |
| `openapi/booking.openapi.yaml` | 외부 8개 (좌석 현황·SSE·선점·해제·결제 준비·결제·조회·취소) + 🆕 3개 (체크아웃 시작·조회·확인) | 남궁일 |
| `openapi/payment.openapi.yaml` | 외부 1개 (포트원 웹훅 — 인증 없음, 서명 검증) | 남궁일 |
| `openapi/user.openapi.yaml` | 외부 5개 (프로필 조회·수정, 예매 내역, 선호 조회·저장). 🆕 `GET /users/me`에 `grade` | ~~한채연~~ 🆕 김종식(11/24) |
| 🆕 `openapi/coupon.openapi.yaml` | 외부 3개 (현재 이벤트·발급·내 쿠폰) | ~~한채연~~ 🆕 김종식(11/24) |
| 🆕 `openapi/ops.openapi.yaml` | 외부 8개 (`/ops` — 서비스 상태·정지·가동, 리포트 날짜·생성·상태, AI 범위 밖 통계, 쿠폰 이벤트 생성) | 김종식 |
| ⚠️AI `openapi/ai.openapi.yaml` | 외부 2개 (챗봇 질문 `POST /ai/chat`, 추천 질문 목록 `GET /ai/presets`). 🆕 6.4.7: 응답 `path`에 `RAG`, 필드 `sources`(근거 문서 `[{id, title}]`, 그 밖의 경로는 null) **추가만** — `CLARIFY`·`HOLD_*`를 넣을 때와 같은 방식, 6.0.1 계약 PR에 함께(⏳ `contract-check`로 확인) | 김서현 · 한채연 |
| ⚠️AI `openapi/examples/ai/*.json` | 챗봇 응답 예시 5종(CACHE_SEMANTIC·PRESET·NL2SQL·MY_BOOKINGS·REJECTED) + CLARIFY(#140) + 좌석 잡기 3종(HOLD_CONFIRM·HOLD_RESULT·HOLD_GUIDE) + 문맥 이어받기 `chat-context-carry.json`(3.4.22) + 🆕 이용 안내 `chat-rag.json`(6.4.7). OpenAPI Example Object `{ summary, value }` — `value`가 fixture, YAML이 `$ref`로 참조해 Redocly가 스키마로 검증 | 김서현 |
| `openapi/internal/catalog.internal.openapi.yaml` | 내부 3개 | 한채연 |
| `openapi/internal/booking.internal.openapi.yaml` | 내부 2개 | 남궁일 |
| `openapi/internal/payment.internal.openapi.yaml` | 내부 4개 (생성·조회·확인·취소) | 남궁일 |
| 🆕 `openapi/internal/coupon.internal.openapi.yaml` | 내부 2개 (이벤트 생성·목록, 호출자 ops-api) | ~~한채연~~ 🆕 김종식(11/24) |
| 🆕 `openapi/internal/ai.internal.openapi.yaml` | 내부 1개 (범위 밖 질문 통계, 호출자 ops-api) | 김서현 |
| `events/seat-event.v1.json` | Valkey Pub/Sub → SSE | 남궁일 |
| `events/booking.confirmed.v1.json` | SQS 예매 완료 알림. 🆕 SNS `booking-events`로도 발행(user 복제본), 선택 필드 `amount`·`discountAmount` 추가 | 남궁일 (소비 Lambda 김종식, user ~~한채연~~ 🆕 김종식) |
| 🆕 `events/booking.canceled.v1.json` | 예매 취소·만료·결제 실패(`reason`) | 남궁일 |
| 🆕 `events/screening.changed.v1.json` · `movie.changed.v1.json` | catalog 변경(`version`, `deleted`) → ai·(booking) 복제본 | 한채연 |
| 🆕 `events/coupon.{reservation-requested,use-requested,release-requested}.v1.json` | saga 명령 → coupon | 남궁일(보냄) · 한채연(받음) |
| 🆕 `events/payment.{preparation-requested,charge-requested,verification-requested}.v1.json` | saga 명령 → payment | 남궁일 |
| 🆕 `events/coupon.{reserved,reservation-rejected}.v1.json` · `payment.{prepared,preparation-failed,approved,declined,result-unknown}.v1.json` | saga 결과 → booking | 한채연·남궁일 |
| `schemas/error.v1.json` | 공통 오류 응답 | 전원 |
| `*/examples/**` | 예시 메시지 = 서비스 테스트 fixture | 각 담당 |

합계: 외부 22(⚠️AI ai 2개·포트원 웹훅 1개 포함) · 내부 9 · 이벤트 2. 🆕 11/23 이후 외부 **36**(booking 3·coupon 3·ops 8 추가) · 내부 **12**(coupon 2·ai 1) · 메시지 **18**(이벤트 4 + 명령 6 + 결과 7 + 좌석 이벤트 1).

### 2. API 목록

#### 외부 (`/api` 접두사, Gateway StripPrefix)

| 인증 | 메서드·경로 | 기능 ID | 비고 |
| --- | --- | --- | --- |
| 없음 | `GET /movies` | CAT-01 | 페이징 |
| 없음 | `GET /movies/{movieId}` | CAT-02 | TMDB 메타 |
| 없음 | `GET /theaters` | CAT-03 | `{items}` |
| 없음 | `GET /screenings?date&theaterId&movieId` | CAT-03 | `{items}`, `remainingSeatsSource` |
| 없음 | `GET /screenings/{screeningId}` | CAT-03 | |
| 없음 | `GET /auditoriums/{auditoriumId}/layout?version` | CAT-04 | 좌석 상태 없음 |
| 없음 | `GET /screenings/{screeningId}/seats` | BKG-01 | **booking**, Gateway 1순위 라우트 |
| 없음 | `GET /screenings/{screeningId}/seats/stream` | BKG-05 | SSE, **booking** |
| USER | `POST /seats/hold` | BKG-02 | `Idempotency-Key` 필수 |
| USER | `GET /bookings/{bookingId}` | — | 결제 결과 재확인 |
| USER | `DELETE /bookings/{bookingId}/hold` | BKG-03 | |
| USER | `POST /bookings/{bookingId}/payments` | BKG-10 | `Idempotency-Key` 필수. TOSSPAY 결제 준비(paymentId + 포트원 결제창 값), ADR-112 |
| USER | `POST /bookings/{bookingId}/pay` | BKG-10 | `Idempotency-Key` 필수, 거절도 200. `method` TOSSPAY(완료 확인)·CARD(모의, `X-Payment-Mock-Key`가 맞을 때만 — k6·분석 Job) |
| 없음(서명) | `POST /webhooks/portone` | PAY-05 | 포트원 웹훅. Standard Webhooks 서명, 본문 불신 → 단건 조회 재검증 |
| USER | `POST /bookings/{bookingId}/cancel` | BKG-07 | |
| 🆕 USER | `POST /bookings/{bookingId}/checkout` | BKG-10 | **체크아웃 saga**(ADR-116). `Idempotency-Key` 필수, `{method: TOSSPAY\|CARD, couponIssueId?}` → 202 `{checkoutId, state, pollAfterMs}`. 플래그 `APP_CHECKOUT_SAGA_ENABLED`(기본 `false` → 404). 한 예매에 동기 결제와 섞으면 409 `PAYMENT_PATH_CONFLICT` |
| 🆕 USER | `GET /bookings/{bookingId}/checkout` | BKG-10 | polling(1초, 최대 30초). `state` STARTED → COUPON_RESERVING → PAYMENT_PREPARING → READY_TO_PAY(포트원 결제창 값) → VERIFYING → CONFIRMED / COMPENSATING → FAILED / EXPIRED, 🆕 / COUPON_REJECTED(쿠폰만 거절 — 예매·좌석은 그대로, 최초 선점 5분 안에 다른 쿠폰이나 쿠폰 없이 `POST …/checkout`을 새 `Idempotency-Key`로 다시). CARD는 결제 준비 없이 CHARGING. `amount {original, discount, final}`, `failure {code, reason}`. 결제 결과 불명(`CHARGING`·`VERIFYING`)은 보상하지 않고 재확인, ~~선점 만료까지 모르면 EXPIRED~~ 🆕 선점 만료 뒤에는 예매 상태를 따라 닫힌다(기존 만료 처리가 유예 2분 뒤 payment 결과로 정함 — 결과 불명이면 대략 7~9분). 진행 중이면 `DELETE /bookings/{id}/hold`도 409 `CHECKOUT_IN_PROGRESS`, `GET /bookings/{id}`에 `checkout {checkoutId, state}` 추가 |
| 🆕 USER | `POST /bookings/{bookingId}/checkout/confirm` | BKG-10 | 결제창이 닫힌 뒤 `{paymentId}` → 202. `READY_TO_PAY`가 아니면 409 `CHECKOUT_NOT_READY` |
| 🆕 USER | `GET /coupons/events/current` | CPN-01 | **coupon**, `{eventId, title, discountRate, startsAt, endsAt, totalQuantity, remainingQuantity, eligibleGrade, state, myIssue}` — NORMAL 사용자도 200(화면이 「VIP 전용」 안내) |
| 🆕 USER | `POST /coupons/events/{eventId}/claim` | CPN-02 | VIP만(`X-User-Grade`), 선착순, `Idempotency-Key` 필수 → 201. 403 `COUPON_NOT_ELIGIBLE`, 409 `COUPON_SOLD_OUT`·`COUPON_ALREADY_CLAIMED`·`COUPON_EVENT_NOT_OPEN` |
| 🆕 USER | `GET /coupons/me` | CPN-03 | `{items}` |
| 🆕 ADMIN | `/ops/**` 8개 | OPS-20~24 | **ops-api**, gateway 라우트 아님(CloudFront `/ops/*` → ALB). ops-api가 토큰의 `cognito:groups` ∋ `ADMIN` 확인. 목록은 API 명세서 v1.5 25장 |
| USER | `GET /users/me`, `PATCH /users/me` | — | 첫 호출 때 프로필 생성. 🆕 응답에 `grade`(`X-User-Grade`, 저장 안 함) |
| USER | `GET /users/me/bookings` | USR-04 | 페이징. 🆕 `APP_BOOKING_HISTORY_MODE=local`이면 `booking_history` 복제본(응답 모양 그대로) |
| USER | `GET /users/me/preferences`, `PUT /users/me/preferences` | USR-05 | |
| ⚠️AI USER | `POST /ai/chat` | AI-01 | **ai**, 플래그 `APP_AI_ENABLED` 꺼지면 404(gateway, 유효한 토큰으로 인증됐을 때 — 토큰이 없거나 잘못되면 인증이 라우팅보다 먼저라 401). `{message 1~300자, sessionId?, continueFrom?}` → `{sessionId, answer, path, columns, rows, screeningIds, rowCount, truncated, sql, presetId, context, asOf, llm, latencyMs, rejection, choices, cache, seatHold}` — 모든 필드가 항상 있고 해당 없으면 null. `screeningIds`는 `rows`와 같은 순서의 회차 ID(행 하나가 회차 하나일 때만, 집계 결과는 null) — 화면이 좌석 선택 화면 링크를 만든다(ai는 화면 경로를 만들지 않음). `cache {entryId, stored, similarity}`는 이 답과 이어진 캐시 항목(`stored: true` = Claude가 답하고 저장함, `false` = 그 항목에서 나온 답, `similarity`는 `CACHE_SEMANTIC`일 때만 0~1) — 데모 화면이 같은 대화에서 `entryId`가 같은 두 답을 짝지어 실제로 아낀 토큰·시간을 보여 준다(`entryId`는 ai 파드가 다시 뜨면 바뀐다). 저장하지 않은 답은 null이고 질문 글자는 싣지 않는다. `path` `CACHE_EXACT`·`CACHE_SEMANTIC`·`PRESET`·`NL2SQL`·`MY_BOOKINGS`·`REJECTED`·`OUT_OF_SCOPE`·`CLARIFY`(+ 좌석 잡기 플래그 `APP_AI_SEAT_HOLD_ENABLED`(기본 켜짐)가 켜져 있을 때만 `HOLD_CONFIRM`·`HOLD_RESULT`·`HOLD_GUIDE`, `seatHold {screeningId, bookingId, seatLabels, amount, expiresAt, confirmExpiresAt, reason}` — 그 밖은 null). `sessionId`는 서버가 발급하며, 이 사용자의 세션이 아니면 새 세션으로 시작한다. **문맥 이어받기(3.4.22)**: `context {id, date, theater, movie, time, timeDirection, label, inherited}`는 그 답이 실제로 조회한 조건(실행한 SQL에서 하나로 확정된 값만, 없으면 null. `time` 'HH:MM'·`timeDirection` `after`·`before`는 「○시 이후/이전」 하나일 때만 — 시각 범위 두 개·`start_hour` 표현·심야/조조 같은 고정 범위가 있는 답은 `context`가 null). 화면은 답 아래 영화 버튼을 누를 때 그 답의 `context.id`를 `continueFrom`으로 보내고, ai는 그 날짜·극장·시각에 고른 영화를 더해 Claude 없이 실행한다(`inherited`에 이어받은 칸 — 화면은 「`label` 기준」·「조건 지우기」 표시). 모르는·만료된 `continueFrom`은 오류 없이 무시(`inherited` 빈 배열), `continueFrom` 없는 직접 친 질문은 이어받지 않는다. SQL 거부·범위 밖·되묻기도 **200**(`REJECTED`는 `rejection {reason, message, object?}`, `CLARIFY`는 `choices [{label, message}]` 버튼 0~7개 — 누르면 `message`를 같은 `sessionId`로 보낸다). 409 `AI_QUERY_IN_PROGRESS` · 429 `AI_QUOTA_EXCEEDED`(`Retry-After`, `details[{field: quota, value: 10/min·100/day, reason: PER_MINUTE·PER_DAY}]`) · 503 `AI_LLM_UNAVAILABLE`(`details[{field: provider, value: anthropic, reason}]`)·`AI_SNAPSHOT_NOT_READY`·`SERVICE_UNAVAILABLE`(내 예매 공개 API 실패 `upstream: user`, 또는 ai 파드 없음). `Idempotency-Key` 없음(조회 전용. 좌석 잡기에서도 화면은 보내지 않는다 — ai가 확인 요청 때 만든 키로 `POST /seats/hold`를 부른다) |
| ⚠️AI USER | `GET /ai/presets` | AI-04 | **ai**, 추천 질문 목록 `{items:[{presetId, title, example}]}` — 버튼을 누르면 화면이 빈칸(시각·개수는 입력창에서 고치기, 극장·영화·날짜·등급은 고르는 버튼)을 사용자가 정하게 한 뒤 질문 문장을 `POST /ai/chat`의 `message`로 보낸다. 빈칸이 없는 질문과 화면이 모르는 프리셋은 `example`을 그대로. 화면은 **챗봇 창을 열 때** 이 API를 불러(로그인·메인 진입 때 자동 호출 없음 — 런북 6-G-5) 404면 「준비 중」 안내 뒤 챗봇 버튼 숨김, 503이면 비활성 안내 |

#### 내부 (`http://{service}:8080/internal/**`)

| 호출 | API | 타임아웃 | 실패 시 |
| --- | --- | --- | --- |
| booking → catalog | `GET /internal/screenings/{id}` | 1초 / 3초 | 선점·취소 503 |
| user → catalog | `POST /internal/screenings/summaries` (최대 100) | 1초 / 3초 | `screening: null` |
| user → catalog | `GET /internal/theaters?ids=` (최대 10) | 1초 / 3초 | 극장 검증 생략 |
| user → booking | `GET /internal/bookings?userId&status&page&size` | 1초 / 3초 | 503 `BOOKING_UNAVAILABLE` |
| catalog → booking | `GET /internal/screenings/seat-counts?ids=` (최대 100) | **300ms** 전체 | `remainingSeatsSource: TOTAL` |
| booking → payment | `POST /internal/payments` | 1초 / 3초, 타임아웃·연결 오류만 같은 paymentId로 1회 재시도 → 조회 | 503 `PAYMENT_RESULT_UNKNOWN` |
| booking → payment | `GET /internal/payments/{id}` | 1초 / 3초 | 결과 미확인 |
| booking → payment | `POST /internal/payments/{id}/verify` | **1초 / 10초**(`APP_SERVICES_PAYMENT_PG_READ_TIMEOUT` — 안에서 포트원을 부른다), 재호출 없음 | 503 `PAYMENT_RESULT_UNKNOWN` (TOSSPAY 완료 확인 — 화면의 `GET /bookings/{id}` 재조회가 verify를 다시 부른다) |
| booking → payment | `POST /internal/payments/{id}/cancel` `{reason}` | **1초 / 10초**(같은 설정 — PORTONE은 포트원 취소 + 대조) | 503 (화면 재시도) |
| 🆕 ops-api → coupon | `POST /internal/coupon-events`, `GET /internal/coupon-events` | 1초 / 3초 | 관리자 화면 오류 표시 |
| 🆕 ops-api → ai | `GET /internal/ai/unmet-demand/stats?from&to&limit` | 1초 / 3초 | 관리자 화면 「통계 없음」 |
| payment → 포트원 | `GET /payments/{id}` · `POST /payments/{id}/cancel` (`api.portone.io`) | 1초 / 3초, GET만 1회 재호출(최대 약 8초 < booking 10초 < gateway 15초), 서킷 `portone` | verify·cancel 503 `SERVICE_UNAVAILABLE`(PORTONE_UNAVAILABLE) |

공통: 재시도 없음(위 1건 제외), Resilience4j 서킷(실패율 50%·10초), 호출 깊이 1(예외: booking → payment → 포트원, ADR-112 — 위 시간 예산), `X-Trace-Id`·`X-User-Id` 전달.

🆕 saga 명령·결과는 HTTP가 아니라 SQS다(위 표에 없음, 26장). 동기 내부 호출은 지우지 않는다 — 비동기 경로가 꺼져 있거나(플래그) 복제본에 없을 때의 대체 경로로 남긴다(ADR-116).

⚠️AI ai는 내부 API를 부르지 않는다. 공개 API(`GET /movies`·`/movies/{id}`·`/theaters`·`/screenings`, 사용자 토큰으로 `GET /users/me/bookings`)를 CloudFront 주소로 호출한다(ADR-84). 기존 계약 변경 없음.

⚠️AI 채팅으로 좌석 잡기(ai 플래그 `APP_AI_SEAT_HOLD_ENABLED` 기본 `true` — `false`로 끈다, 3.4.19 — ADR 번호는 노션 등록 후 확정): 켜져 있을 때 ai가 공개 API를 4개 더 부른다 — `GET /screenings/{screeningId}/seats` · `GET /auditoriums/{auditoriumId}/layout`(인증 없음, 좌석 이름 → `seatId`), 그리고 **사용자가 확인 버튼을 누른 뒤에만** `POST /seats/hold`(요청의 `Authorization` 그대로 + ai가 만든 `Idempotency-Key`, 시간 초과·연결 오류 시 같은 키로 1회 재시도). ai가 쓰기 API를 부르는 유일한 경우다. 네 번째는 선점에 성공한 그 확인이 다시 왔을 때만 부르는 `GET /bookings/{bookingId}`(요청의 `Authorization` 그대로, 읽기 — 단 booking이 응답 전에 결제 결과를 반영할 수 있다, `booking.openapi.yaml`). booking·catalog 계약은 바뀌지 않는다.

### 3. 확정 결정 (10/22)

| # | 항목 | 결정 | 근거 |
| --- | --- | --- | --- |
| 1 | OpenAPI 버전 | **3.1.0** (error·이벤트 스키마를 JSON Schema 그대로 `$ref`) | 2.2.7 |
| 2 | userId | Cognito `sub` 문자열(`format: uuid`), DB `CHAR(36)` | ADR-37, ERD 6차 |
| 3 | 프로필 email·name | user가 Access Token으로 Cognito `GetUser`. `NotAuthorizedException`은 **401 `UNAUTHORIZED`**(`details[].reason` `TOKEN_REJECTED`), 그 밖의 실패는 **503 `IDENTITY_UNAVAILABLE`** | 2.2.4 v0.3, ADR-54 |
| 4 | `GET /internal/theaters` | **만든다** (USR-05 극장 검증, Catalog 실패 시 검증 생략) | API 명세서 10장 #6 |
| 5 | 범위 | 위 29개 + ⚠️AI ai 외부 2개 = 31개(관리자 API 3개는 11/14 삭제 — 구현된 적 없음 · ADR-112: 결제 준비·포트원 웹훅 외부 2개, verify 내부 1개 추가). gateway 요청 수 제한(429)은 없음 — ai의 429 `AI_QUOTA_EXCEEDED`는 ai 앱 안 비용 보호 | ADR-62·82 |
| 6 | booking.confirmed v1 payload | `screeningId`·`seatCount`·`confirmedAt` **필수** (발행자 booking 하나, 구현 전) | API 명세서 9-2, ADR-55 |
| 7 | traceId 형식 | 스키마에 `pattern: ^[0-9a-f]{32}$` (오류·좌석 이벤트·알림 공통), 오류의 traceId는 null 불가 | GW-03 10/21, ADR-55 |
| 8 | 오류 `details` | **항상 배열**(필수, 없으면 `[]`), 원소 `{field, value?, reason?}` | 2.2.4 v0.3, API 명세서 1-4 |
| 9 | 페이징 없는 목록 | `{items}` (`/theaters`, `/screenings`, 내부 summaries·theaters·seat-counts) | 2.2.4 D8, ADR-54 |
| 10 | 내부 회차 API의 좌석 ID | **삭제**. booking은 CAT-04 배치도를 (상영관, 버전) 키로 파드 캐시 | 2.2.4 v0.3 |
| 11 | 결제 | paymentId = booking 발급 UUID(포트원 paymentId로도 그대로), `PROCESSING` 상태, 거절도 200. **TOSSPAY**(포트원, 화면) = 준비 → 결제창 → 완료 확인(서버가 단건 조회로 금액 대조) · **CARD**(모의 0000·1111·9999) = 테스트·분석 전용, 호출자 제한 | API 명세서 10장 #3~5, ADR-13 → **ADR-112** |
| 12 | 예매 완료 알림 | 아웃박스 + 릴레이, `eventId` = 아웃박스 UUID(재전송 같은 값) | 10/21 확정 |
| 13 | SSE 적용 규칙 | `seq == lastSeq + 1`일 때만 적용, 빈틈이면 200ms 뒤 BKG-01 재조회 | API 명세서 v1.1 |
| 14 | 하위 호환 | 필드 추가만. 깨지는 변경은 새 엔드포인트·`.v2` 파일. 구현 전 설계 변경만 `contract-break-ok` 라벨 | ADR-32·55, 2.2.7 |
| 15 | 🆕 비동기 결제 | 기존 `/payments`·`/pay`는 그대로 두고 **새 자원 `checkout`**(202 + polling)으로 추가. 상태·결과는 `GET /bookings/{id}/checkout` 한 곳 | 노션 ADR-116 (11/23) |
| 16 | 🆕 메시지 envelope | 기존 envelope + `correlationId`(saga = `checkoutId`). 소비자는 모르는 필드를 무시, `processed_message`로 한 번만 | ADR-117 |
| 17 | 🆕 VIP·관리자 | VIP = Cognito 그룹 → gateway `X-User-Grade`, 관리자 = Cognito `ADMIN` 그룹 → ops-api 직접 검증(`/api/admin/**`은 계속 없음) | ADR-118·119 |
| 18 | 🆕 금액 | 쿠폰 할인은 booking이 결제 준비 전에 확정(`amount {original, discount, final}`), 포트원 결제창은 `final` | ADR-118 |

### 4. 바꾸는 방법

1. `contracts/` 파일을 먼저 고치는 PR (구현보다 먼저)
2. 로컬에서 `contracts/tools/check.sh` → CI `contract-check`(lint · 스키마·예시 · 하위 호환)
3. CODEOWNERS 승인(영향받는 담당 전원). 깨지는 변경이면 PR 본문 "계약 변경"에 이유
4. 구현 후(2단계): `pr-check`가 springdoc `/v3/api-docs` 생성본과 계약을 `oasdiff breaking`으로 비교 → Spring은 `springdoc.api-docs.version: openapi_3_1`

### 5. 이번 확정으로 생기는 하위 호환 ERROR (첫 PR에서 라벨 필요)

2.2.7 골격 대비 의도된 변경이다. 아직 구현 전이라 `contract-break-ok` + CODEOWNERS(한채연·남궁일) 승인으로 한 번에 넣는다.

| 파일 | 변경 |
| --- | --- |
| `payment.internal.openapi.yaml` | 결제·취소 요청 본문 필수 추가, `paymentId` 경로 파라미터 `format: uuid`(형식 오류는 400 `VALIDATION_FAILED`) |
| `error.v1.json` (→ 모든 OpenAPI 오류 응답) | `details` 필수, `traceId` null 불가 + pattern |
| `booking.confirmed.v1.json` | payload 필수 3개, `userId` uuid, `traceId` pattern |
| `seat-event.v1.json` | `traceId` pattern |

<노션 반영 필요> API 명세서 v1.1의 1-3 "페이징 없는 목록은 JSON 배열" → `{items}`, 1-4 "details는 선택(배열 또는 객체)" → 항상 배열, 3-2 내부 회차 API `seatIds[]` 삭제, 5-1 프로필 생성 실패 → 503 `IDENTITY_UNAVAILABLE`, 10장 #1·#2·#6·#10 결정 표시, 부록 B에 OpenAPI 3.1·contract-check

---

# 3. 서비스 실행 계약 (앱 ↔ 인프라) (`docs/service-runtime-contract.md`)

> 기준 2026-10-21. 개발(서비스 코드)과 인프라/데브옵스(Terraform·gitops·CI)가 **맞닿는 모든 약속**을 한곳에 둔다. 이름 규칙은 `docs/conventions.md`, API는 `contracts/`.
> 이 표대로 만들면 서로 회의 없이 붙는다. 표를 바꿀 때는 PR에 양쪽(서비스 담당 + 김종식) 승인을 받는다.
> 범위: 노드 t3.xlarge 2대(ng-a 1 · ng-c 1, ADR-7·34 — 11/14 t3.large에서 변경) + ⚠️AI ai 전용 `ng-ai` t3.large 1대(평소 0대, ADR-86 — 변수 `ai_node_instance_type`으로 따로), 환경 staging 하나. 값은 10/22 확정(ADR-57·58·59·63), ⚠️AI 11/5 AI 재도입(ADR-82~87)은 각 장의 「ai」 항목과 9장.
> 🆕 11/23 MSA 고도화(노션 ADR-114~125): 서비스별 RDS 인스턴스 6개 · NAT AZ별 · Multi-AZ · 핵심 서비스 replicas 최소 2 · Karpenter · External Secrets · SNS/SQS · `coupon`(Java)·`ops-api`(Python) 추가는 각 장의 🆕 항목과 **10장**. 적용 순서는 노션 「MSA 고도화 (WBS)」 「순서」 열.

---

### 1. 모든 서비스 공통 (Java 5개 + 🆕 coupon — ⚠️AI Python `ai`는 9장, 🆕 Python `ops-api`는 10장)

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

### 2. 서비스별 배포 방식·자원

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
- 🆕 노드(6.1.6, ADR-122): 관리형 ng-a·ng-c(t3.xlarge 각 1대)는 그대로이고, 모자라면 **Karpenter**가 t3.large·xlarge On-Demand 노드를 더한다(합계 `limits.cpu` 16, 라벨 `cinema/capacity=karpenter`, taint 없음 — ai는 계속 `ng-ai`만). 파드를 옮기는 정리(압축·만료·Drifted)는 하지 않고 빈 노드만 5분 뒤 지운다 → 블루/그린 preview·SSE가 Karpenter 때문에 쫓겨나지 않는다(런북 6-K)
- JVM: `-XX:MaxRAMPercentage=75` (Dockerfile). limits cpu는 두지 않는다(스로틀로 p95 악화)
- ⚠️AI ai는 `ng-ai` 노드에만 뜬다(`nodeSelector cinema/workload=ai` + toleration). 기존 5개 서비스는 taint 때문에 `ng-ai`에 올라가지 않는다. `ng-ai` desired 0이면 ai 파드는 Pending(정상) — 나머지 서비스·데모에 영향 없음
- 두 서비스 동시 블루/그린 금지(노드 2대). 공통 모듈 급한 반영도 한 번에 한 서비스씩: catalog·user·payment → booking → gateway
- 프로브: `startupProbe` readiness 경로 5초 × 30회, `readinessProbe` 5초 / 실패 3, `livenessProbe` 10초 / 실패 3
- 블루/그린: active Service `{svc}`, preview Service `{svc}-preview`, `prePromotionAnalysis` 통과 후 전환, `scaleDownDelaySeconds: 60`. gateway는 ALB 대상 그룹 확인(ADR-35)
- 블루/그린 파드 수: 분석 중 preview는 **1개**(`previewReplicaCount: 1`, active는 HPA 그대로). 전환 직전 preview를 active 수까지 늘리므로 **전환 순간~60초만 2배**(booking HPA 최대 2 → 4개, requests 1 vCPU) → 노드 2대에서 preview가 Pending이면 전환이 멈추고 10분 뒤 abort. booking `maxReplicas`는 **2**(11/12 결정 — 4면 전환 순간 8개 × 풀 15로 RDS 연결이 경보를 넘는다, 3장). 4.2.1(11/11)은 최대 4에서 쟀으므로 이 변경을 머지한 직후(rc2 전) 최대 2로 contention·deploy를 한 번 다시 재서 선점 p95·CPU를 확인한다. 선점 p95가 300ms를 넘으면 원인을 가른다 — DB 연결 대기(`hikaricp_connections_acquire_seconds_max` 큼, CPU 여유)면 파드를 늘려도 안 풀리므로 근본 수정 A(진 요청 사유 판정을 DB 없이), CPU 부족이면 「풀 10 · 최소 유휴 10 · 최대 4」(전환 순간 8 × 10 + 35 = 115)를 수정 A와 함께. **최대 4로만 되돌리지 않는다**(전환 순간 155). 1차 데모(11/16) 전에 끝낸다(노션 ADR-96)
- booking 만료 스캔·아웃박스 릴레이는 **preview 파드에서도 돈다** → 만료·릴레이 로직 변경은 이전 버전과 호환되게(조건부 UPDATE로 한 번만 처리)

### 3. 설정·Secret (서비스가 읽는 이름)

값은 예시. **SeatSAC-gitops가 Public이라(ADR-74) 엔드포인트·계정 ID는 gitops에 쓰지 않는다**: `{svc}-config`(gitops)에는 `${DB_HOST}` 같은 자리만 두고, 실제 값은 Terraform ConfigMap `cinema-infra-endpoints`(`envs/staging-k8s/infra_endpoints.tf`)에서, 비밀번호는 Secret에서 온다.

##### Terraform ConfigMap `cinema-infra-endpoints` (`cinema-staging`, 모든 서비스·Flyway Job이 받는다)

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

#### 공통 (ConfigMap `{svc}-config`)

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

#### 서비스별

| 서비스 | ConfigMap 키 | Secret `{svc}-secrets` 키 |
| --- | --- | --- |
| gateway | ⚠️AI `APP_AI_ENABLED`(기본 `false` — 꺼져 있으면 `/api/ai/**` 라우트 없음 → 유효한 토큰이면 404, 토큰이 없거나 잘못되면 인증이 먼저라 401, rc2 M6 테스트 후 `true`), `APP_SERVICES_AI_URL=http://ai:8080` — ai 라우트는 **metadata `response-timeout: 28000`**(ms, 전역 `httpclient.response-timeout: 15s`의 예외, SSE 라우트의 `-1`과 같은 방식. 없으면 15초에 `UPSTREAM_TIMEOUT`). 하위 연결 풀은 `httpclient.pool.max-idle-time: 30s`(앱 `application.yml`) — 하위 Tomcat keep-alive 60초보다 짧게 둬 이미 닫힌 연결을 꺼내 쓰는 503(PrematureClose)을 막는다. ai(uvicorn keep-alive 기본 5초)는 이 값으로 덮지 못해 ai 쪽 keep-alive를 늘려 맞춘다. gateway는 `APP_SERVICES_*_READ_TIMEOUT`을 읽지 않으므로 `APP_SERVICES_AI_READ_TIMEOUT`은 만들지 않는다, `APP_COGNITO_ISSUER_URI=${COGNITO_ISSUER_URI}`, `APP_COGNITO_CLIENT_ID=${COGNITO_CLIENT_ID}`, `APP_SERVICES_{CATALOG,BOOKING,USER}_URL`, `APP_SERVICES_PAYMENT_URL=http://payment:8080`(포트원 웹훅 라우트 `POST /api/webhooks/portone`만, ADR-112) (issuer·client-id는 기본값이 없어 빠지면 기동 실패. `app.cognito.issuer-uri`·`app.cognito.client-id`로 바인딩 → `JwtDecoderConfig`가 issuer·`client_id` 검증) | 없음 |
| catalog | `APP_SERVICES_BOOKING_URL`, `APP_SERVICES_BOOKING_READ_TIMEOUT=300ms`, `APP_CACHE_TTL=10m`, `APP_SCHEDULE_ENABLED=true`·`APP_SCHEDULE_DAYS=7`·`APP_SCHEDULE_INTERVAL=1h`(상영 일정 이어 붙이기 — 오늘(KST)부터 7일 안에 회차가 없는 영업일을 앞선 가장 최근 영업일 편성으로 복사, 날짜·시각만 옮김. 회차가 있는 날은 건드리지 않는다. 새 회차 ID = 영업일 epochDay × 100000 + 순번, `INSERT IGNORE` + `GET_LOCK`이라 파드 여럿이어도 한 번만. 넣은 날짜의 `catalog:screenings:{date}:*` 캐시 삭제. 기본값으로 동작해 gitops 변경 불필요) | `SPRING_DATASOURCE_PASSWORD` |
| 🆕 booking (추가) | `APP_CHECKOUT_SAGA_ENABLED`(기본 `false` — 체크아웃 API 404. 메시징·coupon 준비 뒤 `true`), ~~`APP_CHECKOUT_STEP_TIMEOUT=30s`~~ 🆕 `APP_CHECKOUT_STEP_TIMEOUT=10s`(쿠폰 예약·결제 준비만), ⏳ `APP_CHECKOUT_VERIFY_INTERVAL`(결제 재확인 간격, 5s 제안 — 재확인은 선점 만료 + `APP_PAYMENT_PENDING_GRACE`까지), `APP_MESSAGING_COUPON_COMMANDS_URL=${SQS_COUPON_COMMANDS_URL}`, `APP_MESSAGING_PAYMENT_COMMANDS_URL=${SQS_PAYMENT_COMMANDS_URL}`, `APP_MESSAGING_REPLIES_URL=${SQS_BOOKING_REPLIES_URL}`, `APP_MESSAGING_EVENTS_TOPIC_ARN=${SNS_BOOKING_EVENTS_ARN}`, (여유 시) `APP_SCREENING_LOOKUP_MODE=sync\|local` | 같음 |
| 🆕 coupon | `SPRING_DATA_REDIS_*`(기존 Valkey), `APP_MESSAGING_COMMANDS_URL=${SQS_COUPON_COMMANDS_URL}`, `APP_MESSAGING_REPLIES_URL=${SQS_BOOKING_REPLIES_URL}`, `APP_COUPON_IDEMPOTENCY_TTL=24h`, `APP_COUPON_RESERVED_ALARM_AFTER=1h`, 🆕 `APP_COUPON_SWEEP_INTERVAL=1m`(종료 스윕 — ISSUED → EXPIRED, 종료 뒤 오래 RESERVED로 남은 쿠폰 ERROR 로그), 🆕 `APP_MESSAGING_CONSUMER_ENABLED`·`APP_OUTBOX_RELAY_ENABLED`(기본 `false` — payment와 같은 이름·뜻. `coupon-commands`·`booking-replies` 큐와 Pod Identity `cinema-staging-coupon-pod`(6.1.3) 뒤 gitops로 `true`), ⏳ 이벤트 값(수량·할인율·시각)은 관리자 화면에서(`POST /ops/coupons/events`) | `SPRING_DATASOURCE_PASSWORD` |
| 🆕 user (추가) | `APP_BOOKING_HISTORY_MODE=sync\|local`(`local`은 사용자별 첫 조회 때 1회 sync로 채운 뒤 복제본만 — API 명세서 v1.5 23-1), `APP_MESSAGING_BOOKING_EVENTS_URL=${SQS_USER_BOOKING_EVENTS_URL}` | 같음 |
| 🆕 catalog (추가) | `APP_MESSAGING_EVENTS_TOPIC_ARN=${SNS_CATALOG_EVENTS_ARN}`, `APP_OUTBOX_RELAY_ENABLED`(기본 `false` — booking과 같은 플래그·같은 이유: SNS 토픽 `catalog-events`와 구독 큐(6.1.3)가 생기기 전에 켜면 재시도 끝에 아웃박스 행이 `FAILED`가 된다. 꺼 두면 `PENDING`으로 쌓였다가 켤 때 흘러간다. 토픽·`ai-catalog-events` 구독 뒤 gitops로 `true`), 🆕 `APP_OUTBOX_RELAY_INTERVAL=1s`, `APP_CATALOG_CHANGE_SCAN_ENABLED`(기본 `true` — 변경 찾기는 outbox에 쓰기만 하고 보내지 않아 릴레이가 꺼져 있어도 켜 둔다)·`APP_CATALOG_CHANGE_SCAN_INTERVAL=1m`(6.3.4a, data-model 7-2), 로컬 전용 `APP_MESSAGING_EVENTS_QUEUE_URL`(ElasticMQ에 SNS가 없어 큐로 직접 — 토픽이 있으면 토픽이 먼저). SNS 발행 권한은 Pod Identity `cinema-staging-catalog-pod`(2장) | 같음 |
| 🆕 payment (추가) | `APP_MESSAGING_COMMANDS_URL=${SQS_PAYMENT_COMMANDS_URL}`, `APP_MESSAGING_REPLIES_URL=${SQS_BOOKING_REPLIES_URL}`. 포트원 스윕·웹훅이 saga 결제(`saga_payment`)를 닫으면 결과 이벤트도 낸다(data-model 7-2) — 새 설정 없음 | 같음 |
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

#### frontend (빌드 시 `VITE_`, 공개 값만)

| 키 | 값 |
| --- | --- |
| `VITE_API_BASE_URL` | `/api` |
| `VITE_COGNITO_AUTHORITY` | `https://cognito-idp.ap-northeast-2.amazonaws.com/{userPoolId}` |
| `VITE_COGNITO_CLIENT_ID` | 공개 클라이언트 ID (시크릿 없음) |
| `VITE_COGNITO_DOMAIN` | `https://cinema-staging.auth.ap-northeast-2.amazoncognito.com` |
| `VITE_COGNITO_REDIRECT_URI` | **넘기지 않는다**(3.1.23) — 비우면 화면이 지금 주소(`window.location.origin`) + `/callback`. CloudFront 기본 도메인·사용자 도메인 어느 쪽으로 들어와도 같은 주소로 돌아온다. 허용 목록은 Cognito 앱 클라이언트 콜백(`envs/staging/cognito.tf`, 두 도메인 + 로컬) |

`frontend-deploy` 워크플로가 Terraform 출력에서 값을 넣는다(GitHub Variables). OIDC `scope`는 `openid email profile aws.cognito.signin.user.admin`으로 설정한다. 프로필 행이 없을 때 user 서비스가 같은 Access Token으로 Cognito `GetUser`를 호출하고, 기존 행이 있으면 호출하지 않는다. `NotAuthorizedException`은 401 `UNAUTHORIZED`(`details[].reason` `TOKEN_REJECTED`)로, 그 밖의 Cognito 실패는 503 `IDENTITY_UNAVAILABLE`로 반환한다. 토큰은 `react-oidc-context`가 관리하고 `Authorization: Bearer`로 보낸다. 저장소는 **메모리**(`InMemoryWebStorage`, localStorage 금지). 새로고침하면 `signinRedirect`로 Cognito 세션을 이용해 다시 받는다(비밀번호 재입력 없음, ADR-63).

### 4. 이미지·DB 마이그레이션

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

### 5. 필수 메트릭 (대시보드·자동 롤백이 이 이름에 의존)

| 메트릭 | 타입 | 라벨 값 | 서비스 | 쓰는 곳 |
| --- | --- | --- | --- | --- |
| `http_server_requests_seconds_*` (Spring 기본) | histogram | `status`, `uri`, `method` | 전체 (⚠️AI ai는 같은 이름으로 직접 낸다) | 5xx 비율·p95, 블루/그린 분석 |
| `booking_seat_hold_total` | counter | `result`=`success`·`conflict`·`active_hold`·`error` | booking | 신호등·동시성 데모. `success`는 PENDING 커밋(발행 전), `conflict`=409 `SEAT_UNAVAILABLE`, `active_hold`=409 `ACTIVE_HOLD_EXISTS`, `error`=5xx(Catalog 503·Valkey·RDS). **4xx 거절(검증·시작된 회차·없는 회차)은 세지 않는다** → 신호등 분모 = 네 라벨 합 |
| `booking_sse_connections` | gauge | — | booking | SSE 재연결 확인 (블루/그린 전환·노드 재기동 때 0으로 떨어졌다 다시 오르는 게 정상) |
| `booking_payment_call_total` | counter | `result`=`approved`·`declined`·`timeout`·`error` | booking | **자동 롤백**(`declined`는 실패율에서 제외). TOSSPAY(ADR-112): 준비(`prepare`)는 세지 않는다(돈이 안 움직임). 완료 확인(`verify`)은 APPROVED → `approved`, FAILED·CANCELED → `declined`, **결제창 미완료(PROCESSING)는 세지 않는다**(사용자가 늦게 닫은 것을 장애로 보지 않게), 응답 없음 → `timeout`, 4xx → `error`(409 `NOT_VERIFIABLE`은 호출자 실수라 세지 않음) |
| `booking_hold_expiry_lag_seconds` | histogram | — | booking | 만료 반영 2초 목표. 노드 0대 구간(ADR-34) 뒤 첫 스윕은 밀린 만큼(수 시간) 큰 값이 한 번 찍히는 게 정상 → 경보는 파드 기동 후 10분 제외(3.1.7) |
| `booking_notification_publish_total` | counter | `result`=`sent`·`retry`·`failed` | booking (릴레이) | 알림 경보 |
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
| 🆕 `booking_checkout_total` · `booking_checkout_seconds` · `booking_checkout_compensation_total` | counter · histogram · counter | `result`=`confirmed`·`failed`·`expired`·🆕 `coupon_rejected` · — · `reason`=`payment_declined`·`step_timeout`·`hold_expired`(🆕 쿠폰 거절은 보상이 아니라 `result`로 센다) | booking | 체크아웃 saga(ADR-116). 자동 롤백은 기존 `booking_payment_call_total` 그대로 |
| 🆕 `booking_checkout_step_seconds` · `booking_checkout_verify_retry_total` | histogram · counter | `step`=`accepted`·`coupon_reserved`·`payment_prepared`·`verified`·`applied` · — | booking | 동기/비동기 비교 측정(API 명세서 v1.5 21-3) — 단계별 시간·결제 재확인 횟수 |
| 🆕 `payment_command_total` · `payment_command_seconds` | counter · histogram | `type`, `result`=`ok`·`duplicate`·`error` · `type` | payment | saga 명령 처리 |
| 🆕 `coupon_command_total` | counter | `type`, `result`=`ok`·`duplicate`·`rejected`·`error` | coupon | saga 명령 처리(거절 포함) |
| 🆕 `{svc}_message_consumed_total` | counter | `type`(메시지 type), `result`=`ok`·`duplicate`·`error` | 메시지 받는 서비스 | inbox 중복·실패. DLQ 깊이는 CloudWatch |
| 🆕 `coupon_claim_total` · `coupon_stock_remaining` | counter · gauge | `result`=`issued`·`sold_out`·`not_eligible`·`duplicate`·`closed` · — | coupon | 선착순 시연 |
| 🆕 `coupon_outbox_publish_total` · `coupon_outbox_pending` | counter · gauge | `result`=`sent`·`retry`·`failed` · — | coupon (결과 릴레이) | saga 결과 발송·밀림(6.3.2 — payment `payment_outbox_*`와 같은 뜻) |
| 🆕 `catalog_outbox_publish_total` · `catalog_outbox_pending` | counter · gauge | `result`=`sent`·`retry`·`failed` · — | catalog (변경 이벤트 릴레이) | `screening.changed`·`movie.changed` 발송·밀림(6.3.4a — coupon과 같은 뜻). 릴레이가 꺼져 있는 동안 `pending`이 쌓이는 것이 정상이고, 처음 켠 날은 회차·영화 수(약 8,400)까지 올랐다가 줄어든다 |
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

### 6. 네트워크

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

### 7. 개발 ↔ 인프라 인계 지점

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

### 8. 인프라 쪽 남은 정리

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
- [ ] 🆕 Karpenter(NodePool·EC2NodeClass·중단 알림 SQS)·`cluster-scale.sh` 연동 (6.1.6, ADR-122 — `envs/staging/karpenter.tf`·`envs/staging-k8s/karpenter.tf`·gitops `platform/karpenter`·밤 정리 Lambda `cinema-staging-karpenter-sweep` — 코드 11/25 · **런북 6-K 확인 후 체크**). t3.large·xlarge On-Demand, Karpenter 노드 합계 `limits.cpu` 16, 빈 노드만 정리(`WhenEmpty`, 압축·만료·Drifted 교체 없음 — 시연·블루/그린 중 파드를 옮기지 않는다, 시연 중에는 budget 0 PR). 컨트롤러는 관리형 노드에만 → 밤에 관리형 노드그룹이 0대면 정리 Lambda가 15분마다 Karpenter 노드를 지운다
- [ ] 🆕 ops-api·`/ops` CloudFront 동작·ALB 규칙·Role·서비스 Application `ignoreDifferences` (6.1.7, ADR-119)
- [ ] 🆕 WAF·리포트 버킷·Glue·Athena·내보내기 CronJob (6.1.8·6.1.9, ADR-121·124)
- [ ] 🆕 CloudWatch EKS audit 로그 끄기(하루 약 1.5GB) · Budgets 일 경보 $50

### 9. ⚠️AI ai 서비스 (Python, ADR-82~87)

| 항목 | 앱(김서현)이 할 일 | 인프라(김종식)가 할 일 |
| --- | --- | --- |
| 런타임 | Python 3.12, FastAPI + uvicorn(워커 1), `pyproject.toml` + `uv.lock`. 테스트 pytest, 린트 ruff | `pr-check` `python` job(ruff·pytest, `services/ai/**` 바뀐 PR만), `changed-services.sh`의 `ALL_SERVICES`에 `ai` + **ai만 Gradle 대신 `docker build services/ai`** 분기 |
| 포트·헬스·메트릭 | 8080. `/actuator/health/liveness`(프로세스만), `/actuator/health/readiness`(스냅샷 첫 적재 완료 + ai Valkey PING), `/actuator/prometheus` | Java 서비스와 같은 프로브·PodMonitor 템플릿(`app.kubernetes.io/name: ai`, 포트 `http`, 15초). startupProbe 5초 × 30회(임베딩 모델·첫 스냅샷 적재) |
| 이미지 | `python:3.12-slim`, UID 10001, 임베딩 모델(`paraphrase-multilingual-MiniLM-L12-v2`, ONNX, fastembed)을 **빌드 때** `/app/models`에 넣는다(실행 중 다운로드 없음). 시드·스냅샷 파일은 이미지에 넣지 않는다 | `build-push`가 ECR `cinema/ai:{sha7}` → gitops `overlays/staging/ai/image-tag`. Trivy 동일 기준 |
| 비루트·파일 | 쓰기 경로 `/tmp`만. SQLite는 **메모리 DB(`:memory:`)** — 디스크에 남기지 않음 | `runAsNonRoot`, `readOnlyRootFilesystem` + `/tmp` emptyDir |
| 로그 | stdout JSON 한 줄(`timestamp`·`level`·`service=ai`·`traceId`·`userId`·`path`·`latencyMs`·`tokens`·`questionLength`·`questionHash`·`sqlTemplate`·`rowCount`, 좌석 잡기일 때 `holdReason` — 결과 코드만). **질문 원문·SQL 리터럴 값·결과 행·Claude 응답 본문·사용자 토큰·좌석 이름·예매 ID는 남기지 않는다** — 질문에 이메일·전화번호 같은 개인정보가 들어올 수 있다. `questionHash` = HMAC-SHA256(질문, 파드 기동 때 만든 임의 키) 앞 16자(같은 파드 안 반복 질문 추적용, 되돌릴 수 없음), `sqlTemplate` = 리터럴을 `?`로 바꾼 SQL(sqlglot). 디버깅에 원문이 필요하면 로컬에서 재현한다. **예외 — `unmet demand` 로그**(`category`·`reason`·`reasonDropped`·`questionHash`·`questionLength`): 범위 밖 요청의 `reason`은 Claude가 쓴 120자 이하 요약으로 예외 허용. 질문을 베낀 요약은 버리고(`reason: null`, `reasonDropped: true`) 숫자 연속·이메일은 가린다(`[숫자]`·`[이메일]`). `chitchat`은 기록하지 않는다 | `kubectl logs` |
| 오류·traceId | `error.v1.json` 형식 그대로, `X-Trace-Id` 받으면 그대로·없으면 32자리 생성. 코드는 `services/ai` 안(conventions 6장 ⚠️AI 행). `libs/` 변경 없음 | — |
| 배포 | 별도 ArgoCD Application `cinema-ai` | `argocd_apps.tf` `argocd_services`에 `ai` 추가. **다른 Application 파일은 수정하지 않는다** |

#### 9-1. 설정 (ConfigMap `ai-config` · `cinema-infra-endpoints` · Secret `ai-secrets`)

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

#### 9-2. 실행 규칙

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

### 10. 🆕 ops-api 서비스 (Python, 노션 ADR-119 · 11/23)

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

---

# 4. 데이터 모델·DB 계약 (`docs/data-model.md`)

> 기준 2026-10-21. 원본은 노션 WBS 2.2.2 「ERD·서비스별 스키마」(ERD 6차, 완료)와 각 서비스 Flyway V1. 이 문서는 개발·인프라가 함께 보는 **요약**이다. 테이블 정의가 바뀌면 Flyway 파일이 먼저, 이 문서가 다음이다.
> 10/22 결정 반영(ADR-59·60·61). 스키마는 4개(catalog·booking·user·payment)뿐이다.
> ⚠️AI 11/5 AI 재도입(ADR-82~87)에도 **RDS는 바뀌지 않는다**(리포트 뷰·`ai_readonly` 없음, ADR-60 유지). ai 데이터는 6장.
> 🆕 11/23 MSA 고도화(노션 ADR-115·116·117·118·120): **서비스마다 자기 RDS 인스턴스**(6개), 스키마 `coupon_db`·`ai_db` 추가, saga·inbox 테이블 추가. 7장이 이번 추가분 요약이다(정의 원본은 각 서비스 Flyway).

---

### 1. 공통 규칙

| 규칙 | 내용 |
| --- | --- |
| 인스턴스 | ~~RDS MySQL 8.4 1대(ADR-3·33)~~ 🆕 **서비스별 인스턴스 `cinema-staging-{svc}-db`**(ADR-115, 7-1). 스키마 이름·계정 이름은 그대로, 다른 인스턴스 권한 없음 |
| 계정 | `{svc}_app`(DML: SELECT·INSERT·UPDATE·DELETE), `{svc}_migrator`(DDL: 해당 스키마 ALL). 비밀번호는 Terraform 랜덤 → K8s Secret |
| 참조 | 서비스 간은 ID 컬럼만, FK 없음. 같은 스키마 안에서만 FK |
| 이름 | snake_case, 테이블 단수형 |
| ID | `BIGINT`(Java `Long`). 예외: `user_id` = Cognito `sub` `CHAR(36)`, `hold_id`·`payment_id`·`message_key` = 앱 발급 UUID `CHAR(36)` |
| 시각 | `DATETIME(3)` **UTC**. 응답은 `+09:00` 변환. `business_date`만 KST 영업일 `DATE` |
| 상태 | `VARCHAR` + 앱 enum. 철자 `CANCELED`, 컬럼 `canceled_at` |
| 문자셋 | `utf8mb4` / `utf8mb4_0900_ai_ci` |

### 2. 스키마별 테이블

#### catalog_db (한채연) — 시드 DDL 기준

| 테이블 | 키·제약 | 비고 |
| --- | --- | --- |
| `theater` | PK `theater_id`, UK (`source_provider`, `source_theater_id`) | 132곳, `has_layout=1` 4곳 |
| `auditorium` | PK `auditorium_id`, UK (`theater_id`, `source_auditorium_id`), FK theater | 39개 관 |
| `seat` | PK `seat_id`, UK (`auditorium_id`, `layout_version`, `seat_label`), FK auditorium | 6,767석, `seat_id = auditorium_id × 10000 + 순번`, `active` |
| `auditorium_layout` | PK (`auditorium_id`, `layout_version`) | 스크린·입구·통로 JSON, `fetched_at` |
| `layout_import` | PK `import_id` | 수집·검증 이력 |
| `movie` | PK `movie_id`, UK (`source_provider`, `source_movie_code`) | 22편. TMDB 보충 컬럼(NULL 허용): `tmdb_id`, `genres`, `overview`, `release_date`, `runtime_minutes`, `poster_path`, `director`, `cast_names`, `meta_fetched_at` |
| `screening` | PK `screening_id`, UK (`auditorium_id`, `business_date`, `play_sequence`), INDEX (`movie_id`, `business_date`) | `starts_at`·`ends_at` UTC, `layout_version` |

#### user_db (~~한채연~~ 🆕 김종식, 11/24)

| 테이블 | 키·제약 | 비고 |
| --- | --- | --- |
| `user` | PK `user_id` CHAR(36), UK `email` | password·role 없음(Cognito). email·name은 첫 `GET /users/me` 때 `GetUser` |
| `user_preference` | PK `user_id` | `preferred_time_slot`, `preferred_genres` JSON |
| `user_preferred_theater` | PK (`user_id`, `rank_no`), UK (`user_id`, `theater_id`) | `rank_no` 1~3 |

#### booking_db (남궁일)

| 테이블 | 키·제약 | 비고 |
| --- | --- | --- |
| `booking` | PK `booking_id`, UK `hold_id`, UK `current_payment_id`, INDEX (`status`, `expires_at`) | `user_id` CHAR(36), `screening_id`, `status`, `seat_count` 1~4, `amount`, `seat_labels` JSON, `expires_at`, `created_at`·`confirmed_at`·`canceled_at`. hold_id·seat_labels·amount는 선점 후 불변 |
| `booking_seat` | PK (`booking_id`, `seat_id`), **UK (`screening_id`, `seat_id`)** | 이중 확정 최종 방어선. PAYMENT_FAILED·EXPIRED·CANCELED 때 행 삭제 |
| `outbox_message` | PK `outbox_id`, UK `message_key`(= 알림 `eventId`), UK (`event_type`, `aggregate_id`), INDEX (`status`, `next_attempt_at`) | `payload` JSON, `trace_id` CHAR(32), `status` PENDING·SENDING·SENT·FAILED, `attempt_count`, `last_error`, `sent_at`. 확정과 같은 트랜잭션에서 INSERT, 릴레이가 1·2·4…최대 60초 간격 재전송, 10회 초과 FAILED + 경보 |

#### payment_db (남궁일)

| 테이블 | 키·제약 | 비고 |
| --- | --- | --- |
| `payment` | PK `payment_id` CHAR(36)(booking 발급), **UK `booking_id`**(`uk_payment_booking_id`, 예매당 1회, ADR-24), INDEX (`status`, `created_at`)(기동 정리) | `amount`, `method`, `card_last4`(카드 번호 저장 금지), `status` PROCESSING·APPROVED·FAILED·CANCELED, `failure_code`, `cancel_reason`, `pg_provider`(`MOCK`·`PORTONE`, ADR-112 — ADR-60의 「MOCK만」 대체), `pg_transaction_id`(PORTONE에서 돈이 잡힌 증거 = 포트원 transactionId, UK — 승인 행, 그리고 금액 불일치·늦은 결제로 FAILED인데 환불이 남은 행에도 기록. FAILED + 값 있음 + `canceled_at` null = 환불 미완료), `approved_at`·`canceled_at`. user_id 없음. MOCK: PROCESSING으로 먼저 INSERT·커밋 → 판정은 트랜잭션 밖 → `UPDATE … WHERE status='PROCESSING'`, 30초 넘게 남은 PROCESSING은 기동 시 `FAILED`(`PROCESSING_TIMEOUT`)로 정리(ADR-60). PORTONE: PROCESSING(결제창 대기) → `verify`·웹훅이 포트원 단건 조회로 APPROVED·FAILED, 7분 넘게 PROCESSING이면 포트원 조회로 닫음(PAID면 APPROVED). `failure_code`에 `PG_DECLINED`·`USER_CANCELED`·`AMOUNT_MISMATCH` 추가. DDL 변경 없음(열은 이미 있음) |

### 3. 서비스 간 ID 참조 (FK 없음)

| 참조하는 쪽 | 컬럼 | 원본 |
| --- | --- | --- |
| `booking.booking` | `user_id`, `screening_id` | `user.user`, `catalog.screening` |
| `booking.booking_seat` | `screening_id`, `seat_id` | `catalog.screening`, `catalog.seat` |
| `payment.payment` | `booking_id` | `booking.booking` |
| `user.user_preferred_theater` | `theater_id` | `catalog.theater` |

### 4. 상태 전이 (booking)

| 현재 | 다음 | 계기 | 좌석 | 이벤트 |
| --- | --- | --- | --- | --- |
| — | PENDING | BKG-02 선점 | HELD | HELD |
| PENDING | CONFIRMED | BKG-10 승인 + 선점 유효 (+ 아웃박스 행) | RESERVED | CONFIRMED |
| PENDING | PAYMENT_FAILED | BKG-10 거절 | AVAILABLE | RELEASED |
| PENDING | EXPIRED | 만료(keyspace · `hold-expiry` 1초 · RDS 10초 스캔) | AVAILABLE | EXPIRED |
| PENDING | CANCELED | BKG-03 해제 | AVAILABLE | RELEASED |
| CONFIRMED | CANCELED | BKG-07 취소 + PAY-03 | AVAILABLE | RELEASED |

- 모든 전이는 **현재 상태를 조건으로 건** 조건부 UPDATE로 한 번만 처리한다: `UPDATE booking SET status=:next … WHERE booking_id=:id AND status=:expected`(위 표의 "현재" 열). 예: 만료·해제·결제 결과는 `:expected='PENDING'`, 확정 예매 취소(BKG-07)는 `:expected='CONFIRMED'`. 갱신 행이 0이면 이미 다른 경로가 처리한 것(재시도·중복 이벤트)으로 보고 아무것도 하지 않는다. 처리권은 `ZREM`이 아니라 UPDATE 결과 1행으로 정한다
- 이벤트 발행은 커밋 후, `ZREM hold-expiry`는 마지막

### 5. 인프라가 만드는 것 (3.1.3 · 3.1.12)

| 대상 | 내용 |
| --- | --- |
| RDS 파라미터 그룹 | `time_zone=UTC`, `character_set_server=utf8mb4`, `collation_server=utf8mb4_0900_ai_ci` |
| 스키마 | `catalog_db`, `booking_db`, `user_db`, `payment_db` — 🆕 각자 자기 인스턴스에 + `coupon_db`·`ai_db` |
| 계정·권한 | 1장 계정 규칙 + 모든 계정 `REQUIRE SSL`, `@'%'`. 마스터 계정(`cinema_master`)은 앱·Flyway Job에서 쓰지 않음. 마스터 비밀번호는 RDS가 Secrets Manager에서 관리(3.1.3) → 계정·스키마 생성은 3.1.12 클러스터 안 Job `cinema-ops/db-bootstrap-*`(Pod Identity로 마스터 Secret 읽기, RDS는 VPC 밖에서 닿지 않음). SQL은 멱등(`IF NOT EXISTS` + `ALTER USER`) → 비밀번호 교체도 같은 Job |
| Secret | `{svc}-secrets`(`SPRING_DATASOURCE_PASSWORD`), `{svc}-migrator-secrets`(`FLYWAY_USER`, `FLYWAY_PASSWORD`) — namespace `cinema-staging`, 비밀번호는 Terraform `random_password`(영숫자 32자, `envs/staging-k8s` state에만) |
| ElastiCache Valkey 8.2 | `notify-keyspace-events Ex`(만료 감지, ADR-44), 전송 중 암호화, `maxmemory-policy noeviction`(선점 키가 밀려나면 안 됨), 노드 **`cache.t4g.micro`** + 메모리 80%·Evictions 경보(ADR-59) |
| 시드 적재 | 3.1.12 후 `lotte-seed/load/run.sh`(관리자): 일회성 Job이 catalog Flyway를 먼저 적용하고 시드를 `catalog_migrator`로 1회 넣은 뒤 검증(극장 132·상영관 39·좌석 6,767·회차 8,360·영화 22). 재실행 안전, 끝나면 Job·ConfigMap 삭제. 시드 파일은 레포·gitops에 두지 않는다 |
| 가용성·백업 | ~~RDS **Single-AZ**(ADR-61) `db.t4g.small`~~ 🆕 7-1 표(ai 외 Multi-AZ), 자동 백업 7일(03:00 KST), 최종 스냅샷 없음(재생성 리허설용) · 자동 백업은 삭제 후에도 보존 |
| ⚠️AI ElastiCache ai Valkey 8.2 | `cinema-staging-ai-valkey` `cache.t4g.micro` 1노드, `maxmemory-policy noeviction`(한도 키가 밀려나지 않게, 모든 키 TTL), `reserved-memory-percent 50`(벡터 검색 조건), 전송 중 암호화, 메모리 80% 경보. **기존 Valkey와 별도** — 좌석 선점 키와 메모리를 나누지 않는다(ADR-85). 밤에도 유지(ElastiCache는 정지 불가, 월 약 $12) |
| 만들지 않는 것 | 리포트 뷰(`v_*_report`)·`stats_db`·⚠️AI `ai_readonly` 계정 — catalog Flyway V2 `v_screening_report`는 머지하지 않음(ADR-60). ~~ai는 RDS에 접근하지 않는다(ADR-84)~~ 🆕 ai는 **자기 인스턴스 `ai_db`만**(ADR-120, 6-4). 다른 서비스 DB 접근·리포트 뷰는 계속 없음 |

### 6. ⚠️AI ai 서비스 데이터 (RDS 밖, ADR-84·85)

#### 6-1. SQLite 스냅샷 (ai 파드 메모리, `:memory:`)

공개 API(`PUBLIC_API_BASE_URL` = CloudFront `/api`)의 응답만으로 채운다. 갱신마다 새 메모리 DB에 쓰고, 다 채운 뒤 읽기 전용으로 잠가(`PRAGMA query_only=ON` + authorizer) 질의용과 바꾼다 — `mode=ro`는 `:memory:`에 쓸 수 없다(`docs/service-runtime-contract.md` 9-2). **사이트에서 누구나 볼 수 있는 값만** 들어가고, 시드 원본·롯데 스냅샷·포스터는 들어오지 않는다. 디스크·이미지·레포·PVC에 남지 않고, 파드가 꺼지면 사라진다.

| 테이블 | 컬럼 | 출처(공개 API) | 갱신 |
| --- | --- | --- | --- |
| `movie` | `movie_id` PK, `title`, `title_en`, `view_grade`, `slot_minutes`, `runtime_minutes`, `release_date`, `director`, `genres`(쉼표), `synopsis` | `GET /movies?status=NOW_SHOWING`을 `page=0`부터 `totalPages - 1`까지 모두 조회한 뒤 목록의 각 영화에 `GET /movies/{id}` 호출 | 10분 |
| `theater` | `theater_id` PK, `name`, `region`, `has_layout` | `GET /theaters?hasLayout=true` | 10분 |
| `screening` | `screening_id` PK, `movie_id`, `theater_id`, `auditorium_id`, `auditorium_name`, `screen_type`, `business_date`(`YYYY-MM-DD`), `starts_at`·`ends_at`(KST `YYYY-MM-DD HH:MM`), `start_hour`(0~23), `film_format`, `total_seats`, `remaining_seats`, `remaining_seats_source`, `fetched_at` | `GET /screenings?date&theaterId` (극장 4곳 × 오늘~+6일) | **오늘 1분**, 나머지 10분 |
| `snapshot_meta` | `table_name`, `scope`, `refreshed_at` | — | 적재마다 |

- 넣지 않는 것: `posterUrl`(롯데 포스터 표시 금지), 배치도·좌석 ID, 예매·결제·사용자 데이터
- 시각은 모두 KST 문자열(질문이 KST 기준이라 LLM이 변환하지 않게). 공개 API는 시작한 회차를 제외하지만 적재 후 시간이 흐르므로, SQLite에서 회차를 반환하는 모든 질의는 현재 KST와 `starts_at`을 비교해 `starts_at > 현재 KST`인 회차만 반환한다
- ⚠️AI 좌석 화면 넘기기(3.4.18): 결과 행 하나가 회차 하나인 질의(집계·DISTINCT·UNION 없이 `screening`을 한 번 읽고, 그 회차의 `starts_at`이 결과에 그대로 나옴)는 실행할 때만 `screening_id` 열을 끝에 덧붙였다가 떼어 응답 `screeningIds`(`rows`와 같은 순서)로 보낸다. 표(`columns`·`rows`)·응답 `sql`·캐시 템플릿에는 나오지 않는다. 그 밖의 질의는 `screeningIds: null`
- 컬럼 한국어 설명·enum 뜻·few-shot은 `services/ai/src/seatsac_ai/resources/schema.yaml`(AI-10). 이 파일이 NL2SQL 정확도의 대부분을 정한다
- 답에는 "○시 ○분 기준"(`fetched_at`)을 붙인다

#### 6-2. 개인 데이터

"내 예매"는 SQLite에 넣지 않고 요청마다 `GET {PUBLIC_API_BASE_URL}/users/me/bookings`를 **사용자의 토큰 그대로** 호출해 답한다. 캐시하지 않고, Claude API에는 예매 행을 보내지 않는다(템플릿 문장으로 답). 채팅으로 좌석 잡기(플래그 `APP_AI_SEAT_HOLD_ENABLED` 기본 켜짐)도 같은 길(공개 주소 + 사용자 토큰 그대로)로 `POST /seats/hold`를 부르되 **사용자의 확인 버튼 뒤에만** 부른다.

#### 6-3. ai Valkey 키

`docs/conventions.md` 4장 ⚠️AI 표.

#### 6-4. 🆕 `ai_db` (11/23, 노션 ADR-120)

`APP_AI_DATA_SOURCE=db`일 때 6-1 SQLite 대신 쓴다(기본 `snapshot`, 같은 질의 규칙 — `starts_at > 현재 KST`, 포스터·시드 원본·개인 데이터 없음).

| 테이블 | 키·제약 | 채우는 방법 |
| --- | --- | --- |
| `movie` · `theater` · `screening` | 6-1과 같은 컬럼, PK 원본 ID | 최초 백필(공개 API) → SQS `ai-catalog-events`(`screening.changed`·`movie.changed`) upsert(`version`이 큰 것만) → 10분 전체 대조 |
| `processed_message` | PK `message_id` | inbox(중복 무시) |
| `unmet_demand` | PK `id`, INDEX (`created_at`), (`category`) | 범위 밖·거부 질문 1건 = 1행: `category`·`reason`·`question_hash`·`slots` JSON·`created_at`. **질문 원문·userId 없음** |

- 계정: `ai_app`(DML), `ai_migrator`(DDL), `ai_query`(NL2SQL 실행 전용, `movie`·`theater`·`screening` SELECT만, `MAX_EXECUTION_TIME` 5초)
- `ai_query`의 테이블 권한은 **ai 마이그레이션이 준다**(6.1.1): db-bootstrap은 계정만 만든다(권한 없음) — 그때는 테이블이 없고 MySQL은 없는 테이블에 SELECT만 줄 수 없다. 테이블을 만드는 마이그레이션 뒤에 `GRANT SELECT ON ai_db.movie TO 'ai_query'@'%'`(`theater`·`screening`도)를 넣는다. 그래서 `ai_migrator`만 `ai_db` 범위의 GRANT OPTION을 갖는다(다른 서비스의 migrator에는 없다). 세 테이블 밖의 권한을 주면 다음 db-bootstrap 실행이 실패한다. 5초 상한은 계정에 걸 수 없어 ai가 연결마다 세션에 건다

#### 6-5. 🆕 이용 안내 문서 (RAG, 6.4.7 — ADR-126)

`APP_AI_RAG_ENABLED=true`일 때만 쓴다(기본 `false`). DB·Valkey에 넣지 않는다 — 조각이 20개 안팎이라 파드 메모리로 충분하다(늘어나면 ai Valkey 벡터 인덱스로 옮긴다).

| 항목 | 내용 |
| --- | --- |
| 원본 | `services/ai/src/seatsac_ai/resources/guides.yaml` — 항목 하나가 조각 하나: `id`·`title`·`body`(300자 안팎) |
| 내용 | **SeatSAC 사이트 이용 안내만**: 예매 순서·좌석 선점 시간·결제·취소·관람등급 뜻·AI가 하는 일. 이 레포의 계약·문서로 확인되는 사실만 쓴다. 극장 시설·주차·매점처럼 우리 데이터에 없는 것은 쓰지 않는다(지어낸 답 방지 — 그런 질문은 계속 `OUT_OF_SCOPE`로 끝나 `unmet_demand`에 쌓인다). 롯데시네마 원문 금지 |
| 색인 | 기동 때 조각마다 임베딩(의미 캐시와 같은 모델, 384차원)을 계산해 메모리에 둔다. 고칠 때는 PR → 재배포 |
| 검색 | 질문 임베딩과 코사인 유사도 상위 `APP_AI_RAG_TOP_K`개 중 `APP_AI_RAG_MIN_SCORE` 이상만 (런타임 계약 9-1·9-2) |

### 7. 🆕 MSA 고도화 추가분 (11/23)

#### 7-1. 인스턴스 (ADR-115)

| 인스턴스 | 클래스 | AZ | 스키마 | Hikari 합(최대 파드 × 풀) |
| --- | --- | --- | --- | --- |
| `cinema-staging-booking-db` | `db.t4g.small` | **Multi-AZ** | `booking_db` | 블루/그린 순간 8 × 15 = 120 < 경보 130 |
| `cinema-staging-payment-db` | `db.t4g.micro` | Multi-AZ | `payment_db` | 2 × 5 |
| `cinema-staging-user-db` | `db.t4g.micro` | Multi-AZ | `user_db` | 2 × 5 (+ 롤링 1) |
| `cinema-staging-catalog-db` | `db.t4g.micro` | Multi-AZ | `catalog_db` | 4 × 5 (+ 롤링 1) |
| `cinema-staging-coupon-db` | `db.t4g.micro` | Multi-AZ | `coupon_db` | 4 × 5 |
| `cinema-staging-ai-db` | `db.t4g.micro` | Single-AZ | `ai_db` | 1 × 5 |

- 파라미터 그룹은 하나를 같이 쓴다(UTC·utf8mb4·TLS 강제). micro는 `max_connections` 약 60~80이라 경보는 인스턴스마다 `max_connections`의 75%
- 옮기는 순서: 새 인스턴스 → db-bootstrap(인스턴스별) → 덤프·복원(서비스 하나씩) → `DB_HOST_{SVC}`로 바꾸는 gitops PR(앱 `SPRING_DATASOURCE_URL`과 Flyway Job `FLYWAY_URL`의 호스트를 **같은 PR에서** 함께 — 따로 바꾸면 마이그레이션과 앱이 다른 인스턴스를 본다) → 이전 인스턴스는 다 옮긴 뒤 삭제. **쓰기는 덤프를 시작할 때부터 그 서비스가 새 인스턴스로 떠서 확인(Flyway Job 성공·readiness 200·행 수 대조)이 끝날 때까지 멈춘다** — 덤프 뒤에 기존 인스턴스에 쓰인 것은 새 인스턴스에 없다. 그 전에 쓰기를 다시 열어야 하면 전환을 미루고 덤프부터 다시 한다. catalog 시드는 다시 적재해도 된다(ID 고정)
- 마스터 비밀번호는 인스턴스마다 RDS 관리 Secrets Manager

#### 7-2. 테이블 추가 (이름은 API 명세서 v1.5 27-1과 같다)

| 스키마 | 테이블 | 키·제약 | 비고 |
| --- | --- | --- | --- |
| `booking_db` | `checkout_saga` | PK `checkout_id` CHAR(36), 🆕 UK `active_booking_id`, INDEX (`booking_id`, `created_at`), INDEX (`state`, `step_deadline_at`)(스윕) | `state`(STARTED·COUPON_RESERVING·PAYMENT_PREPARING·READY_TO_PAY·CHARGING·VERIFYING·CONFIRMED·COMPENSATING·FAILED·EXPIRED·🆕 COUPON_REJECTED), `method`(TOSSPAY·CARD), `coupon_issue_id`, `discount_rate`, `original_amount`·`discount_amount`·`final_amount`, `payment_id`, `failure_code`·`failure_reason`, `step_deadline_at`, `verify_attempt`. ~~진행 중 체크아웃은 예매당 1건(앱이 확인)~~ → 🆕 진행 중 체크아웃은 예매당 1건 — 생성 열 `active_booking_id`(진행 중 상태일 때만 `booking_id`, 끝난 상태면 NULL)의 UK가 동시 요청까지 막는다(409 `CHECKOUT_IN_PROGRESS`). 끝난 행은 NULL이라 쿠폰 거절 뒤 같은 예매에 새 행을 만들 수 있다 |
| `booking_db` | `booking` (기존) | 그대로 | `amount`는 선점 후 불변(원가). 할인·최종 금액은 `checkout_saga`에만, `booking.confirmed`의 `amount`(최종)·`discountAmount`로 내보냄 |
| `booking_db` | `processed_message` | PK `message_id` | 결과(reply) inbox |
| `booking_db` | `outbox_message` (기존) | 🆕 열 `aggregate_key VARCHAR(80) NOT NULL DEFAULT ''` 추가, UK (`event_type`, `aggregate_id`) → (`event_type`, `aggregate_id`, `aggregate_key`) | 명령·이벤트도 같은 표에 `event_type`으로. ~~결제 재확인은 새 행 `aggregate_id = {checkoutId}:{attempt}`~~ → 🆕 `aggregate_id`는 BIGINT라 문자열을 넣을 수 없다 — `aggregate_id = bookingId` 그대로, 명령은 `aggregate_key = {checkoutId}:{단계}`, 결제 재확인은 `{checkoutId}:{attempt}`(새 `eventId`). 기존 행·이전 버전 파드가 넣는 행은 `''`라 기존 유일 보장이 그대로다(블루/그린 중 두 버전 공존) |
| `payment_db` | `processed_message` · `outbox_message` | 같은 구조 | 명령 inbox · 결과 발송 |
| `payment_db` | 🆕 `saga_payment` | PK `payment_id`, INDEX (`checkout_id`) | saga 명령으로 만든 결제 표시(`checkout_id`, `booking_id`). payment 스윕·포트원 웹훅이 이 결제를 APPROVED·FAILED로 닫으면 **같은 트랜잭션에 결과 이벤트**(`payment.approved`·`payment.declined`)를 outbox에 넣는다 — 체크아웃 중 booking은 payment를 조회하지 않기 때문. 동기 경로 결제는 행이 없어 지금과 같다 |
| `user_db` | `booking_history` | PK `booking_id`, INDEX (`user_id`, `confirmed_at`) | `status` CONFIRMED·CANCELED, `screening_id`, `seat_count`, `amount`·`discount_amount`(NULL 허용), `confirmed_at`·`canceled_at`. CANCELED가 이긴다(순서 역전) |
| `user_db` | `booking_history_sync` · `processed_message` | PK `user_id` · PK `message_id` | 사용자별 첫 조회 1회 sync 채움 기록 · inbox |
| `catalog_db` | `outbox_message` · ~~`screening.version` 열~~ 🆕 `change_ledger`(6.3.4a) | outbox 컬럼·유일 제약은 booking과 같다(🆕 UK (`event_type`, `aggregate_id`, `aggregate_key`), `destination` 열만 없음) — `aggregate_id` = screeningId 또는 movieId, 🆕 `aggregate_key` = `{version}`. 장부 PK (`aggregate_type` SCREENING·MOVIE, `aggregate_id`), `version` BIGINT·`content_hash`(공개 필드 SHA-256, 삭제면 NULL)·`deleted`·`changed_at` | `screening.changed`·`movie.changed` 발행, 복제본은 `version`이 클 때만 덮어씀. 같은 회차·영화가 바뀔 때마다 `version`이 올라 **변경 1번 = 새 행 1개**(booking 결제 재확인과 같은 `aggregate_key` 방식 — ID만 넣으면 두 번째 변경이 유일 제약에 걸리고, `aggregate_id`는 BIGINT라 `{id}:{version}` 문자열을 넣을 수 없다). 🆕 catalog에는 쓰기 API가 없고 회차·영화는 시드 적재·TMDB 보충 SQL로만 바뀌어 **쓰기와 같은 트랜잭션에 outbox를 넣을 수 없다** → 변경 찾기(`APP_CATALOG_CHANGE_SCAN_INTERVAL` 1분)가 원본 공개 필드의 해시를 장부와 비교해, 차이마다 「장부 version + 1(조건부) + outbox 행」을 한 트랜잭션에 쓴다(파드 사이는 `GET_LOCK`). version을 `screening`·`movie` 열이 아니라 장부에 두는 이유: 시드 재적재는 행을 지웠다 다시 넣어 열 값이 처음으로 돌아가고, 지워진 행은 마지막 version을 남길 곳이 없다(계약: 삭제 뒤에도 version 보존·다시 생기면 더 큰 version). 트리거는 RDS(바이너리 로그)에서 `log_bin_trust_function_creators` 없이 만들 수 없어 쓰지 않는다. 스캔 사이에 여러 번 바뀌면 마지막 상태 하나로 나가고, 처음 켠 날은 회차·영화 전부가 version 1로 한 번씩 나간다 |
| `coupon_db` | `coupon_event` | PK `event_id` | `title`, `discount_rate`, `eligible_grade`(VIP), `total_quantity`, `starts_at`·`ends_at`. 🆕 `issued_quantity`(6.3.2) — 발급 트랜잭션이 `issued_quantity < total_quantity`일 때만 +1(조건부 UPDATE, `coupon_issue` INSERT와 같은 트랜잭션). 총수량의 최종 방어선 — Valkey 재고 키가 틀려도 넘겨 발급하지 않는다 |
| `coupon_db` | `coupon_issue` | PK `coupon_issue_id` CHAR(36), **UK (`event_id`, `user_id`)**, INDEX (`user_id`, `state`) | `state` ISSUED·RESERVED·USED·EXPIRED, `expires_at`(= 이벤트 종료), `issued_at`·`used_at`. 🆕 `claim_key`(6.3.2, 발급 요청의 `Idempotency-Key`) — `coupon:idem` 결과를 저장하지 못했어도 같은 키 재요청에 첫 201을 다시 준다(다른 키는 409 `COUPON_ALREADY_CLAIMED`) |
| `coupon_db` | 🆕 `coupon_event_lock` | PK `lock_id`(1행 고정) | 이벤트 생성 직렬화(6.3.2) — 생성이 이 행을 `FOR UPDATE`로 잠근 뒤 기간 겹침을 확인·저장한다. 표가 비어 있어도 동시 생성 두 건이 둘 다 통과하지 않게 |
| `coupon_db` | `coupon_reservation` | PK `checkout_id`, INDEX (`coupon_issue_id`) | `state` RESERVED·RELEASED·USED·🆕 REJECTED. 체크아웃 1건 = 1행 — 반환이 예약보다 먼저 오면 RELEASED 행을 먼저 만들어 늦은 예약을 거절. 🆕 거절도 `REJECTED` + `reject_reason`으로 남겨 같은 checkoutId의 재시도(새 eventId)에 같은 결과를 다시 보낸다. 예약 성공 행은 `discount_rate`·`event_id`를 함께 둔다(재시도에 같은 할인율) |
| `coupon_db` | `processed_message` · `outbox_message` | 같은 구조 | 명령 inbox·결과 발송 |

- 재고는 Valkey `coupon:stock:{eventId}`의 `DECR` 결과가 0 이상이고 DB 행 생성도 성공해야 확정. `INCR`로 되돌리는 것은 **`DECR`이 성공한 뒤**의 두 경우뿐이다 — 결과가 음수(매진 처리)이거나 DB INSERT가 실패했을 때. `DECR`이 실행되지 않았거나 실패했으면 `INCR`를 부르지 않는다(안 깎은 재고를 올리면 `total_quantity`를 넘겨 발급된다) — conventions 4장 `coupon:stock` 행과 같은 기준. DB UK `(event_id, user_id)`가 1인 1매 최종 방어선. 키가 없으면 `total_quantity − 발급 수`로 `SET NX` 재생성
- 재고 불변식은 `Valkey 재고 + 발급 수 ≤ total_quantity`(같다가 아니라 **이하**)다. `DECR`이 성공한 뒤 DB INSERT 전에 프로세스가 죽거나 `DECR` 응답을 받지 못하면(타임아웃) 그 1매는 되돌리지 않는다 — 결과를 모를 때 `INCR`를 부르면 안 깎인 재고를 올릴 수 있어서, **덜 발급되는 쪽**으로 틀리게 둔다(초과 발급 없음. 사용자가 다시 눌러도 UK `(event_id, user_id)`가 중복 발급을 막는다). 잃은 수량의 대조는 **발급이 멈춘 상태에서만** 한다: 이벤트가 끝난 뒤, 또는 운영자가 coupon을 멈춘 뒤 `total_quantity − 발급 수`로 키를 다시 쓴다. 발급이 도는 중에 키를 지우거나 다시 쓰면 그때 진행 중이던 발급 수만큼 더 나갈 수 있어서 하지 않는다(Valkey 재시작으로 키가 사라져 `SET NX`로 재생성되는 순간도 같다 — 드문 경우로 두고, 막으려면 6.3.2에서 INSERT 뒤 발급 수 확인을 넣는다). 이벤트 중 자동 복구(차감 기록을 따로 두고 스윕)는 넣지 않았다 — 1시간 이벤트에서 파드가 죽은 횟수만큼만 덜 나간다
- 이벤트 종료 스윕은 ISSUED만 EXPIRED. RESERVED는 booking의 use·release까지 그대로(1시간 넘으면 경보만)

#### 7-3. 체크아웃 상태 전이 (ADR-116, API 명세서 v1.5 21-2)

| 현재 | 다음 | 계기 | 보상 |
| --- | --- | --- | --- |
| — | STARTED | `POST /bookings/{id}/checkout`(예매 PENDING·선점 유효·마감 전·🆕 `current_payment_id` 없음) | — |
| STARTED | COUPON_RESERVING / PAYMENT_PREPARING (TOSSPAY) / 🆕 CHARGING (CARD) | 쿠폰 있음 / 없음 → 명령 발송(같은 트랜잭션 outbox). 🆕 **결제 명령을 처음 보낼 때** `attachPayment`로 paymentId를 붙인다 | — |
| COUPON_RESERVING | PAYMENT_PREPARING / 🆕 CHARGING | `coupon.reserved` → 금액 확정 + 🆕 `attachPayment` + 결제 명령 | — |
| COUPON_RESERVING | ~~FAILED~~ 🆕 **COUPON_REJECTED**(끝) | `coupon.reservation-rejected`(`failure.code` `COUPON_NOT_USABLE`) | **없음** — 예매 PENDING·좌석 HELD·`expires_at` 그대로(선점 시간을 늘리지 않는다). 사용자는 최초 선점 5분 안에 다른 쿠폰이나 쿠폰 없이 `POST …/checkout`을 다시 보낸다(새 행). paymentId를 쿠폰 단계에서 붙이지 않는 이유: 붙어 있으면 기존 만료 처리가 결제 중으로 보고 만료를 유예만큼 미룬다 |
| PAYMENT_PREPARING | READY_TO_PAY | `payment.prepared` | — |
| ~~PAYMENT_PREPARING~~ | ~~CHARGING (CARD)~~ | ~~`payment.charge-requested` 발송~~ → 🆕 CARD는 결제 준비 단계가 없다(위 두 행) | — |
| READY_TO_PAY | VERIFYING | `POST …/checkout/confirm` → `payment.verification-requested` | — |
| 🆕 READY_TO_PAY·VERIFYING·CHARGING | CONFIRMED | `payment.approved` + 예매가 **PENDING일 때만** → 예매 CONFIRMED(4장, 동기 경로와 같은 `BookingPaymentResultApplier`) + `coupon.use-requested`. READY_TO_PAY가 들어간 이유: 사용자가 결제 뒤 확인 요청 없이 떠나도 포트원 웹훅·payment 스윕이 결제를 닫고 결과 이벤트를 낸다(`saga_payment`) | — |
| VERIFYING·CHARGING | COMPENSATING → FAILED | `payment.declined`(`PAYMENT_DECLINED`) | `coupon.release-requested` + 좌석 해제(PAYMENT_FAILED) + `booking.canceled` |
| COUPON_RESERVING·PAYMENT_PREPARING | COMPENSATING → FAILED | 단계 기한 ~~30초~~ 🆕 **10초**(`STEP_TIMEOUT`) — **돈이 움직이기 전 단계만**(정상 왕복 1~3초, 최악 약 6초) | 위와 같음. 쿠폰은 예약 여부를 몰라도 반환 명령을 보낸다 |
| VERIFYING·CHARGING | (그대로) | 재확인 기한 안에 결과 없음 · `payment.result-unknown` | **보상하지 않음** — 새 `eventId`로 `payment.verification-requested` 재발송. 🆕 간격 ⏳(5초 고정 제안), `expires_at + APP_PAYMENT_PENDING_GRACE`(2분)까지만 보낸다 |
| ~~진행 중 어디서나~~ 🆕 READY_TO_PAY·VERIFYING·CHARGING | ~~COMPENSATING → EXPIRED~~ 🆕 **예매 상태를 따라** CONFIRMED / FAILED / EXPIRED | 🆕 예매가 PENDING을 벗어남. paymentId가 붙은 예매는 기존 만료 처리(`HoldExpiryService`)가 **선점 만료 + 유예 2분 뒤** payment를 조회해 닫는다 — 승인이면 확정하지 않고 환불 후 EXPIRED, 실패면 PAYMENT_FAILED, 아직 결제 중이면 payment 스윕이 결제 생성 7분 뒤 닫을 때까지 미룬다. 결과를 끝까지 모르면 대략 7~9분에 닫힌다 | saga는 환불·좌석을 다시 처리하지 않고 쿠폰만 반환(EXPIRED·FAILED). 결과 불명이었으면 `reason` `PAYMENT_RESULT_UNKNOWN`. 예매가 닫힌 뒤 온 `payment.approved`는 `applyApproved`를 부르지 않는다(환불 두 번 방지) |

- 4장 규칙 그대로: 모든 전이는 `WHERE state=:expected` 조건부 UPDATE, 이미 처리된 결과 메시지는 inbox·0행으로 무시. 결과 소비자·스윕은 블루/그린 preview 파드에서도 돈다(이전 버전과 호환)
- `GET /bookings/{id}`는 체크아웃 진행 중이면 payment를 부르지 않는다(동기 대조 없음)

<노션 반영 필요> 2.2.2 ERD에서 ai·stats 스키마·리포트 뷰 삭제(ADR-50·51·60), catalog V2 `v_screening_report` 머지 안 함, outbox_message 행의 "알림 발송 방식 팀 재확인 중" → "10/21 아웃박스 확정", Valkey `maxmemory-policy noeviction` 추가

<노션 반영 필요> 2.2.2 ERD에 11/23 MSA 고도화 반영: 인스턴스 6개(7-1), `coupon_db`·`ai_db` 스키마, booking `checkout_saga`·`processed_message`, payment `processed_message`·`outbox_message`, user `booking_history`·`booking_history_sync`, catalog `outbox_message`·`screening.version`, coupon `coupon_event`·`coupon_issue`·`coupon_reservation`(7-2)

---

# 5. 결정 요약 (ADR) (`docs/decisions.md`)

> 기준 2026-10-22 (회의록2 결정 반영). 원본은 노션 「결정 기록 (ADR)」(번호 = DB 번호). 근거·대안은 노션에서 본다. 이 문서는 **지금 유효한 결론**만 한 줄씩 둔다.
> 이후 결정이 앞 결정을 덮은 경우 "→ 대체"로 표시했다. ⏳는 아직 열린 항목이다.
> ⚠️AI 11/5 AI 재도입: ADR-82~87(노션 등록 번호는 등록 후 확인). 대체·보완 관계는 각 행 비고.
> 🆕 11/23 MSA 고도화: **6장**(노션 ADR-114~125 — 번호가 노션과 같다). 덮인 행은 비고에 「→ 11X」로 표시했다.

---

### 1. 범위·일정·사람

| ADR | 결론 | 비고 |
| --- | --- | --- |
| 1 · 50 | ~~서비스 5개, AI 제외(10/22)~~ → **82로 대체**: 서비스 6개(ai 추가) | 26 → 83으로 대체 |
| ⚠️AI 82 | AI 재도입: **Python(FastAPI) `services/ai`, 사용자 챗봇만**(로그인 필수, `/api/ai/**`). 질문 처리 정확 일치 캐시 → 의미 캐시 → 프리셋 → NL2SQL, "내 예매"는 공개 API. 담당 **김서현**. 데모는 1차 11/16(무중단·좌석) + **2차 11/17(AI)** | 1·50 대체, 52·53 보완. 관리자 NL2SQL·추천(AI-08) 제외 |
| ⚠️AI 83 | LLM은 **Claude API(Anthropic 직접) `claude-sonnet-5-5`**, 도구 호출로 프리셋/SQL 선택, 프롬프트 캐싱. 키: staging `ai-secrets`(Terraform sensitive), 로컬 개발 전용 키 `.env`(커밋 금지). Anthropic 월 지출 한도 $90(11/13 $20에서 상향 — 11/20 발표까지만 쓴다), 사용자당 동시 1·분당 10·하루 100(staging은 11/18 발표 전 테스트용으로 분당 600·하루 300 — gitops #29, 키 폐기까지. 동시 1·월 지출 한도는 그대로) | 26 대체. Bedrock 안 씀(Pod Identity 불필요) |
| 52 | (⚠️AI 82 보완: 11/17 AI 2차 데모 추가) 데모는 **무중단 배포 · 실시간 좌석** 2개로 확정. 실시간 좌석 데모에 동시성 테스트(같은 좌석 100명 → 성공 1명) 포함 | 선점 전략 비교는 여유 시 |
| 45 | 선점 전략 비교(db-lock/redis): 지금은 3.2.2에 `SeatHoldStrategy` 인터페이스만, db-lock은 잠글 행·만료 처리 **설계 초안만**(남궁일). 구현·측정은 최후 순위(4.2 때 여유 시) | 새 노드 불필요 |
| 53 | 김서현은 일정에서 제외. 필요하면 개발·인프라/DevOps의 WBS 하나씩 맡긴다 → ⚠️AI **82로 보완: AI는 김서현 고정 담당**(11/2) | 4.1.2 김서현·한채연, OPS-07 코드 김종식 |
| 25 | Payment 백엔드는 남궁일, 결제 화면은 한채연 | |
| 2 | 롯데시네마 좌석 배치·하루 편성만 로컬에서 1회 수집 → 비공개 `lotte-seed` 시드 | 실시간 연동·예매 호출 없음 |
| 15 · 16 | ⏳ 스크린 위치(GetSeats `ScreenSeatInfo` 사용 가정) · 수집 방식(로컬 1회 가정) — 한채연 확인 예정 | |

### 2. 데이터·실시간

| ADR | 결론 | 비고 |
| --- | --- | --- |
| 3 · 33 · 61 | RDS **MySQL 8.4** 1대(**Single-AZ**, `db.t4g.small`, TLS 강제, 마스터 비밀번호는 RDS 관리 Secrets Manager) + 서비스별 스키마·계정 | Multi-AZ·AZ 장애 실험은 시간 남을 때. 🆕 **→ 115·122로 대체**(서비스별 인스턴스·Multi-AZ) |
| 33 · 59 | ElastiCache **Valkey 8.2 `cache.t4g.micro`**, `noeviction`, `notify-keyspace-events Ex`, 메모리 80%·Evictions 경보 | 부하 테스트에서 70% 넘으면 t4g.small |
| 57 | DB 마이그레이션은 ArgoCD **Flyway 훅 Job**(`{svc}_migrator`, Sync 단계 wave -1 — ConfigMap 다음·앱 전, 실패 시 sync 중단), 앱은 Flyway 끔. 스키마·계정·Secret은 Terraform(`envs/staging-k8s`)이 클러스터 안 Job으로 생성(3.1.12) | 처음 "PreSync"였으나 첫 배포 때 ConfigMap보다 먼저 돌아 Sync wave -1로 변경(10/23) |
| 60 | 리포트 뷰 만들지 않음(catalog V2 머지 안 함, ⚠️AI 재도입 후에도 유지 — 84) · payment `PROCESSING` 30초 초과는 기동 시 `FAILED(PROCESSING_TIMEOUT)`(MOCK 행만) · ~~`pg_provider`는 `MOCK`만~~ → 112로 대체(`PORTONE` 추가) | |
| 4 | 좌석 이벤트: Valkey Pub/Sub `seat-events:{회차}` → SSE, 놓치면 BKG-01 스냅샷 | 유실 허용 |
| 5 | 선점: Valkey `SET NX EX {선점 TTL}`(`APP_SEAT_HOLD_TTL_SECONDS`, 기본 300초, 30~600)(Lua로 여러 좌석 원자적) + 그 시점 RDS PENDING 예매 | |
| 11 | 한 번에 최대 4석 | |
| 19 | 멱등: `Idempotency-Key` + Valkey `idem:{userId}:{키}` 24시간 + 유일 제약(`booking_seat`, `current_payment_id`) | |
| 22 | 내부 브로커 없음: Pub/Sub + keyspace 알림 + sorted set | 만료 반영 2초 초과 시 재검토. 🆕 좌석 실시간은 그대로, 서비스 간 비동기는 **117(SNS/SQS)** 로 보완 |
| 44 | 만료: keyspace(Ex) + `hold-expiry` 1초 스캔 + RDS 10초 스캔, 조건부 UPDATE로 한 번만 | 노션 본문 "ADR-21" = 이 항목 |
| ⚠️AI 84 | AI 데이터: **ai 파드 메모리 SQLite 스냅샷**, CloudFront 공개 API로만 적재(오늘 1분·나머지 10분). RDS·기존 Valkey·리포트 뷰·`ai_readonly` 없음. 실행은 읽기 전용·5초·500행, SQL 검증 sqlglot(SQLite). 포스터·시드 원본 미포함 | 기존 MSA DB를 건드리지 않음. 60 유지. 🆕 **→ 120으로 보완**(`APP_AI_DATA_SOURCE=db`면 자기 `ai_db`) |
| ⚠️AI 85 | AI 캐시: 새 ElastiCache **`cinema-staging-ai-valkey`** `t4g.micro`(벡터 검색, `noeviction` + 모든 키 TTL — 한도 키 보호, 예약 메모리 50%). 정확 일치 + **의미 캐시**(로컬 임베딩 `paraphrase-multilingual-MiniLM-L12-v2`, 슬롯 치환 뒤 임베딩, 유사도 0.92) + 세션(사용자별 키) + 사용 한도. 개인 질문은 캐시 안 함 | 59는 기존 Valkey에만 해당(그대로) |

### 3. 서비스 간 통신·계약·결제·알림

| ADR | 결론 | 비고 |
| --- | --- | --- |
| 23 · 32 | REST 동기(RestClient + Resilience4j), 연결 1초·응답 3초(catalog→booking 300ms), 서킷 50%·10초, OpenAPI 계약 + CI | p95 초과 시 gRPC. 🆕 동기 호출은 **유지**, 체크아웃은 116(saga)·117(메시징)으로 보완 |
| 38 · 54 | 계약 기준은 API 명세서 v1.1 + 2.2.4 v0.3 정리: 비페이징 목록 `{items}`, 오류 `details` 항상 배열, Cognito 조회 실패 503 `IDENTITY_UNAVAILABLE`, 내부 회차 API `seatIds` 삭제, `GET /internal/theaters` 신설 | `docs/contracts.md` |
| 55 | 계약 강화(구현 전 일괄): `booking.confirmed` 필드 3개 필수, traceId `^[0-9a-f]{32}$`, 오류 traceId null 불가. 첫 계약 PR만 `contract-break-ok`, 라벨은 구현 시작 엔드포인트·M3 이후 금지 | |
| 62 | Gateway 요청 수 제한 미도입(`rl:` 키·429 없음) | 🆕 ⏳ 재검토 중 — 124(WAF IP 요청 제한)로 대신할지 |
| 24 | 결제는 예매당 1회, 실패하면 PAYMENT_FAILED + 좌석 해제 | 20 폐기 |
| 13 | 모의 결제: 거절도 200 + status, 카드 끝 0000 거절·1111 잔액 부족·9999 5초 지연 후 승인, `PROCESSING` | 롤백 지표에서 거절 제외. **112로 부분 대체** — 화면은 토스페이, 모의는 테스트·분석 전용 |
| 112 | 결제는 **포트원(PortOne) V2 + 토스페이**(간편결제, `payMethod EASY_PAY`). 화면은 토스페이만. 두 단계: `POST /bookings/{id}/payments`(준비, paymentId 발급) → 결제창 → `POST /bookings/{id}/pay {method: TOSSPAY, paymentId}`(payment가 포트원 단건 조회로 PAID·금액 검증). 웹훅 `Transaction.Paid` 병행(본문 불신). 모의(`CARD`)는 테스트·분석 전용 — 헤더 `X-Payment-Mock-Key`가 맞을 때만(화면에는 없음). paymentId는 booking 발급 UUID 그대로. 결제창 미완료 정리: payment 7분, booking 만료+2분 | 11/13. 결제 경로 매트릭스 `docs/payment-paths.md`. 13 부분 대체·60 대체. 계약 PR #115는 `contract-break-ok`(55의 「M3 이후 금지」 예외 — 응답 enum 값 추가뿐이고 소비자인 화면·booking이 같은 기간에 함께 바뀐다). 일정은 #121(64 개정)로 동결 없음 |
| 30 · 51 | 예매 완료 알림: booking만 SQS 발행(아웃박스 + 릴레이) → Lambda(로그), DLQ 3회. **OPS-08 일별 집계는 제외** | |
| ⚠️AI (번호 미정 — 노션 등록 후 확정) | **채팅으로 좌석 잡기**(3.4.19): ai가 **사용자 확인 뒤에만** 공개 API `POST /seats/hold`를 대신 부른다 — ai가 부르는 쓰기 API는 이것 하나(결제·취소·예매 변경은 계속 금지). 조건: ① 확인 버튼 + 일회용 확인 ID(대기 행동 `ai:pending:{userId}:{sessionId}` 2분, 한 번만 — 타이핑한 「좌석 잡기」는 확인이 아님) ② 플래그 `APP_AI_SEAT_HOLD_ENABLED` **기본 켜짐**(머지·배포 = 사용자에게 열림). 끄는 방법: gitops `overlays/staging/ai`의 `ai-config`에 `APP_AI_SEAT_HOLD_ENABLED=false` 한 줄(ai만 롤링 재시작) — 끄면 Claude 도구·system·처리 경로가 좌석 잡기가 없던 때와 같다. 켜져 있어도 ai Valkey·`PUBLIC_API_BASE_URL`이 없으면 꺼진 것으로 동작 ③ `PUBLIC_API_BASE_URL`(CloudFront → gateway)로만 — 클러스터 안 주소·내부 API·RDS·기존 Valkey 금지 ④ 확인 요청에 실려 온 사용자 `Authorization` 그대로(저장·로그 금지, ai 자체 권한으로 예매하지 않음) ⑤ 결제는 화면에서(ai는 `bookingId`·`screeningId`만 주고 화면이 링크를 만든다) ⑥ 좌석은 사용자 메시지 원문에 적힌 것만(추천·자동 선택 없음, 최대 4석) ⑦ 좌석 현황·예매 결과는 Claude에 보내지 않고 캐시·세션 문맥·로그에도 좌석 이름·예매 ID를 남기지 않는다(결과 코드만) ⑧ 확인 요청 때 만든 `Idempotency-Key` 하나로만, 시간 초과·연결 오류면 같은 키로 1회 재시도 | **⏳ 팀 합의 필요**(기본 켜짐이라 합의 뒤에 머지한다). 82(사용자 챗봇 = 조회)·84(ai는 공개 API를 읽는다) 보완. booking·catalog·gateway 변경 없음. 하나라도 못 지키면 `false`로 끈다 — 끄면 즉시 기존 동작(코드 제거 없이). 확인 1번에 booking 쓰기 1~2회 + 확인 요청 1건당 공개 API 읽기 2회, 좌석 데모·부하 테스트 중에는 기존 규칙대로 `ng-ai` 0대라 좌석 잡기도 나가지 않는다(런타임 계약 9-2). `.coderabbit.yaml` Authorization 규칙·런타임 계약 6·9장 함께 수정 |

### 4. 인프라·배포

| ADR | 결론 | 비고 |
| --- | --- | --- |
| 7 · 34 | (⚠️AI 86 보완: `ng-ai` 추가) EKS On-Demand **t3.xlarge 2대**(ng-a 1 · ng-c 1 — 11/14 t3.large에서 변경: 4.2.1 선점 p95가 파드 CPU에 묶여, 부족하면 한 단계씩 올림. `ng-ai`는 t3.large 그대로, 변수 분리). 환경은 **staging 하나**. 평일 밤·주말 노드 0대(월~금 08:23 켜기·23:07 끄기 KST, 3.1.14 — 실행은 EventBridge Scheduler, GitHub Actions는 백업·수동), 데모 주간 11/16~20 24시간. 비용 경보 Budgets 월·일 | 노션 ADR-7 제목 "3대"는 이전 값. 🆕 **→ 122로 보완**(Karpenter·AZ 분산) |
| 8 · 35 · 58 | Kustomize + ArgoCD. booking·gateway만 블루/그린(5xx 1%·결제 timeout/error 5% 롤백, scaleDown 60초), 나머지 롤링. 프로브·자원·HPA·메트릭·Secret·NetworkPolicy는 `docs/service-runtime-contract.md` | 동시 블루/그린 금지 |
| 9 · 31 | CloudFront + S3(OAC), 기본 도메인 HTTPS, `/api/*` → ALB, CORS 없음. 사용자 도메인은 **선택으로 추가**(3.1.23 — 기본 도메인은 그대로, Route 53 등록은 Terraform 밖, `var.custom_domain`, apex만. CloudFront → ALB 구간은 기본 HTTP — HTTPS 원본은 **발표 뒤 선택 적용**: `var.api_origin_https` `off`(기본) → `prepare`(원본 이름 `origin.<도메인>`·ap-northeast-2 ACM 인증서·ALB SG 443) → gitops Ingress 443 리스너 → `on`(CloudFront 원본 `https-only`), 런북 7-8) | 31의 httpOnly 쿠키는 37·63으로 대체. 🆕 124에서 `api_origin_https` **`on`** |
| 78 | (⚠️AI 86 보완: gateway 5xx 쿼리에 `uri!~"/ai.*"`) 블루/그린 분석 트래픽: 분석 중 AnalysisTemplate Job(k6, 조회만)이 `{svc}-preview`로 직접 요청, `/actuator` 제외 요청 ≥ 20건/분을 같이 판정(트래픽 0 = 실패). 실패하면 abort(전환 없음) → gitops 태그 revert PR. 실패 버전 시연은 gitops 설정 PR로 의존 서비스 주소를 깨뜨리는 방식 | ⏳ 남궁일 확인(결제 경로 트래픽·시연 방식) |
| 10 | 관측은 Prometheus·Grafana, 비밀 값은 Terraform이 만드는 K8s Secret | ~~External Secrets는 확장~~ 🆕 **→ 123으로 대체**(ESO). Loki는 113으로 보완, 121로 확장 |
| ⚠️AI 86 | AI 격리: 전용 노드그룹 **`ng-ai`**(t3.large 1대, 라벨·taint `cinema/workload=ai`, 평소 desired 0, 스케줄 대상 제외) · 별도 Application **`cinema-ai`**(`argocd_apps.tf`, 다른 Application 수정 없음) · gateway 플래그 `APP_AI_ENABLED`(꺼지면 `/api/ai/**` 404) · 분석 쿼리 `/ai` 제외 · `base/ai/networkpolicy.yaml`만 신규(gateway·monitoring → ai) · ai는 클러스터 안 gateway를 부르지 않고 CloudFront 공개 주소 사용 | 7·34·58·69·78 보완 |
| 36 · 39 | AWS 아키텍처 = 위 결정들의 합. 노션 ADR-36(10/18 v3)은 이전 스냅샷 | |
| 46 | GitHub Actions → AWS OIDC. 역할 `cinema-gha-ecr-push`·`cinema-gha-tf-plan`·`cinema-gha-frontend-deploy`·`cinema-gha-cluster-schedule`(3.1.14). CI는 apply 안 함 | `infra/terraform/global` |
| 47 | 팀 접근: IAM 역할 + MFA(Identity Center 미사용). `cinema-admin` = 김종식 + **백업 남궁일**, `cinema-developer` = 전원. 프로필 `seatsac-user`·`cinema-dev`·`cinema-admin` | 회의록3 |
| 74 | ⚠️ `SeatSAC-gitops` **Public**(GitHub 무료 플랜은 Private 레포에 ruleset을 적용하지 않음). 계정 ID·엔드포인트는 gitops에 두지 않는다: 엔드포인트 = Terraform ConfigMap `cinema-infra-endpoints` + `${키}`, ECR 레지스트리 = Terraform이 만드는 서비스 ArgoCD Application의 `kustomize.images`, gitops에는 태그만. ArgoCD는 HTTPS로 읽음(자격 증명 없음) | 10/25. 대안: Private + ruleset 없이 운영(직접 push 위험), 유료 플랜 |
| 113 (가번호) | 로그 저장소 **Loki**(SingleBinary 1개, 청크·인덱스 S3 `cinema-staging-loki-{계정ID}` — EBS CSI가 없어 PVC 대신 S3, Pod Identity `cinema-staging-loki-pod`, 보존 7일·수명 주기 14일) + 수집 **Alloy**(파드 1개가 Kubernetes API로 `cinema-staging` 파드 로그만 읽음 — DaemonSet·hostPath 없음, Role 범위, gateway `clientIp`는 저장 전 `***`). 라벨은 `namespace`·`app`·`container`·`pod`만(traceId·userId는 `\| json`). 조회는 NetworkPolicy로 Grafana·Alloy만. 로컬 디스크가 emptyDir라 파드 교체 때 삭제 대기 청크는 S3 수명 주기(14일)가 지운다. 버킷 이름은 계정 ID가 들어가 Terraform ConfigMap `monitoring/loki-storage` → Loki `-config.expand-env`. Grafana 데이터소스 uid `loki`. 알림 Lambda 로그는 CloudWatch 그대로. **발표 전(11/18~19) 적용** — 시연·측정·녹화가 없는 시간에, 머지 마감(11/20 07:00) 전. 새 파드 2개는 requests 합 250m·640Mi로 작고 서비스 경로 밖이다. 이미 잰 p95·HPA는 Loki 없는 환경 기준으로 그대로 둔다(런북 6-J) | 10 보완. 노션 등록 후 번호 확정 |

### 5. 인증·화면·코드·프로세스

| ADR | 결론 | 비고 |
| --- | --- | --- |
| 37 | Cognito 사용자 풀(Hosted UI, 코드 + PKCE), Gateway가 Access Token을 JWKS로 검증 → `X-User-Id`(= sub)·`X-User-Role` | 11/14 관리자 역할 제거: `X-User-Role`은 항상 `USER`, `/api/admin/**`·관리자 API·화면 없음(Cognito `ADMIN` 그룹은 쓰지 않음). ADR 번호는 노션 등록 후. 🆕 **→ 118·119로 보완**: Cognito 그룹 `VIP`(gateway `X-User-Grade`)·`ADMIN`(ops-api `/ops`만, gateway `/api/admin/**`는 계속 없음) |
| 63 | 화면 토큰 저장은 **메모리**(`InMemoryWebStorage`), localStorage 금지. 새로고침은 Cognito 세션으로 재발급 | |
| 56 | 공통 코드: 같은 레포 공유 모듈(`libs/common-*` + `build-logic`), 패키지 `com.seatsac.{service}`·`com.seatsac.common.{core,web,test}`. `libs/` 변경 승인 2명 | 최종 결정 21번 "복사" 대체 |
| 48 · 64 | 레포 `SeatSAC`·`SeatSAC-gitops`, 브랜치 `{type}/{기능ID 또는 WBS ID}-{설명}`, ~~데모 주간 11/16~20 머지 금지~~(11/14 삭제 — QA·피드백 반영, 시연·측정 중에는 머지하지 않음. 발표 당일 마감 booking·gateway 11/20 05:00·그 밖 07:00), 태그 rc1 11/6·rc2 11/13·v1.0.0 11/20(마감 뒤), Lambda Python, 이슈는 노션 WBS만 (⚠️AI `services/ai` 다시 생김 — 82) | `docs/repo-workflow.md` |
| ⚠️AI 87 | AI 일정·머지: **rc1(11/6)** gateway 라우트(플래그 off)·분석 쿼리 제외 머지, ai 인프라·골격은 플래그 off로 staging 배포 가능 · **M6(11/9~13)** `services/ai/**`·`contracts/openapi/ai*`·gitops `base/ai`·`overlays/staging/ai`만 머지 허용(gateway·booking·`libs/`·Terraform 금지), **테스트 준비 예외(11/10 일정 변경)** `tests/k6/**`·`docs/**`·gitops `base/*/networkpolicy.yaml` 테스트 Job 허용 규칙 추가는 M6에도 머지(조건: PR 본문에 「M6 예외(테스트 준비)」와 이유, 스크럼 공유, 테스트 실행 중이 아닐 때), **AI 화면 예외(11/12)** 3.3.7 챗봇 화면(전체 허용 `frontend/src/entities/ai-chat/**`·`frontend/src/features/ai-chat/**`(새 슬라이스)·`frontend/mock/**`·`frontend/README.md`(개발용 목·문서, 빌드에 들어가지 않음) + 기존 파일 **추가만**: `frontend/src/widgets/page-layout/ui/PageLayout.tsx` Dock 배치·`frontend/src/shared/ui/icon/Icon.tsx` 아이콘 경로·`frontend/src/shared/api/http.ts` `Retry-After` 읽기(기존 오류 처리 그대로, 테스트 포함)·`frontend/src/app/routes/router.tsx` 주석)도 M6에 머지 — frontend는 S3 + CloudFront 배포라 클러스터·블루/그린과 무관하고 화면은 챗봇 창을 열 때만 `/api/ai/**`를 부른다(런북 6-G-5). 조건은 테스트 준비 예외와 같음(PR 본문 「M6 예외(AI 화면)」), 그 밖의 `frontend/**`는 금지 그대로, 테스트는 `ng-ai` 0·플래그 off, ai 검증은 로컬 Compose + 개발 키 · **rc2(11/13)** M6 테스트 뒤 플래그 켜기(ai 코드 동결·데모 주간 머지 금지는 11/14 삭제, 64와 같이) | 64 보완 |
| 49 | 공통 문서 6종 채택, 합본 `Claude.md`를 레포 루트에 (노션과 다르면 레포 우선, 수정은 PR) | 65는 49로 통합(폐기) |

### 6. 🆕 MSA 고도화 (11/23, 노션 ADR-114~125)

| ADR | 결론 | 비고 |
| --- | --- | --- |
| 114 | 범위: 기간 11/23~27(4명 — 11/23~25 구현, 11/26 통합 테스트·비교 측정, 11/27 통합 시연·리허설), 목표 = 서비스별 DB·비동기 saga·장애 격리·관리자 운영. 하루 비용 상한 $50, 못 끝내면 새 계정으로 이어서(125). 순서는 노션 「MSA 고도화 (WBS)」 「순서」 열 | 52 보완 |
| 115 | **서비스별 RDS 인스턴스** `cinema-staging-{svc}-db` 6개(booking small·나머지 micro, ai 외 Multi-AZ), `DB_HOST_{SVC}`, 인스턴스별 db-bootstrap | 3·33·61 대체 |
| 116 | 체크아웃 **오케스트레이션 saga**(booking이 오케스트레이터): 새 리소스 `/bookings/{id}/checkout`(202 + 폴링), 쿠폰 예약 → 결제 준비 → 확인 → 확정, 실패는 역순 보상, ~~단계 기한 30초~~ 🆕 단계 기한 10초(돈이 움직이기 전 단계만 — 결제 확인은 보상 없이 재확인, 선점 만료 뒤에는 예매 상태를 따름), 쿠폰 거절은 보상 없이 `COUPON_REJECTED`(예매·좌석 유지, 다시 시작 가능). 기존 `/payments`·`/pay`는 유지, 플래그 `APP_CHECKOUT_SAGA_ENABLED`(기본 off)·화면 `VITE_CHECKOUT_MODE` | 동기 경로와 비교 시연 |
| 117 | 메시징 **SNS(이벤트) + SQS(명령·결과)**, 큐마다 DLQ 3회, 아웃박스 + inbox(`processed_message`), `correlationId` = checkoutId. 파드가 SQS 직접 소비, Lambda는 외부 작업만 | 22·23 보완, 30 유지 |
| 118 | **coupon 서비스**(Java, `coupon_db`): VIP 선착순 쿠폰, 재고 Valkey DECR + DB 유일 제약, VIP는 Cognito 그룹 → gateway `X-User-Grade` | 37 보완 |
| 119 | **ops-api**(Python, `/ops/*` CloudFront → ALB, gateway 우회): Cognito `ADMIN` 그룹만, 상태·정지/가동(replicas 0 ↔ 원래 값, ArgoCD `ignoreDifferences`)·리포트. 감사 로그 | 37 보완. GitOps 예외(replicas만) |
| 120 | ai는 자기 **`ai_db`** 복제본(catalog 이벤트 + 백필 + 10분 대조)과 `unmet_demand`, `APP_AI_DATA_SOURCE=snapshot\|db`, ops-api용 내부 통계 API | 84 보완 |
| 121 | 리포트: 로그 `errorCategory` → Loki → S3 `cinema-staging-reports-*`(1시간 CronJob) → Glue → Athena → CSV(presigned 5분) | 113 확장 |
| 122 | 가용성: NAT AZ별 2개, Multi-AZ RDS·Valkey 복제본, 핵심 서비스 replicas 2 + topologySpread, **Karpenter**(t3 계열 On-Demand) | 7·34 보완 |
| 123 | **External Secrets**: Secrets Manager `cinema/staging/{svc}` → ExternalSecret(sync wave -3), Pod Identity `cinema-staging-external-secrets-pod`(conventions 9장 이름 규칙 `cinema-staging-{대상}-pod`) | 10 대체 |
| 124 | ⏳ 보안: WAF(관리형 규칙 + IP 요청 제한), gateway 본문 64KB(413 `PAYLOAD_TOO_LARGE`), `SECURITY` 로그, 원본 HTTPS `on` | 검토 중. 62 재검토 |
| 125 | 새 AWS 계정 이전: 기존 계정 리소스를 그대로 두고 Terraform을 새 계정에 다시 apply(bootstrap → global → staging → staging-k8s), 도메인은 Route 53 등록 이전 + 새 호스팅 영역, ECR 이미지는 CI 재빌드 | 이전할 때만 |
| 126 | ai 추가 고도화 두 가지. **이용 안내 RAG**: 사이트 이용 안내 문서만, 파드 메모리 색인, 플래그 `APP_AI_RAG_ENABLED`(기본 off), 응답 `path: RAG`·`sources`. **수요 → 백로그 워크플로우**(LangGraph): 통계 API 응답 → 묶기 → 기준 미달은 건너뜀 → 제안서 초안 → 사람 승인 → 파일. 로컬 실행, 클러스터 배포 없음, 코드·PR 생성 없음 | 120 보완(노션 ADR-120에 표시). WBS 6.4.7·6.4.8(6.4.1~6.4.4·6.4.6은 그대로) |

---

# 6. 레포·브랜치·PR·CI (`docs/repo-workflow.md`)

> 기준 2026-10-22 (회의록2 결정 반영, ADR-49~64). 03b 레포·브랜치·PR 개정 확정본 + 08 공통 코드(B안, ADR-56) + 브랜치 WBS ID(ADR-48) + 레포·일정(ADR-64) + 2.2.7 계약 CI.
> 전제: staging 하나가 곧 데모 환경이라 **main 머지 = 배포**다(3.1.4부터 `build-push`가 main 머지마다 자동으로 돈다).
> ⚠️AI 11/5 AI 재도입(ADR-82·86·87): `services/ai`(Python) 추가, AI 전용 머지 기간 규칙은 2장 「⚠️AI 일정·머지」.
> 🆕 11/23 MSA 고도화(노션 ADR-114~125): `services/coupon`(Java)·`services/ops-api`(Python) 추가, 11/23~27 머지 규칙은 2장 「🆕 MSA 고도화 일정·머지」.

---

### 1. 레포

| 레포 | 공개 | 내용 |
| --- | --- | --- |
| `sesac-mzc-final-2/SeatSAC` (앱, 03b의 `cinema`) | 개발 중 Private → 발표 후 Public | 서비스, frontend, 계약, Terraform, Lambda, 테스트, 문서 |
| `SeatSAC-gitops` | ⚠️ **Public** (무료 플랜 ruleset, ADR-74) — 계정 ID·엔드포인트·키 금지(`gitops-validate`가 막음) | Kustomize 매니페스트, 플랫폼 ArgoCD Application. CI 앱 봇이 이미지 태그만 커밋. 서비스 Application·엔드포인트는 Terraform `envs/staging-k8s` |
| `lotte-seed` | **항상 Private** | 수집 스크립트, 원본 스냅샷, 좌석 배치·편성 시드 SQL |

```
SeatSAC/
├── settings.gradle.kts · gradle/libs.versions.toml · build-logic/   빌드 설정 공통 (08)
├── libs/common-core · common-web · common-test                     런타임 규약만 (오류·traceId·헤더·로그·내부 클라이언트)
├── services/{gateway,catalog,booking,user,payment}/                각 Dockerfile, db/migration
├── services/ai/                                                    ⚠️AI Python(FastAPI) — pyproject.toml·uv.lock·Dockerfile·src/seatsac_ai·tests, .env(커밋 금지), 🆕 `backlog/`(6.4.8 워크플로우 — `eval/`처럼 이미지 밖)
├── services/coupon/                                                🆕 Java(Spring) — 다른 Java 서비스와 같은 골격, db/migration
├── services/ops-api/                                               🆕 Python(FastAPI) — ai와 같은 골격(⏳ 6.0.1 확정), Flyway 없음
├── frontend/                                                       React(Vite) → S3
├── contracts/{openapi,events,schemas,tools}/                       계약 + 계약 CI
├── lambdas/{notification,cognito-presignup}/                       Python
├── infra/terraform/{bootstrap,global,envs/staging,envs/staging-k8s}/ · infra/compose/
├── tests/k6/
├── docs/   conventions.md · contracts.md · service-runtime-contract.md · data-model.md · decisions.md · repo-workflow.md · runbooks/
└── .github/ workflows/ · scripts/(서비스 고르기·Trivy, pr-check·build-push 공용) · CODEOWNERS · pull_request_template.md

SeatSAC-gitops/
├── apps/staging/          App of Apps 대상(project·플랫폼 2·cinema-common). root 앱·서비스 Application 5개는 SeatSAC Terraform(staging-k8s argocd_apps.tf)
├── platform/              Argo Rollouts, kube-prometheus-stack values (LB Controller는 Terraform envs/staging-k8s) · 🆕 karpenter(NodePool·EC2NodeClass)·external-secrets(ClusterSecretStore)
├── base/{service}/        Deployment 또는 Rollout, Service(+preview), HPA, NetworkPolicy, PodMonitor, Flyway 훅 Job(Sync wave -1)
│                          ⚠️AI base/ai: Deployment(nodeSelector·toleration ng-ai)·Service·NetworkPolicy(gateway·monitoring → ai, 🆕 ops-api → ai 내부 통계)·PodMonitor (HPA 없음. Flyway는 🆕 `ai_db`를 쓰기 전까지 없음 — 런타임 계약 9-1)
│                          🆕 base/coupon: Deployment·Service·HPA·NetworkPolicy(gateway·ops-api·monitoring → coupon — ops-api는 내부 이벤트 생성·목록)·PodMonitor·Flyway Job·ExternalSecret
│                          🆕 base/ops-api: Deployment·Service·Ingress(IngressGroup `cinema`, `/ops`)·Role·RoleBinding·ServiceAccount·NetworkPolicy·PodMonitor
│                          🆕 base/{svc}/externalsecret.yaml(sync wave -3) — Secret 매니페스트 대신
├── base/common/           앱 namespace 인바운드 기본 거부 + analysis
├── overlays/staging/{service}/  ConfigMap {service}-config(엔드포인트는 ${키}) + image-tag/(newTag만)
├── components/rollout/    kustomize가 Rollout 안 ConfigMap 해시 이름을 바꾸게 하는 설정
├── analysis/              AnalysisTemplate (booking·gateway)
└── .github/               CODEOWNERS · PR 템플릿 · gitops-validate
```

- `lambdas/daily-stats/`는 10/22 삭제(ADR-51). ⚠️AI `services/ai/`는 10/22 삭제(ADR-50) 후 11/5 **Python으로 다시 만든다**(ADR-82). 서비스 Application은 Terraform `argocd_apps.tf`에 `ai` → `cinema-ai`(gitops `apps/`에는 두지 않음)
- Secret 매니페스트는 gitops에 없다(Terraform이 생성, Deployment는 이름으로만 참조)

### 2. 브랜치·커밋

| 항목 | 규칙 |
| --- | --- |
| 기본 브랜치 | `main` 보호(ruleset), 직접 push 금지. **main 머지 = staging 자동 배포**(`services/**`가 바뀐 머지 → `build-push`, 3.1.4) |
| 브랜치 이름 | `{type}/{기능ID}-{설명}`. 기능 ID가 없으면 **WBS ID** (ADR-48) |
| type | `feat` · `fix` · `refactor` · `test` · `docs` · `infra` · `ci` · `chore` |
| 예 | `feat/BKG-02-seat-hold`, `fix/CAT-05-cache-key`, `infra/3.1.3-rds-valkey`, `ci/2.2.7-contract-ci`, `docs/ADR-47`, 🆕 `infra/6.1.1-rds-per-service`, `feat/6.2.1-checkout-saga`, `docs/6.0.1-msa-upgrade` |
| 수명 | 최대 3일. 길어지면 기능 플래그로 나눠 머지 |
| 머지 | Squash merge |
| 커밋 메시지 | Conventional Commits + 기능 ID 또는 WBS ID: `feat(booking): BKG-02 좌석 선점 Lua 스크립트`, `infra(terraform): 3.1.3 RDS·Valkey` |
| 릴리스 태그 | `rc1` 11/6(M5), `rc2` 11/13(M6), `v1.0.0` 11/20 07:00 머지 마감 뒤 main 마지막 커밋 |
| 동결 | rc2 동결·데모 주간 머지 금지는 **없음**(11/14 — QA·피드백 반영이 남아 뺐다). 평소 PR 규칙(승인 1명) 그대로. 단 main 머지 = 배포이므로 데모 시연·측정이 진행 중인 시간에는 머지하지 않는다(스크럼에서 시간 공유). **발표(11/20 10시 시작, 우리 순서 약 11시) 머지 마감**: booking·gateway(블루/그린 — 분석 약 6분, 실패 시 abort·revert까지 30분 이상)는 **11/20 05:00**, 그 밖의 변경은 **11/20 07:00**까지 머지. 마감 뒤에는 기능·수정 머지를 하지 않는다(**발표가 끝나는 11/20까지만** — 11/23부터는 아래 「🆕 MSA 고도화 일정·머지」 행을 따른다). 복구 목적의 revert만 예외로 머지한다(승인 1명, 스크럼 공유): 서비스는 `SeatSAC-gitops` 태그 revert PR(4장 롤백), frontend는 SeatSAC main revert PR → `frontend-deploy`가 자동으로 돌고 안 돌면 수동 실행, Terraform은 revert PR 뒤 김종식(또는 백업 관리자)이 로컬에서 `cinema-admin`으로 apply. tfstate 버전 복원은 최후 수단 |
| 🆕 MSA 고도화 일정·머지 (11/23~27, ADR-114) | 순서는 노션 「MSA 고도화 (WBS)」 「순서」 열. ① **계약 PR 6.0.1 먼저**(API 명세서 v1.5 → `contracts/`, 구현 PR보다 앞). ② 기능은 플래그 off로 머지(`APP_CHECKOUT_SAGA_ENABLED`·`APP_BOOKING_HISTORY_MODE=sync`·`APP_AI_DATA_SOURCE=snapshot`·`APP_AI_RAG_ENABLED=false`) → 켜기는 gitops PR 1회씩. ③ **블루/그린 동시 금지 그대로**(booking·gateway), Terraform apply는 김종식이 한 번에 하나 — RDS 이전(서비스 하나씩, 덤프 시작부터 새 인스턴스 전환·확인이 끝날 때까지 쓰기 멈춤 — data-model 7-1)·NAT·Karpenter는 시연·측정이 없는 시간에 스크럼 공유. ④ 부하·장애 테스트를 두 개 동시에 돌리지 않는다. ⑤ ops-api 정지/가동은 시연 중에만, 끝나면 「가동」으로 되돌린다(Git과 replicas가 다른 상태로 두지 않음). ⑥ 하루 비용 $50(Budgets 일 경보)을 넘기면 밤에 노드·NAT를 끈다 |
| ⚠️AI 일정·머지 (ADR-87) | **rc1(11/6)**: gateway `/api/ai/**` 라우트(플래그 off)·gateway 분석 쿼리 `/ai` 제외 머지 완료. ai 인프라·골격은 플래그 off로 staging 배포 가능 · **M6(11/9~13)**: AI 기능은 계속 머지할 수 있다 — 허용 경로는 `services/ai/**`·`contracts/openapi/ai*`·gitops `base/ai/**`·`overlays/staging/ai/**`만. gateway·booking·`libs/`·Terraform 변경은 이 기간 금지(부하·전환 테스트 환경 보호). **테스트 준비 예외(11/10 일정 변경)**: 4.2.1 부하 테스트 준비가 M6로 밀려 테스트 도구·문서는 M6에도 머지할 수 있다 — `tests/k6/**`, `docs/**`(런북·문서, `CLAUDE.md` 재생성 포함), gitops `base/*/networkpolicy.yaml`의 **테스트 Job 라벨 허용 규칙 추가만**(예: `k6-loadtest` → gateway. Rollout·Deployment 템플릿은 그대로라 블루/그린·재시작이 없다). 조건: PR 본문에 「M6 예외(테스트 준비)」와 이유, 스크럼 공유, 4.2.1·4.2.2 테스트가 돌지 않는 시간에 머지. 서비스 코드·Terraform 금지는 그대로. **AI 화면 예외(11/12)**: 3.3.7 챗봇 화면도 M6에 머지할 수 있다 — 이 경로는 전체 허용: `frontend/src/entities/ai-chat/**`·`frontend/src/features/ai-chat/**`(새 슬라이스), `frontend/mock/**`·`frontend/README.md`(개발용 목·문서, 빌드에 들어가지 않음). 기존 파일은 **추가만**(기존 동작 그대로): `frontend/src/widgets/page-layout/ui/PageLayout.tsx`(Dock import·배치), `frontend/src/shared/ui/icon/Icon.tsx`(아이콘 경로), `frontend/src/shared/api/http.ts`(`Retry-After`를 `ApiError`에 담기 — 기존 오류 처리 그대로, `http.test.ts` 포함), `frontend/src/app/routes/router.tsx`(주석만). `http.ts`는 모든 화면이 쓰는 클라이언트라 `pr-check` `frontend`(vitest) 통과를 조건에 넣는다. frontend는 S3 + CloudFront 배포라 클러스터 파드·블루/그린·노드와 무관하고, 화면은 챗봇 창을 열 때만 `/api/ai/**`를 부른다(런북 6-G-5) → 로그인·메인 진입 때 ai 트래픽이 없어 4.2.1·4.2.2·좌석 데모 측정에 섞이지 않는다. 조건: PR 본문에 「M6 예외(AI 화면)」와 이유, 스크럼 공유, 테스트가 돌지 않는 시간에 머지(`frontend-deploy`는 S3 업로드·CloudFront 무효화만). 그 밖의 `frontend/**`는 금지 그대로. 테스트(4.2.1·4.2.2)는 `ng-ai` desired 0·플래그 off. ai 검증은 로컬 Compose + **개발 전용 Claude API 키**(`.env`), staging 확인은 테스트가 돌지 않는 시간에 `kubectl port-forward svc/ai`(스크럼에서 시간 공유) · **rc2(11/13)**: M6 테스트가 끝난 뒤 gateway `APP_AI_ENABLED=true` gitops PR 1회. ai 코드 동결은 없다(11/14 삭제) · **데모 주간(11/16~20)**: 시연·측정 중이 아닐 때 머지, 발표 당일 마감 11/20 07:00(ADR-64 — ai는 롤링이라 07:00, gateway 플래그 변경은 블루/그린이라 05:00). 11/17 AI 데모는 `ng-ai` 수동 기동(머지 아님) |

### 3. PR

| 항목 | 규칙 |
| --- | --- |
| 승인 | 1명 (11/13 이후에도 1명 — 11/13 결정). `libs/`·`build-logic/` 변경은 2명(김종식 + 영향받는 서비스 담당) |
| 필수 검사 (ruleset) | `java`·`gitleaks`·`image`·`frontend`(pr-check), `lint (OpenAPI · JSON Schema · 예시)`·`하위 호환 (base 대비)`(contract-check). 여섯 검사 모두 **모든 PR에서 실행**된다(contract-check는 `paths` 필터 없이 `변경 감지` job이 계약 변경이 없으면 두 job을 건너뜀 = 성공. `image`도 같은 방식: 이미지 대상이 없으면 건너뜀 = 성공. `frontend`도 같은 방식: `frontend 변경 감지` job이 `frontend/**` 변경이 없으면 건너뜀 = 성공). `infra-plan`은 `paths` 필터가 있어 필수로 걸지 않는다 — `infra/terraform/**` PR은 리뷰어가 plan 코멘트를 확인. `java`는 Gradle wrapper·서비스 골격이 들어온 뒤 필수로 추가. ⚠️AI `python`(pr-check)도 같은 「변경 없으면 건너뜀」 방식으로 필수 검사에 추가 → 일곱 검사(워크플로가 main에 들어간 뒤 ruleset에 `python`을 추가한다 — 그 전에 추가하면 job이 없어 모든 PR이 대기) |
| 크기 | 400줄 이하 권장(생성 코드·lock 제외) |
| 리뷰 응답 | 24시간 이내(주말 제외) |
| 머지 후 | 작성자가 ArgoCD Synced·Healthy(또는 frontend 배포 완료)까지 확인 — `kubectl -n argocd get applications` 또는 `kubectl -n argocd port-forward svc/argocd-server 8080:80`(읽기 전용 화면) |
| 템플릿 | 변경 요약 · WBS ID·기능 ID · 테스트 방법 · 스크린샷(화면) · **계약 변경 여부** · **DB 마이그레이션 단계(확장·전환·정리)** · **배포 영향(env·Secret·gitops·CloudFront 캐시)** |
| 계약 깨는 변경 | 구현 전 설계 변경만 라벨 `contract-break-ok` + CODEOWNERS 승인, PR 본문에 이유 |

**리뷰 체크리스트**

- DB: 컬럼 삭제·이름 변경은 다음 배포로(두 버전이 같은 DB를 쓴다)
- 계약: 필드 추가만. 삭제·타입 변경은 새 엔드포인트·`.v2`
- 이벤트: 소비자는 모르는 필드를 무시한다
- booking 만료·릴레이 로직: preview 파드도 돌므로 이전 버전과 호환
- 설정 이름이 `docs/service-runtime-contract.md` 3장과 같은지

**CODEOWNERS** (`.github/CODEOWNERS`, GitHub 아이디는 실제 값으로 확인)

| 경로 | 담당 |
| --- | --- |
| `services/gateway·catalog/` | 한채연 (🆕 + 승인용 남궁일) |
| 🆕 `services/user/` | 김종식 · 남궁일 (11/24 한채연에서, #186) |
| `services/booking·payment/` | 남궁일 |
| ⚠️AI `services/ai/`, `contracts/openapi/ai*` | 김서현 (계약은 + 한채연) |
| 🆕 `services/coupon/`, `contracts/openapi/coupon*`·`openapi/internal/coupon*`·`events/coupon*` | 김종식 · 남궁일 (11/24 한채연에서, #178) |
| 🆕 `contracts/openapi/user*` | 김종식 · 한채연 (11/24) |
| 🆕 `services/ops-api/`, `contracts/openapi/ops*` | 김종식 (계약은 + 한채연 · 남궁일) |
| 🆕 `contracts/events/payment.*`·`screening.*`·`movie.*`·`booking.*` | 아래 `contracts/` 규칙 (+ 소비 담당 리뷰) |
| `frontend/` | 한채연 · 남궁일 |
| `contracts/` | 한채연 · 남궁일 |
| `contracts/tools/`, `contracts/redocly.yaml`, `build-logic/`, `gradle/`, `lambdas/`, `infra/`, `.github/`, `tests/k6/` | 김종식 |
| `libs/` | 김종식 · 한채연 · 남궁일 |

### 4. CI/CD 워크플로

| 워크플로 | 트리거 | 하는 일 |
| --- | --- | --- |
| `pr-check` | PR | ⚠️AI `python`: `services/ai/**`가 바뀐 PR만 Python 3.12 → `uv sync --locked`(uv 버전은 `services/ai/Dockerfile`의 `ARG UV_VERSION`) → `ruff check` → `pytest`(Claude API는 목, 키·AWS 권한 없음, 바뀌지 않으면 건너뜀 = 성공 — 필수 검사로 추가). `image`의 서비스 고르기(`changed-services.sh`)는 Java 5개 + `ai`이고, **`services/{svc}/build.gradle.kts`가 없는 ai는 Gradle `bootJar` 없이 `docker build services/ai`**. ai 이미지는 PR에서 **실제로 띄워 본다**(`--read-only --tmpfs /tmp`, liveness 200, readiness HTTP 상태 200 또는 503만 확인(스냅샷 적재·ai Valkey 연결 성공은 보장하지 않음), `http_server_requests_seconds{application="ai"}`, 헤더 없는 `/ai/presets` → 401 + `error.v1.json` 형식). `services/{svc}/Dockerfile`이 아직 없는 서비스는 고르지 않는다. `java`: 빌드·테스트(Testcontainers MySQL 8.4·Valkey 8.2·ElasticMQ) · `gitleaks` · `image`: 바뀐 서비스만 bootJar → `docker build` → 비루트 확인 → Trivy(push 안 함, AWS 권한 없음. `build-logic/`·`gradle/`·이미지 스크립트·워크플로가 바뀌면 5개 전부) · `lambda`: `lambdas/notification` 단위 테스트(Python 3.12 `unittest`, 의존성·AWS 권한 없음, 필수 검사 아님) · `frontend`: `frontend/**`가 바뀐 PR만 node 24 → `npm ci` → `npm run check`(tsc -b · eslint · steiger FSD · vitest) → `npm run build`(`dist/index.html` 확인). frontend-deploy와 같은 node·npm 설정, AWS 권한 없음. 실패하면 같은 PR 브랜치에 고쳐서 push → 자동 재실행. 2단계: springdoc 생성본 vs 계약 diff |
| `contract-check` | PR (항상, 변경 없으면 건너뜀) | Redocly lint, JSON Schema·예시 검증, oasdiff·스키마 하위 호환 |
| `build-push` | main 머지(`services/**`, 바뀐 서비스만) + 수동(`services`=all 또는 목록, main에서만) | bootJar → x86 이미지 → Trivy(CRITICAL·수정 가능이면 실패) → ECR `cinema/{svc}:{sha7}`(OIDC `cinema-gha-ecr-push`, 같은 태그 있으면 건너뜀) → `SeatSAC-gitops` `overlays/staging/{svc}/image-tag`의 **`newTag`만** yq로 GitHub App `seatsac-build-push`(gitops에만 설치, Contents 쓰기) 토큰으로 main에 커밋(조직 정책상 Deploy key 불가). 레지스트리(계정 ID)는 gitops에 쓰지 않는다(Public, ADR-74) — Terraform ArgoCD Application이 붙인다. booking·gateway가 같이 바뀌면 booking만(블루/그린 동시 금지). ⚠️AI ai는 `docker build services/ai`(Gradle 없음) → ECR `cinema/ai` → `overlays/staging/ai/image-tag`. 🆕 coupon은 Java 5개와 같은 Gradle 경로, ops-api는 ai와 같은 `docker build services/ops-api`(저장소 변수 `OPS_BUILD_PUSH_ENABLED`, ECR `cinema/ops-api`·gitops `overlays/staging/ops-api`가 생긴 뒤 켬). 저장소 변수 **`AI_BUILD_PUSH_ENABLED`가 `true`가 아니면 ai는 건너뛴다**(경고) — ECR `cinema/ai`(3.1.18)·gitops `overlays/staging/ai`(3.1.20)가 생긴 뒤 김종식이 켠다(런북 6-G-1). 그 전에는 ai 머지가 빌드·배포 없이 끝난다 |
| `frontend-deploy` | main 머지(`frontend/**`) + 수동 | Vite 빌드 → S3(`/assets/*` 1년 immutable, `index.html` no-cache) → 무효화 |
| `lambda-deploy` | **없음(현재)** | Lambda 배포는 Terraform `archive_file`(3.1.16 Pre sign-up, 3.1.8 알림)이 코드를 묶어 올린다. 코드가 바뀌면 `infra-plan`에 함수 update가 보이고 apply는 김종식이 로컬에서 `cinema-admin`으로. 별칭(alias)은 쓰지 않는다. 필요해지면 이 워크플로를 추가(CI에 배포 권한이 필요) |
| `infra-plan` | PR(`infra/terraform/**`·`lambdas/**`, 필수 검사 아님) | `terraform plan`(OIDC `cinema-gha-tf-plan`) → PR 코멘트. **apply는 김종식이 로컬에서 `cinema-admin`으로** |
| `cluster-schedule` | **백업** 월~금 08:53 켜기 · 23:37·00:37 끄기(KST) + 수동(up·down·status — `up`은 `ng-ai`를 켜지 않고 `down`은 `ng-ai`까지 끈다) | 정시 켜기·끄기(08:23·23:07)는 **AWS EventBridge Scheduler**(`envs/staging/cluster_schedule.tf`)가 한다 — GitHub schedule은 몇 시간씩 늦거나 빠져서(10/23·10/26) 이 워크플로는 30분 뒤 백업·수동용. 백업은 늦게 도착하면(끄기 월~금 22~23시·화~토 0~5시, 켜기 월~금 7~10시 밖) 건너뛰고, 데모 주간 11/16~20·EventBridge 끄기 DISABLED(`cluster_keep_on`)면 끄기 건너뜀. 노드그룹 desired 1 ↔ 0 (OIDC `cinema-gha-cluster-schedule`), 스크립트 `infra/scripts/cluster-scale.sh` |
| `gitops-validate` (gitops 레포) | PR | `kustomize build` + kubeconform, Secret 매니페스트·계정 ID·`*.amazonaws.com`·`newName` 금지, gitleaks |

- OIDC 역할 세션 이름 `gha-${{ github.actor }}-${{ github.run_id }}`(CloudTrail에 실행자)
- 이미지는 두 번 빌드된다: PR의 `image`(검사용, 버림)와 main의 `build-push`(진짜, 태그 = main 커밋 SHA). 서비스 고르기·Trivy는 `.github/scripts/`를 같이 쓴다
- 공통 모듈(`libs/`·`build-logic/`·`gradle/`)만 바뀐 머지는 배포하지 않는다. 급하면 `build-push` 수동 실행으로 한 서비스씩
- 롤백 = `SeatSAC-gitops`에서 해당 태그 커밋을 revert하는 PR. ECR 이미지는 저장소당 최근 30개가 남는다

### 5. 지켜야 할 것

| 규칙 | 이유 |
| --- | --- |
| 비밀 값(키·비밀번호·tfstate 내용·tfvars의 민감 값)을 Git·문서·PR에 쓰지 않는다. ⚠️AI Claude API 키 포함 — 로컬은 개발 전용 키를 `services/ai/.env`에만 | 발표 후 Public 전환. gitleaks가 막는다 |
| 롯데 원본·fixture·시드는 `lotte-seed`에만 | 준수 조건(공개 금지) |
| 롯데 실제 예매 API 호출 금지, 롯데 포스터 이미지 표시 금지 | 준수 조건 |
| 사람은 ECR·frontend S3에 직접 올리지 않는다 | ECR 저장소 정책(CI 역할만 push) + 태그 불변 |
| AWS 콘솔에서 리소스를 만들지 않는다. IAM·OIDC는 `infra/terraform/global`, 나머지는 `envs/staging` | 10/21 OIDC 공급자 중복 사례 |
| `terraform destroy`로 bootstrap·global을 지우지 않는다 | tfstate·팀 접근 상실 |
| 클러스터 직접 수정 금지(selfHeal이 되돌림). 긴급 시 해당 앱 auto-sync 끄고 작업 → Git 반영 → 스크럼 공유 | GitOps |
| 팀원 평소 작업은 `cinema-dev` 프로필(MFA). `cinema-admin`은 김종식 + 백업 관리자 남궁일 | ADR-47 |
| 작업·진척은 노션 WBS만. GitHub Issues 안 씀 | 03b |
| 🆕 Secret 값은 Secrets Manager `cinema/staging/*`에만(Terraform이 만들고 ESO가 가져옴). gitops `ExternalSecret`에는 키 **이름**만, ARN·계정 ID 금지 | ADR-74·123 |
| 발표 후 Public 전환 전: gitleaks 전체 이력 스캔, 시드·원본·`.env`·tfvars 없음 확인. ⚠️AI 개발용·staging Claude API 키 폐기(Anthropic 콘솔) | — |

<노션 반영 필요> 03b 결정 칸에 "10/22 확정 + 레포 이름 SeatSAC·SeatSAC-gitops, `libs/` 유지(08 B안), contract-check 필수 검사, 브랜치 WBS ID 보완(ADR-48)", 최종 결정 21번 "공통 코드 복사" → "공유 모듈"

<노션 반영 필요> 03b에 11/23 MSA 고도화 반영: `services/coupon`·`services/ops-api`, CODEOWNERS(~~coupon 한채연~~ 🆕 coupon·user 김종식(11/24)·ops-api 김종식), 11/23~27 머지 규칙(2장)

# 결정 요약 (ADR 한 장)

> 기준 2026-10-22 (회의록2 결정 반영). 원본은 노션 「결정 기록 (ADR)」(번호 = DB 번호). 근거·대안은 노션에서 본다. 이 문서는 **지금 유효한 결론**만 한 줄씩 둔다.
> 이후 결정이 앞 결정을 덮은 경우 "→ 대체"로 표시했다. ⏳는 아직 열린 항목이다.
> ⚠️AI 11/5 AI 재도입: ADR-82~87(노션 등록 번호는 등록 후 확인). 대체·보완 관계는 각 행 비고.

---

## 1. 범위·일정·사람

| ADR | 결론 | 비고 |
| --- | --- | --- |
| 1 · 50 | ~~서비스 5개, AI 제외(10/22)~~ → **82로 대체**: 서비스 6개(ai 추가) | 26 → 83으로 대체 |
| ⚠️AI 82 | AI 재도입: **Python(FastAPI) `services/ai`, 사용자 챗봇만**(로그인 필수, `/api/ai/**`). 질문 처리 정확 일치 캐시 → 의미 캐시 → 프리셋 → NL2SQL, "내 예매"는 공개 API. 담당 **김서현**. 데모는 1차 11/16(무중단·좌석) + **2차 11/17(AI)** | 1·50 대체, 52·53 보완. 관리자 NL2SQL·추천(AI-08) 제외 |
| ⚠️AI 83 | LLM은 **Claude API(Anthropic 직접) `claude-sonnet-5-5`**, 도구 호출로 프리셋/SQL 선택, 프롬프트 캐싱. 키: staging `ai-secrets`(Terraform sensitive), 로컬 개발 전용 키 `.env`(커밋 금지). Anthropic 월 지출 한도 $20, 사용자당 동시 1·분당 10·하루 100 | 26 대체. Bedrock 안 씀(Pod Identity 불필요) |
| 52 | (⚠️AI 82 보완: 11/17 AI 2차 데모 추가) 데모는 **무중단 배포 · 실시간 좌석** 2개로 확정. 실시간 좌석 데모에 동시성 테스트(같은 좌석 100명 → 성공 1명) 포함 | 선점 전략 비교는 여유 시 |
| 45 | 선점 전략 비교(db-lock/redis): 지금은 3.2.2에 `SeatHoldStrategy` 인터페이스만, db-lock은 잠글 행·만료 처리 **설계 초안만**(남궁일). 구현·측정은 최후 순위(4.2 때 여유 시) | 새 노드 불필요 |
| 53 | 김서현은 일정에서 제외. 필요하면 개발·인프라/DevOps의 WBS 하나씩 맡긴다 → ⚠️AI **82로 보완: AI는 김서현 고정 담당**(11/2) | 4.1.2 김서현·한채연, OPS-07 코드 김종식 |
| 25 | Payment 백엔드는 남궁일, 결제 화면은 한채연 | |
| 2 | 롯데시네마 좌석 배치·하루 편성만 로컬에서 1회 수집 → 비공개 `lotte-seed` 시드 | 실시간 연동·예매 호출 없음 |
| 15 · 16 | ⏳ 스크린 위치(GetSeats `ScreenSeatInfo` 사용 가정) · 수집 방식(로컬 1회 가정) — 한채연 확인 예정 | |

## 2. 데이터·실시간

| ADR | 결론 | 비고 |
| --- | --- | --- |
| 3 · 33 · 61 | RDS **MySQL 8.4** 1대(**Single-AZ**, `db.t4g.small`, TLS 강제, 마스터 비밀번호는 RDS 관리 Secrets Manager) + 서비스별 스키마·계정 | Multi-AZ·AZ 장애 실험은 시간 남을 때 |
| 33 · 59 | ElastiCache **Valkey 8.2 `cache.t4g.micro`**, `noeviction`, `notify-keyspace-events Ex`, 메모리 80%·Evictions 경보 | 부하 테스트에서 70% 넘으면 t4g.small |
| 57 | DB 마이그레이션은 ArgoCD **Flyway 훅 Job**(`{svc}_migrator`, Sync 단계 wave -1 — ConfigMap 다음·앱 전, 실패 시 sync 중단), 앱은 Flyway 끔. 스키마·계정·Secret은 Terraform(`envs/staging-k8s`)이 클러스터 안 Job으로 생성(3.1.12) | 처음 "PreSync"였으나 첫 배포 때 ConfigMap보다 먼저 돌아 Sync wave -1로 변경(10/23) |
| 60 | 리포트 뷰 만들지 않음(catalog V2 머지 안 함, ⚠️AI 재도입 후에도 유지 — 84) · payment `PROCESSING` 30초 초과는 기동 시 `FAILED(PROCESSING_TIMEOUT)` · `pg_provider`는 `MOCK`만 | |
| 4 | 좌석 이벤트: Valkey Pub/Sub `seat-events:{회차}` → SSE, 놓치면 BKG-01 스냅샷 | 유실 허용 |
| 5 | 선점: Valkey `SET NX EX {선점 TTL}`(`APP_SEAT_HOLD_TTL_SECONDS`, 기본 300초, 30~600)(Lua로 여러 좌석 원자적) + 그 시점 RDS PENDING 예매 | |
| 11 | 한 번에 최대 4석 | |
| 19 | 멱등: `Idempotency-Key` + Valkey `idem:{userId}:{키}` 24시간 + 유일 제약(`booking_seat`, `current_payment_id`) | |
| 22 | 내부 브로커 없음: Pub/Sub + keyspace 알림 + sorted set | 만료 반영 2초 초과 시 재검토 |
| 44 | 만료: keyspace(Ex) + `hold-expiry` 1초 스캔 + RDS 10초 스캔, 조건부 UPDATE로 한 번만 | 노션 본문 "ADR-21" = 이 항목 |
| ⚠️AI 84 | AI 데이터: **ai 파드 메모리 SQLite 스냅샷**, CloudFront 공개 API로만 적재(오늘 1분·나머지 10분). RDS·기존 Valkey·리포트 뷰·`ai_readonly` 없음. 실행은 읽기 전용·5초·500행, SQL 검증 sqlglot(SQLite). 포스터·시드 원본 미포함 | 기존 MSA DB를 건드리지 않음. 60 유지 |
| ⚠️AI 85 | AI 캐시: 새 ElastiCache **`cinema-staging-ai-valkey`** `t4g.micro`(벡터 검색, `noeviction` + 모든 키 TTL — 한도 키 보호, 예약 메모리 50%). 정확 일치 + **의미 캐시**(로컬 임베딩 `paraphrase-multilingual-MiniLM-L12-v2`, 슬롯 치환 뒤 임베딩, 유사도 0.92) + 세션(사용자별 키) + 사용 한도. 개인 질문은 캐시 안 함 | 59는 기존 Valkey에만 해당(그대로) |

## 3. 서비스 간 통신·계약·결제·알림

| ADR | 결론 | 비고 |
| --- | --- | --- |
| 23 · 32 | REST 동기(RestClient + Resilience4j), 연결 1초·응답 3초(catalog→booking 300ms), 서킷 50%·10초, OpenAPI 계약 + CI | p95 초과 시 gRPC |
| 38 · 54 | 계약 기준은 API 명세서 v1.1 + 2.2.4 v0.3 정리: 비페이징 목록 `{items}`, 오류 `details` 항상 배열, Cognito 조회 실패 503 `IDENTITY_UNAVAILABLE`, 내부 회차 API `seatIds` 삭제, `GET /internal/theaters` 신설 | `docs/contracts.md` |
| 55 | 계약 강화(구현 전 일괄): `booking.confirmed` 필드 3개 필수, traceId `^[0-9a-f]{32}$`, 오류 traceId null 불가. 첫 계약 PR만 `contract-break-ok`, 라벨은 구현 시작 엔드포인트·M3 이후 금지 | |
| 62 | Gateway 요청 수 제한 미도입(`rl:` 키·429 없음) | |
| 24 | 결제는 예매당 1회, 실패하면 PAYMENT_FAILED + 좌석 해제 | 20 폐기 |
| 13 | 모의 결제: 거절도 200 + status, 카드 끝 0000 거절·1111 잔액 부족·9999 5초 지연 후 승인, `PROCESSING` | 롤백 지표에서 거절 제외 |
| 30 · 51 | 예매 완료 알림: booking만 SQS 발행(아웃박스 + 릴레이) → Lambda(로그), DLQ 3회. **OPS-08 일별 집계는 제외** | |

## 4. 인프라·배포

| ADR | 결론 | 비고 |
| --- | --- | --- |
| 7 · 34 | (⚠️AI 86 보완: `ng-ai` 추가) EKS On-Demand **t3.large 2대**(ng-a 1 · ng-c 1). 환경은 **staging 하나**. 평일 밤·주말 노드 0대(월~금 08:23 켜기·23:07 끄기 KST, 3.1.14 — 실행은 EventBridge Scheduler, GitHub Actions는 백업·수동), 데모 주간 11/16~20 24시간. 비용 경보 Budgets 월·일 | 노션 ADR-7 제목 "3대"는 이전 값 |
| 8 · 35 · 58 | Kustomize + ArgoCD. booking·gateway만 블루/그린(5xx 1%·결제 timeout/error 5% 롤백, scaleDown 60초), 나머지 롤링. 프로브·자원·HPA·메트릭·Secret·NetworkPolicy는 `docs/service-runtime-contract.md` | 동시 블루/그린 금지 |
| 9 · 31 | CloudFront + S3(OAC), 기본 도메인 HTTPS, `/api/*` → ALB, CORS 없음 | 31의 httpOnly 쿠키는 37·63으로 대체 |
| 78 | (⚠️AI 86 보완: gateway 5xx 쿼리에 `uri!~"/ai.*"`) 블루/그린 분석 트래픽: 분석 중 AnalysisTemplate Job(k6, 조회만)이 `{svc}-preview`로 직접 요청, `/actuator` 제외 요청 ≥ 20건/분을 같이 판정(트래픽 0 = 실패). 실패하면 abort(전환 없음) → gitops 태그 revert PR. 실패 버전 시연은 gitops 설정 PR로 의존 서비스 주소를 깨뜨리는 방식 | ⏳ 남궁일 확인(결제 경로 트래픽·시연 방식) |
| 10 | 관측은 Prometheus·Grafana, 비밀 값은 Terraform이 만드는 K8s Secret | External Secrets·Loki는 확장 |
| ⚠️AI 86 | AI 격리: 전용 노드그룹 **`ng-ai`**(t3.large 1대, 라벨·taint `cinema/workload=ai`, 평소 desired 0, 스케줄 대상 제외) · 별도 Application **`cinema-ai`**(`argocd_apps.tf`, 다른 Application 수정 없음) · gateway 플래그 `APP_AI_ENABLED`(꺼지면 `/api/ai/**` 404) · 분석 쿼리 `/ai` 제외 · `base/ai/networkpolicy.yaml`만 신규(gateway·monitoring → ai) · ai는 클러스터 안 gateway를 부르지 않고 CloudFront 공개 주소 사용 | 7·34·58·69·78 보완 |
| 36 · 39 | AWS 아키텍처 = 위 결정들의 합. 노션 ADR-36(10/18 v3)은 이전 스냅샷 | |
| 46 | GitHub Actions → AWS OIDC. 역할 `cinema-gha-ecr-push`·`cinema-gha-tf-plan`·`cinema-gha-frontend-deploy`·`cinema-gha-cluster-schedule`(3.1.14). CI는 apply 안 함 | `infra/terraform/global` |
| 47 | 팀 접근: IAM 역할 + MFA(Identity Center 미사용). `cinema-admin` = 김종식 + **백업 남궁일**, `cinema-developer` = 전원. 프로필 `seatsac-user`·`cinema-dev`·`cinema-admin` | 회의록3 |
| 74 | ⚠️ `SeatSAC-gitops` **Public**(GitHub 무료 플랜은 Private 레포에 ruleset을 적용하지 않음). 계정 ID·엔드포인트는 gitops에 두지 않는다: 엔드포인트 = Terraform ConfigMap `cinema-infra-endpoints` + `${키}`, ECR 레지스트리 = Terraform이 만드는 서비스 ArgoCD Application의 `kustomize.images`, gitops에는 태그만. ArgoCD는 HTTPS로 읽음(자격 증명 없음) | 10/25. 대안: Private + ruleset 없이 운영(직접 push 위험), 유료 플랜 |

## 5. 인증·화면·코드·프로세스

| ADR | 결론 | 비고 |
| --- | --- | --- |
| 37 | Cognito 사용자 풀(Hosted UI, 코드 + PKCE), Gateway가 Access Token을 JWKS로 검증 → `X-User-Id`(= sub)·`X-User-Role` | |
| 63 | 화면 토큰 저장은 **메모리**(`InMemoryWebStorage`), localStorage 금지. 새로고침은 Cognito 세션으로 재발급 | |
| 56 | 공통 코드: 같은 레포 공유 모듈(`libs/common-*` + `build-logic`), 패키지 `com.seatsac.{service}`·`com.seatsac.common.{core,web,test}`. `libs/` 변경 승인 2명 | 최종 결정 21번 "복사" 대체 |
| 48 · 64 | 레포 `SeatSAC`·`SeatSAC-gitops`, 브랜치 `{type}/{기능ID 또는 WBS ID}-{설명}`, 데모 주간 11/16~20 머지 금지, 태그 rc1 11/6·rc2 11/13·v1.0.0 11/19, Lambda Python, 이슈는 노션 WBS만 (⚠️AI `services/ai` 다시 생김 — 82) | `docs/repo-workflow.md` |
| ⚠️AI 87 | AI 일정·머지: **rc1(11/6)** gateway 라우트(플래그 off)·분석 쿼리 제외 머지, ai 인프라·골격은 플래그 off로 staging 배포 가능 · **M6(11/9~13)** `services/ai/**`·`contracts/openapi/ai*`·gitops `base/ai`·`overlays/staging/ai`만 머지 허용(gateway·booking·`libs/`·Terraform 금지), **테스트 준비 예외(11/10 일정 변경)** `tests/k6/**`·`docs/**`·gitops `base/*/networkpolicy.yaml` 테스트 Job 허용 규칙 추가는 M6에도 머지(조건: PR 본문에 「M6 예외(테스트 준비)」와 이유, 스크럼 공유, 테스트 실행 중이 아닐 때), **AI 화면 예외(11/12)** 3.3.7 챗봇 화면(`frontend/src/entities/ai-chat/**`·`frontend/src/features/ai-chat/**`·`frontend/mock/**`·`frontend/README.md`·`widgets/page-layout`의 Dock 연결)도 M6에 머지 — frontend는 S3 + CloudFront 배포라 클러스터·블루/그린과 무관하고 화면은 챗봇 창을 열 때만 `/api/ai/**`를 부른다(런북 6-G-5). 조건은 테스트 준비 예외와 같음(PR 본문 「M6 예외(AI 화면)」), 그 밖의 `frontend/**`는 금지 그대로, 테스트는 `ng-ai` 0·플래그 off, ai 검증은 로컬 Compose + 개발 키 · **rc2(11/13)** ai 코드 동결(이후 `fix/`만), M6 테스트 뒤 플래그 켜기 · **데모 주간** ai `fix/`도 금지(64 그대로) | 64 보완 |
| 49 | 공통 문서 6종 채택, 합본 `Claude.md`를 레포 루트에 (노션과 다르면 레포 우선, 수정은 PR) | 65는 49로 통합(폐기) |

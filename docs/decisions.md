# 결정 요약 (ADR 한 장)

> 기준 2026-10-22 (회의록2 결정 반영). 원본은 노션 「결정 기록 (ADR)」(번호 = DB 번호). 근거·대안은 노션에서 본다. 이 문서는 **지금 유효한 결론**만 한 줄씩 둔다.
> 이후 결정이 앞 결정을 덮은 경우 "→ 대체"로 표시했다. ⏳는 아직 열린 항목이다.

---

## 1. 범위·일정·사람

| ADR | 결론 | 비고 |
| --- | --- | --- |
| 1 · 50 | 서비스는 gateway·catalog·booking·user·payment 5개. AI 서비스는 범위에서 제외(10/22), 필요하면 그때 다시 추가 | 26(LLM) 폐기 |
| 52 | 데모는 **무중단 배포 · 실시간 좌석** 2개로 확정. 실시간 좌석 데모에 동시성 테스트(같은 좌석 100명 → 성공 1명) 포함 | 선점 전략 비교는 여유 시 |
| 45 | 선점 전략 비교(db-lock/redis): 지금은 3.2.2에 `SeatHoldStrategy` 인터페이스만, db-lock은 잠글 행·만료 처리 **설계 초안만**(남궁일). 구현·측정은 최후 순위(4.2 때 여유 시) | 새 노드 불필요 |
| 53 | 김서현은 일정에서 제외. 필요하면 개발·인프라/DevOps의 WBS 하나씩 맡긴다 | 4.1.2 한채연, OPS-07 코드 김종식 |
| 25 | Payment 백엔드는 남궁일, 결제 화면은 한채연 | |
| 2 | 롯데시네마 좌석 배치·하루 편성만 로컬에서 1회 수집 → 비공개 `lotte-seed` 시드 | 실시간 연동·예매 호출 없음 |
| 15 · 16 | ⏳ 스크린 위치(GetSeats `ScreenSeatInfo` 사용 가정) · 수집 방식(로컬 1회 가정) — 한채연 확인 예정 | |

## 2. 데이터·실시간

| ADR | 결론 | 비고 |
| --- | --- | --- |
| 3 · 33 · 61 | RDS **MySQL 8.4** 1대(**Single-AZ**, `db.t4g.small`, TLS 강제, 마스터 비밀번호는 RDS 관리 Secrets Manager) + 서비스별 스키마·계정 | Multi-AZ·AZ 장애 실험은 시간 남을 때 |
| 33 · 59 | ElastiCache **Valkey 8.2 `cache.t4g.micro`**, `noeviction`, `notify-keyspace-events Ex`, 메모리 80%·Evictions 경보 | 부하 테스트에서 70% 넘으면 t4g.small |
| 57 | DB 마이그레이션은 ArgoCD **Flyway 훅 Job**(`{svc}_migrator`, Sync 단계 wave -1 — ConfigMap 다음·앱 전, 실패 시 sync 중단), 앱은 Flyway 끔. 스키마·계정·Secret은 Terraform(`envs/staging-k8s`)이 클러스터 안 Job으로 생성(3.1.12) | 처음 "PreSync"였으나 첫 배포 때 ConfigMap보다 먼저 돌아 Sync wave -1로 변경(10/23) |
| 60 | 리포트 뷰 만들지 않음(catalog V2 머지 안 함) · payment `PROCESSING` 30초 초과는 기동 시 `FAILED(PROCESSING_TIMEOUT)` · `pg_provider`는 `MOCK`만 | |
| 4 | 좌석 이벤트: Valkey Pub/Sub `seat-events:{회차}` → SSE, 놓치면 BKG-01 스냅샷 | 유실 허용 |
| 5 | 선점: Valkey `SET NX EX 300`(Lua로 여러 좌석 원자적) + 그 시점 RDS PENDING 예매 | |
| 11 | 한 번에 최대 4석 | |
| 19 | 멱등: `Idempotency-Key` + Valkey `idem:{userId}:{키}` 24시간 + 유일 제약(`booking_seat`, `current_payment_id`) | |
| 22 | 내부 브로커 없음: Pub/Sub + keyspace 알림 + sorted set | 만료 반영 2초 초과 시 재검토 |
| 44 | 만료: keyspace(Ex) + `hold-expiry` 1초 스캔 + RDS 10초 스캔, 조건부 UPDATE로 한 번만 | 노션 본문 "ADR-21" = 이 항목 |

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
| 7 · 34 | EKS On-Demand **t3.large 2대**(ng-a 1 · ng-c 1). 환경은 **staging 하나**. 평일 밤·주말 노드 0대(월~금 08:23 켜기·23:07 끄기 KST, 3.1.14), 데모 주간 11/16~20 24시간. 비용 경보 Budgets 월·일 | 노션 ADR-7 제목 "3대"는 이전 값 |
| 8 · 35 · 58 | Kustomize + ArgoCD. booking·gateway만 블루/그린(5xx 1%·결제 timeout/error 5% 롤백, scaleDown 60초), 나머지 롤링. 프로브·자원·HPA·메트릭·Secret·NetworkPolicy는 `docs/service-runtime-contract.md` | 동시 블루/그린 금지 |
| 9 · 31 | CloudFront + S3(OAC), 기본 도메인 HTTPS, `/api/*` → ALB, CORS 없음 | 31의 httpOnly 쿠키는 37·63으로 대체 |
| 78 | 블루/그린 분석 트래픽: 분석 중 AnalysisTemplate Job(k6, 조회만)이 `{svc}-preview`로 직접 요청, `/actuator` 제외 요청 ≥ 20건/분을 같이 판정(트래픽 0 = 실패). 실패하면 abort(전환 없음) → gitops 태그 revert PR. 실패 버전 시연은 gitops 설정 PR로 의존 서비스 주소를 깨뜨리는 방식 | ⏳ 남궁일 확인(결제 경로 트래픽·시연 방식) |
| 10 | 관측은 Prometheus·Grafana, 비밀 값은 Terraform이 만드는 K8s Secret | External Secrets·Loki는 확장 |
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
| 48 · 64 | 레포 `SeatSAC`·`SeatSAC-gitops`, 브랜치 `{type}/{기능ID 또는 WBS ID}-{설명}`, 데모 주간 11/16~20 머지 금지, 태그 rc1 11/6·rc2 11/13·v1.0.0 11/19, Lambda Python, 이슈는 노션 WBS만 | `docs/repo-workflow.md` |
| 49 | 공통 문서 6종 채택, 합본 `Claude.md`를 레포 루트에 (노션과 다르면 레포 우선, 수정은 PR) | 65는 49로 통합(폐기) |

<노션 반영 필요> ADR-7 선택안 "t3.large 2대(ng-a 1·ng-c 1)", ADR-10 제목·선택안 일치, ADR-22 본문 "ADR-21" → "ADR-44", ADR-23 "도입함" → "도입하지 않음", ADR-31에 "ADR-37·63으로 대체", ADR-33에 "Valkey t4g.micro(ADR-59)·Single-AZ(ADR-61)", ADR-34 데모 주간 11/16~20, ADR-36 "10/18 스냅샷" 콜아웃, 37·38·46 결론 채우기, ADR-65(중복) 삭제 여부

<노션 반영 필요> ADR-74(10/25 노션 등록, 상태 열림): 팀 확인 후 노션 상태를 「결정됨」으로

<노션 반영 필요> ADR-69·74 본문(10/25 3.1.5): 서비스 Application은 `kubernetes_manifest`(+`argocd_apps_enabled` 두 단계) 대신 Helm `argocd-apps`로 ArgoCD와 한 번의 apply, root 앱 `cinema-root`도 Terraform(gitops `apps/root.yaml` 삭제). ArgoCD 화면은 port-forward 전용, 팀원은 로그인 없이 읽기 전용(anonymous → `role:readonly`) + `argocd` namespace Application 조회 Role
<<<<<<< Updated upstream
=======

<노션 반영 필요> ADR-78(10/26 3.1.6) 새로 등록(상태 열림): 블루/그린 분석 트래픽 Job·최소 요청 20건·actuator 제외·실패 시 abort + revert PR·실패 버전 시연 방식. 대안(분석 중 실사용자 트래픽 일부를 preview로 = 카나리 전환, k6 부하 Job을 preview로 = OPS-10과 중복)과 기각 이유는 3.1.6 WBS 기록
>>>>>>> Stashed changes

# 데이터 모델·DB 계약 (ERD 요약)

> 기준 2026-10-21. 원본은 노션 WBS 2.2.2 「ERD·서비스별 스키마」(ERD 6차, 완료)와 각 서비스 Flyway V1. 이 문서는 개발·인프라가 함께 보는 **요약**이다. 테이블 정의가 바뀌면 Flyway 파일이 먼저, 이 문서가 다음이다.
> 10/22 결정 반영(ADR-59·60·61). 스키마는 4개(catalog·booking·user·payment)뿐이다.
> ⚠️AI 11/5 AI 재도입(ADR-82~87)에도 **RDS는 바뀌지 않는다**(리포트 뷰·`ai_readonly` 없음, ADR-60 유지). ai 데이터는 6장.
> 🆕 11/23 MSA 고도화(노션 ADR-115·116·117·118·120): **서비스마다 자기 RDS 인스턴스**(6개), 스키마 `coupon_db`·`ai_db` 추가, saga·inbox 테이블 추가. 7장이 이번 추가분 요약이다(정의 원본은 각 서비스 Flyway).

---

## 1. 공통 규칙

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

## 2. 스키마별 테이블

### catalog_db (한채연) — 시드 DDL 기준

| 테이블 | 키·제약 | 비고 |
| --- | --- | --- |
| `theater` | PK `theater_id`, UK (`source_provider`, `source_theater_id`) | 132곳, `has_layout=1` 4곳 |
| `auditorium` | PK `auditorium_id`, UK (`theater_id`, `source_auditorium_id`), FK theater | 39개 관 |
| `seat` | PK `seat_id`, UK (`auditorium_id`, `layout_version`, `seat_label`), FK auditorium | 6,767석, `seat_id = auditorium_id × 10000 + 순번`, `active` |
| `auditorium_layout` | PK (`auditorium_id`, `layout_version`) | 스크린·입구·통로 JSON, `fetched_at` |
| `layout_import` | PK `import_id` | 수집·검증 이력 |
| `movie` | PK `movie_id`, UK (`source_provider`, `source_movie_code`) | 22편. TMDB 보충 컬럼(NULL 허용): `tmdb_id`, `genres`, `overview`, `release_date`, `runtime_minutes`, `poster_path`, `director`, `cast_names`, `meta_fetched_at` |
| `screening` | PK `screening_id`, UK (`auditorium_id`, `business_date`, `play_sequence`), INDEX (`movie_id`, `business_date`) | `starts_at`·`ends_at` UTC, `layout_version` |

### user_db (한채연)

| 테이블 | 키·제약 | 비고 |
| --- | --- | --- |
| `user` | PK `user_id` CHAR(36), UK `email` | password·role 없음(Cognito). email·name은 첫 `GET /users/me` 때 `GetUser` |
| `user_preference` | PK `user_id` | `preferred_time_slot`, `preferred_genres` JSON |
| `user_preferred_theater` | PK (`user_id`, `rank_no`), UK (`user_id`, `theater_id`) | `rank_no` 1~3 |

### booking_db (남궁일)

| 테이블 | 키·제약 | 비고 |
| --- | --- | --- |
| `booking` | PK `booking_id`, UK `hold_id`, UK `current_payment_id`, INDEX (`status`, `expires_at`) | `user_id` CHAR(36), `screening_id`, `status`, `seat_count` 1~4, `amount`, `seat_labels` JSON, `expires_at`, `created_at`·`confirmed_at`·`canceled_at`. hold_id·seat_labels·amount는 선점 후 불변 |
| `booking_seat` | PK (`booking_id`, `seat_id`), **UK (`screening_id`, `seat_id`)** | 이중 확정 최종 방어선. PAYMENT_FAILED·EXPIRED·CANCELED 때 행 삭제 |
| `outbox_message` | PK `outbox_id`, UK `message_key`(= 알림 `eventId`), UK (`event_type`, `aggregate_id`), INDEX (`status`, `next_attempt_at`) | `payload` JSON, `trace_id` CHAR(32), `status` PENDING·SENDING·SENT·FAILED, `attempt_count`, `last_error`, `sent_at`. 확정과 같은 트랜잭션에서 INSERT, 릴레이가 1·2·4…최대 60초 간격 재전송, 10회 초과 FAILED + 경보 |

### payment_db (남궁일)

| 테이블 | 키·제약 | 비고 |
| --- | --- | --- |
| `payment` | PK `payment_id` CHAR(36)(booking 발급), **UK `booking_id`**(`uk_payment_booking_id`, 예매당 1회, ADR-24), INDEX (`status`, `created_at`)(기동 정리) | `amount`, `method`, `card_last4`(카드 번호 저장 금지), `status` PROCESSING·APPROVED·FAILED·CANCELED, `failure_code`, `cancel_reason`, `pg_provider`(`MOCK`·`PORTONE`, ADR-112 — ADR-60의 「MOCK만」 대체), `pg_transaction_id`(PORTONE에서 돈이 잡힌 증거 = 포트원 transactionId, UK — 승인 행, 그리고 금액 불일치·늦은 결제로 FAILED인데 환불이 남은 행에도 기록. FAILED + 값 있음 + `canceled_at` null = 환불 미완료), `approved_at`·`canceled_at`. user_id 없음. MOCK: PROCESSING으로 먼저 INSERT·커밋 → 판정은 트랜잭션 밖 → `UPDATE … WHERE status='PROCESSING'`, 30초 넘게 남은 PROCESSING은 기동 시 `FAILED`(`PROCESSING_TIMEOUT`)로 정리(ADR-60). PORTONE: PROCESSING(결제창 대기) → `verify`·웹훅이 포트원 단건 조회로 APPROVED·FAILED, 7분 넘게 PROCESSING이면 포트원 조회로 닫음(PAID면 APPROVED). `failure_code`에 `PG_DECLINED`·`USER_CANCELED`·`AMOUNT_MISMATCH` 추가. DDL 변경 없음(열은 이미 있음). 🆕 11/24(7-2): `failure_code` `BOOKING_EXPIRED`, `amount`는 15,000 배수 검사를 빼고 1~60,000(쿠폰 할인), UK `uk_payment_booking_id` → `live_booking_id`(saga 재결제, ADR-24 보완) |

## 3. 서비스 간 ID 참조 (FK 없음)

| 참조하는 쪽 | 컬럼 | 원본 |
| --- | --- | --- |
| `booking.booking` | `user_id`, `screening_id` | `user.user`, `catalog.screening` |
| `booking.booking_seat` | `screening_id`, `seat_id` | `catalog.screening`, `catalog.seat` |
| `payment.payment` | `booking_id` | `booking.booking` |
| `user.user_preferred_theater` | `theater_id` | `catalog.theater` |

## 4. 상태 전이 (booking)

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

## 5. 인프라가 만드는 것 (3.1.3 · 3.1.12)

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

## 6. ⚠️AI ai 서비스 데이터 (RDS 밖, ADR-84·85)

### 6-1. SQLite 스냅샷 (ai 파드 메모리, `:memory:`)

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

### 6-2. 개인 데이터

"내 예매"는 SQLite에 넣지 않고 요청마다 `GET {PUBLIC_API_BASE_URL}/users/me/bookings`를 **사용자의 토큰 그대로** 호출해 답한다. 캐시하지 않고, Claude API에는 예매 행을 보내지 않는다(템플릿 문장으로 답). 채팅으로 좌석 잡기(플래그 `APP_AI_SEAT_HOLD_ENABLED` 기본 켜짐)도 같은 길(공개 주소 + 사용자 토큰 그대로)로 `POST /seats/hold`를 부르되 **사용자의 확인 버튼 뒤에만** 부른다.

### 6-3. ai Valkey 키

`docs/conventions.md` 4장 ⚠️AI 표.

### 6-4. 🆕 `ai_db` (11/23, 노션 ADR-120)

`APP_AI_DATA_SOURCE=db`일 때 6-1 SQLite 대신 쓴다(기본 `snapshot`, 같은 질의 규칙 — `starts_at > 현재 KST`, 포스터·시드 원본·개인 데이터 없음).

| 테이블 | 키·제약 | 채우는 방법 |
| --- | --- | --- |
| `movie` · `theater` · `screening` | 6-1과 같은 컬럼, PK 원본 ID | 최초 백필(공개 API) → SQS `ai-catalog-events`(`screening.changed`·`movie.changed`) upsert(`version`이 큰 것만) → 10분 전체 대조 |
| `processed_message` | PK `message_id` | inbox(중복 무시) |
| `unmet_demand` | PK `id`, INDEX (`created_at`), (`category`) | 범위 밖·거부 질문 1건 = 1행: `category`·`reason`·`question_hash`·`slots` JSON·`created_at`. **질문 원문·userId 없음** |

- 계정: `ai_app`(DML), `ai_migrator`(DDL), `ai_query`(NL2SQL 실행 전용, `movie`·`theater`·`screening` SELECT만, `MAX_EXECUTION_TIME` 5초)
- `ai_query`의 테이블 권한은 **ai 마이그레이션이 준다**(6.1.1): db-bootstrap은 계정만 만든다(권한 없음) — 그때는 테이블이 없고 MySQL은 없는 테이블에 SELECT만 줄 수 없다. 테이블을 만드는 마이그레이션 뒤에 `GRANT SELECT ON ai_db.movie TO 'ai_query'@'%'`(`theater`·`screening`도)를 넣는다. 그래서 `ai_migrator`만 `ai_db` 범위의 GRANT OPTION을 갖는다(다른 서비스의 migrator에는 없다). 세 테이블 밖의 권한을 주면 다음 db-bootstrap 실행이 실패한다. 5초 상한은 계정에 걸 수 없어 ai가 연결마다 세션에 건다

### 6-5. 🆕 이용 안내 문서 (RAG, 6.4.7 — ADR-126)

`APP_AI_RAG_ENABLED=true`일 때만 쓴다(기본 `false`). DB·Valkey에 넣지 않는다 — 조각이 20개 안팎이라 파드 메모리로 충분하다(늘어나면 ai Valkey 벡터 인덱스로 옮긴다).

| 항목 | 내용 |
| --- | --- |
| 원본 | `services/ai/src/seatsac_ai/resources/guides.yaml` — 항목 하나가 조각 하나: `id`·`title`·`body`(300자 안팎) |
| 내용 | **SeatSAC 사이트 이용 안내만**: 예매 순서·좌석 선점 시간·결제·취소·관람등급 뜻·AI가 하는 일. 이 레포의 계약·문서로 확인되는 사실만 쓴다. 극장 시설·주차·매점처럼 우리 데이터에 없는 것은 쓰지 않는다(지어낸 답 방지 — 그런 질문은 계속 `OUT_OF_SCOPE`로 끝나 `unmet_demand`에 쌓인다). 롯데시네마 원문 금지 |
| 색인 | 기동 때 조각마다 임베딩(의미 캐시와 같은 모델, 384차원)을 계산해 메모리에 둔다. 고칠 때는 PR → 재배포 |
| 검색 | 질문 임베딩과 코사인 유사도 상위 `APP_AI_RAG_TOP_K`개 중 `APP_AI_RAG_MIN_SCORE` 이상만 (런타임 계약 9-1·9-2) |

## 7. 🆕 MSA 고도화 추가분 (11/23)

### 7-1. 인스턴스 (ADR-115)

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

### 7-2. 테이블 추가 (이름은 API 명세서 v1.5 27-1과 같다)

| 스키마 | 테이블 | 키·제약 | 비고 |
| --- | --- | --- | --- |
| `booking_db` | `checkout_saga` | PK `checkout_id` CHAR(36), 🆕 UK `active_booking_id`, INDEX (`booking_id`, `created_at`), INDEX (`state`, `step_deadline_at`)(스윕) | `state`(STARTED·COUPON_RESERVING·PAYMENT_PREPARING·READY_TO_PAY·CHARGING·VERIFYING·CONFIRMED·COMPENSATING·FAILED·EXPIRED·🆕 COUPON_REJECTED·🆕 PAYMENT_RETRYABLE), `method`(TOSSPAY·CARD), `coupon_issue_id`(🆕 `VARCHAR(64)` — 계약의 불투명 문자열), ~~`discount_rate`~~ 🆕 `discount_kind`(RATE·AMOUNT)·`discount_value`(계약 `coupon.discount {kind, value}`), `original_amount`·`discount_amount`·`final_amount`, `payment_id`, `checkout_json`(READY_TO_PAY 결제창 값), `failure_code`·`failure_reason`, `step_deadline_at`, `verify_attempt`, 🆕 `last_unknown`(마지막 결과 불명 사유 — 재확인 간격 계산), 🆕 `refund_notice`(WILL_REFUND_IF_PAID·REFUND_IN_PROGRESS — 만료 화면 안내), `trace_id`. ~~진행 중 체크아웃은 예매당 1건(앱이 확인)~~ → 🆕 진행 중 체크아웃은 예매당 1건 — 생성 열 `active_booking_id`(진행 중 상태일 때만 `booking_id`, 끝난 상태면 NULL)의 UK가 동시 요청까지 막는다(409 `CHECKOUT_IN_PROGRESS`). 끝난 행은 NULL이라 쿠폰 거절 뒤 같은 예매에 새 행을 만들 수 있다. 🆕 PAYMENT_RETRYABLE도 끝난 상태(NULL). **saga 경로 예매의 기준은 이 표에 행이 하나라도 있는지**다(진행 중인지가 아니라) — 만료 처리·`GET /bookings/{id}`·선점 해제가 행이 있으면 payment를 조회하지 않고 saga 상태로 판단한다(끝난 saga에도 실패한 `current_payment_id`가 남아 동기 경로로 오인하지 않게) |
| `booking_db` | `booking` (기존) | 그대로 | `amount`는 선점 후 불변(원가). 할인·최종 금액은 `checkout_saga`에만, `booking.confirmed`의 `amount`(최종)·`discountAmount`로 내보냄 |
| `booking_db` | `processed_message` | PK `message_id` | 결과(reply) inbox |
| `booking_db` | `outbox_message` (기존) | 🆕 열 `aggregate_key VARCHAR(80) NOT NULL DEFAULT ''` 추가, UK (`event_type`, `aggregate_id`) → (`event_type`, `aggregate_id`, `aggregate_key`) | 명령·이벤트도 같은 표에 `event_type`으로. ~~결제 재확인은 새 행 `aggregate_id = {checkoutId}:{attempt}`~~ → 🆕 `aggregate_id`는 BIGINT라 문자열을 넣을 수 없다 — `aggregate_id = bookingId` 그대로, 명령은 `aggregate_key = {checkoutId}:{단계}`, 결제 재확인은 `{checkoutId}:{attempt}`(새 `eventId`). 기존 행·이전 버전 파드가 넣는 행은 `''`라 기존 유일 보장이 그대로다(블루/그린 중 두 버전 공존). 🆕 열 `destination VARCHAR(40) NOT NULL DEFAULT 'NOTIFICATION'`(`NOTIFICATION`·`PAYMENT_COMMANDS`·`COUPON_COMMANDS`·`BOOKING_EVENTS`) — 릴레이가 보낼 곳을 고른다. 🆕 `booking.confirmed`는 행 2개(알림 큐 `aggregate_key=''` + SNS `aggregate_key='sns'`, **`eventId`는 행마다 새로** — `message_key` UK), BKG-07 취소는 SNS `booking.canceled{USER_CANCELED}` 1행(`aggregate_key='canceled'`, 동기·saga 공통) |
| `payment_db` | `processed_message` · `outbox_message` | 같은 구조 | 명령 inbox · 결과 발송 |
| `payment_db` | 🆕 `saga_payment` | PK `payment_id`, INDEX (`checkout_id`) | saga 명령으로 만든 결제 표시(`checkout_id`, `booking_id`). payment 스윕·포트원 웹훅이 이 결제를 APPROVED·FAILED로 닫으면 **같은 트랜잭션에 결과 이벤트**(`payment.approved`·`payment.declined`)를 outbox에 넣는다 — 체크아웃 중 booking은 payment를 조회하지 않기 때문. 동기 경로 결제는 행이 없어 지금과 같다. 🆕 열 `released_at`(booking이 `payment.release-requested`를 보낸 시각 — 이 결제는 더 이상 예매에 쓰이지 않는다. 이후 PAID로 확인되면 APPROVED 대신 FAILED(`BOOKING_EXPIRED`) + 환불 미완료 → 스윕 환불) |
| `payment_db` | `payment` (기존) | 🆕 UK `uk_payment_booking_id`(`booking_id`) → 생성 열 `live_booking_id`(status가 PROCESSING·APPROVED일 때만 `booking_id`, 나머지 NULL) + UK. 열 `attempt_no INT NOT NULL DEFAULT 1` | ADR-24 11/24 보완 — 살아 있는 결제는 예매당 1건, 실패·취소 행은 여러 개 허용(saga 재결제). 인덱스 교체라 **배포 1회**(블루/그린 중 옛 버전도 「살아 있는 결제 2건」은 똑같이 막힌다). `failure_code` `BOOKING_EXPIRED` 추가 |
| `user_db` | `booking_history` | PK `booking_id`, INDEX (`user_id`, `confirmed_at`) | `status` CONFIRMED·CANCELED, `screening_id`, `seat_count`, `amount`·`discount_amount`(NULL 허용), `confirmed_at`·`canceled_at`. CANCELED가 이긴다(순서 역전) |
| `user_db` | `booking_history_sync` · `processed_message` | PK `user_id` · PK `message_id` | 사용자별 첫 조회 1회 sync 채움 기록 · inbox |
| `catalog_db` | `outbox_message` · `screening.version` 열 | 컬럼·유일 제약은 booking과 같다(🆕 UK (`event_type`, `aggregate_id`, `aggregate_key`)) — `aggregate_id` = screeningId 또는 movieId, 🆕 `aggregate_key` = `{version}` · `BIGINT` | `screening.changed`·`movie.changed` 발행, 복제본은 `version`이 클 때만 덮어씀. 같은 회차·영화가 바뀔 때마다 `version`이 올라 **변경 1번 = 새 행 1개**(booking 결제 재확인과 같은 `aggregate_key` 방식 — ID만 넣으면 두 번째 변경이 유일 제약에 걸리고, `aggregate_id`는 BIGINT라 `{id}:{version}` 문자열을 넣을 수 없다) |
| `coupon_db` | `coupon_event` | PK `event_id` | `title`, `discount_rate`, `eligible_grade`(VIP), `total_quantity`, `starts_at`·`ends_at` |
| `coupon_db` | `coupon_issue` | PK `coupon_issue_id` CHAR(36), **UK (`event_id`, `user_id`)**, INDEX (`user_id`, `state`) | `state` ISSUED·RESERVED·USED·EXPIRED, `expires_at`(= 이벤트 종료), `issued_at`·`used_at` |
| `coupon_db` | `coupon_reservation` | PK `checkout_id`, INDEX (`coupon_issue_id`) | `state` RESERVED·RELEASED·USED. 체크아웃 1건 = 1행 — 반환이 예약보다 먼저 오면 RELEASED 행을 먼저 만들어 늦은 예약을 거절 |
| `coupon_db` | `processed_message` · `outbox_message` | 같은 구조 | 명령 inbox·결과 발송 |

- 재고는 Valkey `coupon:stock:{eventId}`의 `DECR` 결과가 0 이상이고 DB 행 생성도 성공해야 확정. `INCR`로 되돌리는 것은 **`DECR`이 성공한 뒤**의 두 경우뿐이다 — 결과가 음수(매진 처리)이거나 DB INSERT가 실패했을 때. `DECR`이 실행되지 않았거나 실패했으면 `INCR`를 부르지 않는다(안 깎은 재고를 올리면 `total_quantity`를 넘겨 발급된다) — conventions 4장 `coupon:stock` 행과 같은 기준. DB UK `(event_id, user_id)`가 1인 1매 최종 방어선. 키가 없으면 `total_quantity − 발급 수`로 `SET NX` 재생성
- 재고 불변식은 `Valkey 재고 + 발급 수 ≤ total_quantity`(같다가 아니라 **이하**)다. `DECR`이 성공한 뒤 DB INSERT 전에 프로세스가 죽거나 `DECR` 응답을 받지 못하면(타임아웃) 그 1매는 되돌리지 않는다 — 결과를 모를 때 `INCR`를 부르면 안 깎인 재고를 올릴 수 있어서, **덜 발급되는 쪽**으로 틀리게 둔다(초과 발급 없음. 사용자가 다시 눌러도 UK `(event_id, user_id)`가 중복 발급을 막는다). 잃은 수량의 대조는 **발급이 멈춘 상태에서만** 한다: 이벤트가 끝난 뒤, 또는 운영자가 coupon을 멈춘 뒤 `total_quantity − 발급 수`로 키를 다시 쓴다. 발급이 도는 중에 키를 지우거나 다시 쓰면 그때 진행 중이던 발급 수만큼 더 나갈 수 있어서 하지 않는다(Valkey 재시작으로 키가 사라져 `SET NX`로 재생성되는 순간도 같다 — 드문 경우로 두고, 막으려면 6.3.2에서 INSERT 뒤 발급 수 확인을 넣는다). 이벤트 중 자동 복구(차감 기록을 따로 두고 스윕)는 넣지 않았다 — 1시간 이벤트에서 파드가 죽은 횟수만큼만 덜 나간다
- 이벤트 종료 스윕은 ISSUED만 EXPIRED. RESERVED는 booking의 use·release까지 그대로(1시간 넘으면 경보만)

### 7-3. 체크아웃 상태 전이 (ADR-116, API 명세서 v1.5 21-2)

| 현재 | 다음 | 계기 | 보상 |
| --- | --- | --- | --- |
| — | STARTED | `POST /bookings/{id}/checkout`(예매 PENDING·선점 유효·마감 전·🆕 `current_payment_id` 없음 — 🆕 또는 마지막 saga가 PAYMENT_RETRYABLE, 예매당 3회까지) | — |
| STARTED | COUPON_RESERVING / PAYMENT_PREPARING (TOSSPAY) / 🆕 CHARGING (CARD) | 쿠폰 있음 / 없음 → 명령 발송(같은 트랜잭션 outbox). 🆕 **결제 명령을 처음 보낼 때** `attachPayment`로 paymentId를 붙인다 | — |
| COUPON_RESERVING | PAYMENT_PREPARING / 🆕 CHARGING | `coupon.reserved` → 금액 확정 + 🆕 `attachPayment` + 결제 명령 | — |
| COUPON_RESERVING | ~~FAILED~~ 🆕 **COUPON_REJECTED**(끝) | `coupon.reservation-rejected`(`failure.code` `COUPON_NOT_USABLE`) | **없음** — 예매 PENDING·좌석 HELD·`expires_at` 그대로(선점 시간을 늘리지 않는다). 사용자는 최초 선점 5분 안에 다른 쿠폰이나 쿠폰 없이 `POST …/checkout`을 다시 보낸다(새 행). paymentId를 쿠폰 단계에서 붙이지 않는 이유: 붙어 있으면 기존 만료 처리가 결제 중으로 보고 만료를 유예만큼 미룬다 |
| PAYMENT_PREPARING | READY_TO_PAY | `payment.prepared` | — |
| ~~PAYMENT_PREPARING~~ | ~~CHARGING (CARD)~~ | ~~`payment.charge-requested` 발송~~ → 🆕 CARD는 결제 준비 단계가 없다(위 두 행) | — |
| READY_TO_PAY | VERIFYING | `POST …/checkout/confirm` → `payment.verification-requested` | — |
| 🆕 READY_TO_PAY·VERIFYING·CHARGING | CONFIRMED | `payment.approved` + 예매가 **PENDING일 때만** → 예매 CONFIRMED(4장, 동기 경로와 같은 `BookingPaymentResultApplier`) + `coupon.use-requested`. READY_TO_PAY가 들어간 이유: 사용자가 결제 뒤 확인 요청 없이 떠나도 포트원 웹훅·payment 스윕이 결제를 닫고 결과 이벤트를 낸다(`saga_payment`) | — |
| VERIFYING·CHARGING | COMPENSATING → FAILED | `payment.declined`(`PAYMENT_DECLINED`) — 🆕 `retryable=false`이거나 플래그 `APP_CHECKOUT_PAYMENT_RETRY_ENABLED` off이거나 3회째 | `coupon.release-requested` + 좌석 해제(PAYMENT_FAILED) + `booking.canceled` |
| 🆕 VERIFYING | **PAYMENT_RETRYABLE**(끝) | `payment.declined{retryable: true}`(포트원 FAILED 확인 — 돈이 안 빠짐) + 플래그 on + 3회 미만 | 좌석·예매·`expires_at` 그대로, 쿠폰만 반환(다음 시도에서 다시 예약). 다음 시도는 `current_payment_id`를 비교 후 교체(`replacePayment`)하고 새 결제를 만든다 |
| COUPON_RESERVING·PAYMENT_PREPARING | COMPENSATING → FAILED | 단계 기한 ~~30초~~ 🆕 **10초**(`STEP_TIMEOUT`) — **돈이 움직이기 전 단계만**(정상 왕복 1~3초, 최악 약 6초) | 위와 같음. 쿠폰은 예약 여부를 몰라도 반환 명령을 보낸다 |
| VERIFYING·CHARGING | (그대로) | 재확인 기한 안에 결과 없음 · `payment.result-unknown` | **보상하지 않음** — 새 `eventId`로 `payment.verification-requested` 재발송. ~~🆕 간격 ⏳(5초 고정 제안), `expires_at + APP_PAYMENT_PENDING_GRACE`(2분)까지만 보낸다~~ 🆕 11/24 간격: `NOT_YET` 5초, 무응답(포트원·payment) 5 → 10 → 20 → 40초 …, ±20% 흔들림, **`expires_at`까지만** |
| ~~진행 중 어디서나~~ 🆕 READY_TO_PAY·VERIFYING·CHARGING | ~~COMPENSATING → EXPIRED~~ ~~🆕 **예매 상태를 따라** CONFIRMED / FAILED / EXPIRED~~ 🆕 11/24 **EXPIRED** | ~~예매가 PENDING을 벗어남. paymentId가 붙은 예매는 기존 만료 처리(`HoldExpiryService`)가 선점 만료 + 유예 2분 뒤 payment를 조회해 닫는다 … 대략 7~9분에 닫힌다~~ 🆕 **선점 5분.** 기존 만료 처리(`HoldExpiryService`)가 saga 행이 있는 예매는 유예·payment 조회 없이 바로 EXPIRED(좌석 해제). saga 스윕(1초)이 「진행 중인데 예매가 PENDING이 아닌」 saga를 EXPIRED로 닫는다(다른 트랜잭션 — 중간에 죽어도 다음 스윕이 처리) | 쿠폰 반환 + `payment.release-requested{HOLD_EXPIRED}`(결제가 붙어 있을 때) + `booking.canceled{EXPIRED}`. 환불은 payment가 맡는다(해제된 결제가 PAID로 확인되면 FAILED(`BOOKING_EXPIRED`) → 스윕 환불). `refund_notice`: 결제 여부를 모르면 `WILL_REFUND_IF_PAID`, 결과 불명이었으면 `failure.reason` `PAYMENT_RESULT_UNKNOWN` |
| 🆕 EXPIRED | (그대로) | 늦은 `payment.approved`(확정 조건 `expires_at > :now`를 못 넘음) | 확정하지 않는다. `refund_notice` → `REFUND_IN_PROGRESS`, `payment.release-requested`를 **한 번 더**(명령이 유실된 경우 대비, 멱등) |

- 4장 규칙 그대로: 모든 전이는 `WHERE state=:expected` 조건부 UPDATE, 이미 처리된 결과 메시지는 inbox·0행으로 무시. 결과 소비자·스윕은 블루/그린 preview 파드에서도 돈다(이전 버전과 호환)
- `GET /bookings/{id}`는 체크아웃 진행 중이면 payment를 부르지 않는다(동기 대조 없음)

<노션 반영 필요> 2.2.2 ERD에서 ai·stats 스키마·리포트 뷰 삭제(ADR-50·51·60), catalog V2 `v_screening_report` 머지 안 함, outbox_message 행의 "알림 발송 방식 팀 재확인 중" → "10/21 아웃박스 확정", Valkey `maxmemory-policy noeviction` 추가

<노션 반영 필요> 2.2.2 ERD에 11/23 MSA 고도화 반영: 인스턴스 6개(7-1), `coupon_db`·`ai_db` 스키마, booking `checkout_saga`·`processed_message`, payment `processed_message`·`outbox_message`, user `booking_history`·`booking_history_sync`, catalog `outbox_message`·`screening.version`, coupon `coupon_event`·`coupon_issue`·`coupon_reservation`(7-2)

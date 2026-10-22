# 데이터 모델·DB 계약 (ERD 요약)

> 기준 2026-10-21. 원본은 노션 WBS 2.2.2 「ERD·서비스별 스키마」(ERD 6차, 완료)와 각 서비스 Flyway V1. 이 문서는 개발·인프라가 함께 보는 **요약**이다. 테이블 정의가 바뀌면 Flyway 파일이 먼저, 이 문서가 다음이다.
> 10/22 결정 반영(ADR-59·60·61). 스키마는 4개(catalog·booking·user·payment)뿐이다.

---

## 1. 공통 규칙

| 규칙 | 내용 |
| --- | --- |
| 인스턴스 | RDS MySQL 8.4 1대(ADR-3·33), 서비스별 스키마·계정, 다른 스키마 권한 없음 |
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
| `payment` | PK `payment_id` CHAR(36)(booking 발급), **UK `booking_id`**(예매당 1회, ADR-24) | `amount`, `method`, `card_last4`(카드 번호 저장 금지), `status` PROCESSING·APPROVED·FAILED·CANCELED, `failure_code`, `cancel_reason`, `pg_provider`(`MOCK`만, ADR-60), `approved_at`·`canceled_at`. user_id 없음. PROCESSING으로 먼저 INSERT·커밋 → 판정은 트랜잭션 밖 → `UPDATE … WHERE status='PROCESSING'`. 30초 넘게 남은 PROCESSING은 payment 기동 시 `FAILED`(`PROCESSING_TIMEOUT`)로 정리(ADR-60) |

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
| 스키마 | `catalog_db`, `booking_db`, `user_db`, `payment_db` |
| 계정·권한 | 1장 계정 규칙. 마스터 계정은 앱·Job에서 쓰지 않음 |
| Secret | `{svc}-secrets`(`SPRING_DATASOURCE_PASSWORD`), `{svc}-migrator-secrets`(`FLYWAY_USER`, `FLYWAY_PASSWORD`) |
| ElastiCache Valkey 8.2 | `notify-keyspace-events Ex`(만료 감지, ADR-44), 전송 중 암호화, `maxmemory-policy noeviction`(선점 키가 밀려나면 안 됨), 노드 **`cache.t4g.micro`** + 메모리 80%·Evictions 경보(ADR-59) |
| 시드 적재 | 3.1.12 후 `lotte-seed` SQL을 `catalog_migrator`로 1회 (catalog ID BIGINT·UTC 반영본) |
| 가용성·백업 | RDS **Single-AZ**(ADR-61), 자동 백업 7일 |
| 만들지 않는 것 | 리포트 뷰(`v_*_report`)·`stats_db` — catalog Flyway V2 `v_screening_report`는 머지하지 않음(ADR-60) |

<노션 반영 필요> 2.2.2 ERD에서 ai·stats 스키마·리포트 뷰 삭제(ADR-50·51·60), catalog V2 `v_screening_report` 머지 안 함, outbox_message 행의 "알림 발송 방식 팀 재확인 중" → "10/21 아웃박스 확정", Valkey `maxmemory-policy noeviction` 추가

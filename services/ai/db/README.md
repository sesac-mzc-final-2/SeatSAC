# ai_db 마이그레이션 (6.4.1)

## 목적과 범위

승인된 `docs/data-model.md` 6-4를 Flyway SQL로 구현한다. **확장 단계**이며 기존 앱·스냅샷을 바꾸지 않는다.

1. `V202611260001`: 공개 데이터 복제용 `movie`·`theater`·`screening`, inbox `processed_message`, 수요 `unmet_demand` 생성.
2. `V202611260002`: `ai_query`에 세 공개 테이블의 SELECT만 부여.

스키마·계정·비밀번호는 SQL에 만들지 않는다. 인프라 db-bootstrap이 먼저 준비하고,
`ai_migrator`는 **ai_db에만** GRANT OPTION을 가져야 한다. `ai_app`은 DML 전용이다.

## 검증 방법 (레포 루트)

```bash
services/ai/.venv/bin/ruff check services/ai
services/ai/.venv/bin/pytest services/ai/tests -q
AI_DB_MIGRATION_TEST=1 services/ai/.venv/bin/pytest services/ai/tests/test_db_migration.py -q
```

통합 검사는 Docker와 로컬 이미지 `mysql:8.4`, `flyway/flyway:11.7.2`가 필요하다.
새 의존성·외부 API·기존 Compose·실제 DB·`.env`는 사용하지 않는다.
호스트 포트 없이 `--network=none`인 임시 MySQL을 만들고, Flyway만 그 컨테이너의
네트워크를 공유한다. 임의 비밀번호는 출력하지 않고 종료 시 해당 컨테이너만 정리한다.
환경변수를 지정하지 않으면 Docker 검사만 skip하고 정적 검사는 항상 실행한다.

검사 항목:
- [x] 실제 MySQL에서 migrator로 마이그레이션, 두 번째 실행 시 이력 2개·기존 행 유지
- [x] `ai_query`의 세 공개 테이블 SELECT 허용
- [x] INSERT·UPDATE·DELETE·DDL·권한 부여 거부
- [x] 수요·inbox·Flyway 이력·다른 스키마 접근 거부
- [x] `ai_app` 수요 INSERT 허용·DDL 거부, query 계정의 비TLS 연결 거부
- [x] 공개 열 허용 목록 일치, 수요 컬럼에 질문 원문·userId 없음
- [x] UTC 밀리초·인덱스·JSON object 제약·요약 완전 일치 구분
- [x] catalog의 40자 film_format 및 삭제 이벤트 버전 보존용 행 저장

### 로컬 실행 증거 (2026-11-26)

- 기준 main: `8cda804`, 작업 브랜치: `feat/6.4.1-ai-db`
- ruff: 통과 / `git diff --check`: 통과
- 전체 AI 테스트: **1,002 passed, 19 skipped**(Docker opt-in 검사)
- MySQL 8.4 + Flyway 11.7.2 별도 실행: **22 passed**(정적 3 + Docker 19)
- 분리된 코드 리뷰의 두 발견(원본보다 짧은 film_format, 공개 열 검사 빈틈)을 수정하고 재실행
- 전체 서비스 이미지 빌드·staging 연결은 미검증. DB 마이그레이션은 실제 Flyway로 검증
- 커밋·푸시·PR·GitHub/노션 쓰기·실제 좌석 선점 없음

## 배포 경계·다음 단계

Dockerfile은 SQL을 `/app/db/migration`에 담기만 하며 앱은 실행하지 않는다.
**이 작업만으로 staging에 마이그레이션이 적용되지는 않는다.** 별도 허락·담당자 협의 후
gitops `ai-flyway` 훅(Sync wave -1), SQL 복사 initContainer, migrator Secret,
RDS CA·호스트 검증을 연결해야 한다. 로컬 자체 서명 인증서 신뢰 옵션은 테스트 전용이다.
기존 Compose mysql-init에는 ai 계정이 없으므로 이 문서의 격리 테스트를 먼저 사용한다.

6.4.3 수요 기록 → 6.4.4 통계 API가 다음 우선순위다. 6.4.2에서 DB 검색을 연결할 때는
연결 세션마다 `SET SESSION MAX_EXECUTION_TIME=5000`, 삭제 행 제외, UTC↔KST 변환,
백필·버전 비교를 구현해야 한다. 지금은 DB 검색 연결을 구현한 것이 아니다.

## 6.4.3·6.4.4 후속 로컬 검증 (2026-11-26)

사용자 승인대로 검색 스냅샷은 유지하고, 수요 저장·통계만 별도 스위치로 연결했다.
구현 설명·환경변수·실행 흐름은 [`../README.md`](../README.md).

PR 제출 전 최신 main `524fe2f`를 fast-forward 반영하고 전체 MySQL 포함 검사를
다시 실행했다: **1,075 passed (39.03초)**. 일반 검사 **1,053 passed, 22 skipped**,
ruff·diff check도 통과했다. catalog 배포 읽기 전용 조회는 ready/available 1/1을
확인했지만, 이는 AI RDS 연결 검증을 대신하지 않는다.

| 검증 | 결과 |
| --- | --- |
| 전체 AI 테스트 + 실제 MySQL 통합을 한 번에 실행 | **1,075 passed, skip 없음** |
| 일반 실행(Docker opt-in 없음) | **1,053 passed, 22 skipped** |
| MySQL/Flyway 대상 검사 | **25 passed**(정적 3 + 실제 DB 22) |
| ruff / git diff --check | 통과 |
| CI 고정 uv 0.11.32 `lock --check` | 통과(PyMySQL 1.2.4 추가, 기존 패키지 버전 유지) |
| 분리된 코드 리뷰 | 로컬 범위 승인, 추가 필수 수정 없음 |

전체 재실행(레포 루트):

```bash
AI_DB_MIGRATION_TEST=1 services/ai/.venv/bin/pytest services/ai/tests -q
```

검증 범위는 채팅 → 마스킹·원문 제외 → 실제 ai_db 저장 → HTTP 통계, KST 양끝 경계,
대소문자·악센트·후행 공백의 완전 일치, 요약 NULL, 건수 정렬·limit·빈 결과,
동시 INSERT 사이 세 집계의 동일 스냅샷, 400/503·2초 응답·동시 작업 상한이다.
리뷰에서 짧은 원문이 요약에 복사되는 경우·밀리초 반올림을 찾아 수정 후 재실행했다.
최초 DB 테스트의 빈 기간 기대값은 다음 날 자정 행을 잘못 제외해 한 번 실패했고,
조회 기간과 기대값을 바로잡아 다음 날 포함·그 이후 빈 기간을 각각 검증했다.

실제 RDS CA/호스트 검증·Flyway/Secret 마운트·ops-api/NetworkPolicy 연결,
전체 서비스 이미지 빌드는 아직 검증하지 않았다. 배포·외부 쓰기·RAG·복제본 구현은 하지 않았다.

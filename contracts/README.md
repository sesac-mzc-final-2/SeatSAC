# contracts — 서비스 간 약속

| 폴더 | 내용 | 누가 읽나 |
| --- | --- | --- |
| `openapi/` | 서비스별 외부 API (OpenAPI 3.1, `servers: /api`) | 화면(한채연·남궁일), Gateway, 테스트 |
| `openapi/internal/` | 내부 API `/internal/**` (Gateway 비노출, NetworkPolicy로 호출자 제한) | 호출하는 서비스 |
| `events/` | 메시지 JSON Schema (draft 2020-12). `seat-event.v1.json`(Valkey Pub/Sub → SSE), `booking.confirmed.v1.json`(SQS) | 발행·소비 서비스, Lambda |
| `schemas/` | 공용 스키마. `error.v1.json`(오류 응답 `{code, message, traceId, details?}`) | 모든 서비스 (Java·Python 테스트가 같은 파일을 읽음) |
| `*/examples/{스키마 이름}/` | 예시 메시지·응답. CI가 스키마로 검증하고, 서비스 테스트의 fixture로도 쓴다 | 테스트 |
| `tools/` | 계약 CI 도구 (`check.sh`, `check_schema_compat.py`) | 사람·CI |
| `redocly.yaml` | OpenAPI lint 규칙 | CI |

**원칙**: 설계 먼저(yaml·json → PR 합의 → 구현). URL 버전 없음, **하위 호환만**(필드 추가만). 깨지는 변경은 새 엔드포인트 또는 새 버전 파일로 (ADR-32, 03a).

---

## 계약 CI (`.github/workflows/contract-check.yml`, WBS 2.2.7)

`contracts/**`를 바꾸는 PR에서 자동 실행된다. 브랜치 보호의 필수 검사로 등록한다.

| 단계 | 도구 | 실패 조건 |
| --- | --- | --- |
| OpenAPI lint | Redocly CLI 1.34.5 + `redocly.yaml` | 구조 오류(`struct`), 보안 정의 누락(`security-defined`), 잘못된 예시. `operationId`·4xx 응답 누락은 경고 |
| JSON Schema 자체 검증 | check-jsonschema `--check-metaschema` | 스키마 문법 오류 |
| 예시 검증 | check-jsonschema `--schemafile` | `examples/{이름}/*.json`이 스키마와 안 맞음 (uuid·date-time 형식 포함) |
| OpenAPI 하위 호환 | oasdiff 1.11.7 `breaking --fail-on ERR` (base 브랜치 대비) | 엔드포인트·필드 삭제, 타입 변경, 필수 요청 필드 추가, enum 값 삭제, 명세 파일 삭제 등. 변경 내역은 실행 요약(Summary)에 markdown으로 |
| 이벤트·공용 스키마 하위 호환 | `tools/check_schema_compat.py` (base 대비) | 아래 "호환 규칙"의 ERROR |

### 호환 규칙 (이벤트·공용 스키마)

| 구분 | 변경 |
| --- | --- |
| ✅ 허용 | 선택 속성 추가, 설명 변경, 새 스키마 파일 추가(새 버전 `*.v2.json` 포함), 타입 넓히기(`integer` → `["integer","null"]`) |
| ⚠️ 경고 | enum 값 추가(소비자가 모르는 값을 무시하는지 확인), 필수 → 선택 완화 |
| ❌ 실패 | 기존 파일 삭제, 속성 삭제, 타입 변경·축소, **필수 필드 추가**, enum 값 삭제, `const` 변경, `additionalProperties: false` 추가, 최소·최대 제약 새로 추가 또는 강화, `format`·`pattern` 변경 |

- 소비자는 **모르는 필드를 무시**한다. 그래서 스키마에 `additionalProperties: false`를 두지 않는다(오류 응답 `error.v1.json`은 우리가 만드는 응답이라 예외).
- 메시지 버전은 envelope의 `version`과 파일 이름(`.v1`)에만 둔다. 깨지는 변경이 꼭 필요하면 `*.v2.json`을 새로 만들고, 발행자가 두 버전을 함께 내보내는 기간을 둔다.

### 설계 단계에서 일부러 바꿀 때

구현 전(M1~M2)에는 계약이 자주 바뀐다. 아직 아무도 구현하지 않은 부분을 바꾸는 경우만:

1. PR에 라벨 **`contract-break-ok`**를 붙인다 → 하위 호환 검사가 실패 대신 경고로 바뀐다
2. 영향받는 담당자 전원의 승인을 받는다 (CODEOWNERS: `/contracts/`)
3. PR 본문 "계약 변경"에 무엇을 깨는지 적는다

구현이 시작된 엔드포인트·메시지는 라벨을 쓰지 않는다.

### 알려진 한계

- oasdiff는 배열 `maxItems` 축소 같은 일부 제약 강화를 잡지 못한다. 리뷰에서 확인한다.
- **구현과 계약의 일치**(springdoc 생성본 vs `contracts/openapi`)는 아직 검사하지 않는다. 서비스 구현 후 `pr-check`에 추가한다(2단계): 테스트에서 `/v3/api-docs`를 받아 `oasdiff breaking contracts/openapi/{service}.openapi.yaml <생성본>`으로 비교. Spring 쪽은 `springdoc.api-docs.version: openapi_3_1`로 맞춘다.
- 이벤트는 발행·소비 테스트가 `examples/`와 스키마 파일을 직접 읽어 검증한다(각 서비스 테스트 몫).

---

## 로컬에서 미리 돌리기

```bash
# 한 번만
pip install check-jsonschema==0.38.2
brew install oasdiff            # 또는 GitHub Releases의 oasdiff_1.11.7

# PR 올리기 전
contracts/tools/check.sh                 # origin/main과 비교
contracts/tools/check.sh origin/feat/X   # 다른 기준과 비교
```

## 파일 추가 규칙

- 외부 API: `openapi/{service}.openapi.yaml`, 내부 API: `openapi/internal/{service}.internal.openapi.yaml`
- 이벤트: `events/{이름}.v{N}.json`, 예시는 `events/examples/{이름}.v{N}/*.json` (최소 1개)
- 루트에 `security: []`를 두고, 인증이 필요한 operation에만 `security: [ { bearerAuth: [] } ]`
- 오류 응답은 `$ref: "../schemas/error.v1.json"` (내부 API는 `../../schemas/error.v1.json`). 어떤 `code`가 나오는지는 응답 `description`에 적는다
- 확장 필드: `x-feature-id`(기능명세서 ID, 예 `BKG-02`), `x-required-role: ADMIN`(관리자 경로), `x-caller`(내부 API 호출자 = NetworkPolicy 허용 목록), `x-status: proposed`(❓ 결정 전 제안 API)
- `description`·`summary` 값에 `: `나 `{`가 들어가면 따옴표로 감싼다 (YAML 파싱 오류)

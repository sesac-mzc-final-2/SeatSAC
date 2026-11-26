# ops-api — 관리자 API `/ops` (6.1.7 · ADR-119)

계약: `contracts/openapi/ops.openapi.yaml`(8개, OPS-20~24). 실행 규칙: `docs/service-runtime-contract.md` 10장.

| 경로 | 하는 일 | 하위 호출 |
| --- | --- | --- |
| `GET /ops/services` | 서비스·노드 상태 | Kubernetes API(읽기) · Prometheus(정해 둔 PromQL) |
| `POST /ops/services/{svc}/stop`·`start` | replicas 0 ↔ 정지 전 값 | `*/scale` patch · ConfigMap `ops-api-state` |
| `GET /ops/reports/errors/dates`·`errors`·`errors/{queryId}` | 일자별 오류 리포트 | S3 · Athena(Pod Identity) |
| `GET /ops/ai/unmet-demand` | AI 범위 밖 질문 통계 | ai `GET /internal/ai/unmet-demand/stats` |
| `POST`·`GET /ops/coupons/events` | 쿠폰 이벤트 생성·목록 | coupon `/internal/coupon-events` |

- 인증: Cognito Access Token을 직접 검증(JWKS·issuer·`client_id`·`token_use=access`) + `cognito:groups` ∋ `ADMIN`. gateway를 거치지 않는다
- 쓰기 조작(정지·가동·쿠폰 이벤트 생성)과 리포트 쿼리 시작은 `ops audit` 로그 한 줄, 지표 `ops_action_total{action,result}`

## 로컬

```bash
cd services/ops-api
uv sync
uv run ruff check .
uv run pytest            # 외부 호출은 모두 가짜(키·AWS·클러스터 불필요)
OPS_ENV=local uv run uvicorn seatsac_ops.main:app --port 8087
```

로컬에는 클러스터·Athena가 없어 상태·리포트 API는 503이다(인증·오류 형식만 확인된다).

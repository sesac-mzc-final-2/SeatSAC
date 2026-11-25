내부 API(`/internal/**`)는 Gateway가 라우팅하지 않는다. 서비스별로 `{service}.internal.openapi.yaml`, `servers: http://{service}:8080`.
호출자는 operation의 `x-caller`, NetworkPolicy(OPS-15)와 같게 유지한다.

| 파일 | API | 호출자 |
| --- | --- | --- |
| `catalog.internal.openapi.yaml` | GET /internal/screenings/{id} · POST /internal/screenings/summaries · GET /internal/theaters | booking · user · user |
| `booking.internal.openapi.yaml` | GET /internal/bookings · GET /internal/screenings/seat-counts (300ms) | user · catalog |
| `payment.internal.openapi.yaml` | POST /internal/payments · GET /internal/payments/{id} · POST /internal/payments/{id}/cancel | booking |
| `coupon.internal.openapi.yaml` | POST /internal/coupon-events · GET /internal/coupon-events | ops-api · ops-api |
| `ai.internal.openapi.yaml` | GET /internal/ai/unmet-demand/stats (2초, 응답은 `ops.openapi.yaml` `UnmetDemandStats`와 같은 모양) | ops-api |
